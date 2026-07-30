import Image from "next/image";
import { useEffect, useState } from "react";

function getPreviousActiveSeat(round, userSeat) {
  if (!round || !userSeat) {
    return null;
  }

  const actorIndex = round.seats.findIndex((seat) => seat.id === userSeat.id);

  if (actorIndex < 0) {
    return null;
  }

  let pointer = actorIndex;

  for (let i = 0; i < round.seats.length - 1; i += 1) {
    pointer = (pointer - 1 + round.seats.length) % round.seats.length;
    const seat = round.seats[pointer];

    if (seat.active && !seat.packed) {
      return seat;
    }
  }

  return null;
}

export function buildStakeControlState({
  round,
  userSeat,
  acting,
  selectedStake,
}) {
  const roundInactive = !round || round.status !== "active";
  const isTurn = userSeat?.isTurn && round?.status === "active";
  const balance = userSeat?.balance || 0;
  const stake = round?.currentStake || 0;
  const minimumStake = userSeat?.seen ? stake * 2 : stake;
  const raiseStake = userSeat?.seen ? stake * 4 : stake * 2;
  const stakeOptions = [];

  if (minimumStake > 0 && balance >= minimumStake) {
    stakeOptions.push(minimumStake);
  }

  if (raiseStake > minimumStake && balance >= raiseStake) {
    stakeOptions.push(raiseStake);
  }

  const resolvedStake = stakeOptions.includes(selectedStake) ? selectedStake : stakeOptions[0] || 0;
  const selectedIndex = stakeOptions.indexOf(resolvedStake);
  const displayStake = resolvedStake || minimumStake || 0;
  const controlsLocked = Boolean(acting);

  return {
    roundInactive,
    isTurn,
    balance,
    stake,
    minimumStake,
    raiseStake,
    stakeOptions,
    resolvedStake,
    selectedIndex,
    displayStake,
    canAffordCall: balance >= minimumStake,
    canIncreaseStake:
      isTurn &&
      !controlsLocked &&
      selectedIndex !== -1 &&
      selectedIndex < stakeOptions.length - 1,
    canDecreaseStake:
      isTurn &&
      !controlsLocked &&
      selectedIndex > 0,
  };
}

function ActionButton({ label, onClick, disabled, busy = false, tone = "primary", className = "" }) {
  const isLongLabel = String(label).length >= 8;
  const isSideShowLabel = String(label).toLowerCase() === "side show";
  const toneClasses = {
    danger:
      "border-[#ffb68d]/48 bg-[linear-gradient(135deg,rgba(124,37,26,0.98)_0%,rgba(91,22,18,0.98)_48%,rgba(58,11,12,1)_100%)] text-[#fff0d6] shadow-[inset_0_1px_0_rgba(255,221,199,0.24),0_14px_22px_rgba(46,7,8,0.45)]",
    info:
      "border-[#71f0e2]/46 bg-[linear-gradient(135deg,rgba(15,101,100,0.98)_0%,rgba(10,72,74,0.98)_42%,rgba(7,43,47,1)_100%)] text-[#fff0ac] shadow-[inset_0_1px_0_rgba(199,255,245,0.18),0_14px_22px_rgba(4,25,28,0.42)]",
    gold:
      "border-[#f7d98c]/48 bg-[linear-gradient(135deg,rgba(161,112,34,0.98)_0%,rgba(123,82,17,0.98)_46%,rgba(69,43,9,1)_100%)] text-[#fff5d8] shadow-[inset_0_1px_0_rgba(255,245,209,0.2),0_14px_22px_rgba(42,24,3,0.42)]",
    primary:
      "border-[#86ecde]/46 bg-[linear-gradient(135deg,rgba(22,120,124,0.98)_0%,rgba(13,86,90,0.98)_38%,rgba(10,61,66,0.98)_62%,rgba(5,30,33,1)_100%)] text-[#fff2b6] shadow-[inset_0_1px_0_rgba(219,255,247,0.18),0_16px_24px_rgba(4,25,28,0.44)]",
  };

  return (
    <button
      type="button"
      aria-busy={busy}
      className={[
        "table-controls__button relative inline-flex h-[42px] min-w-0 flex-1 items-center justify-center overflow-hidden rounded-[13px] border px-2 text-[8px] font-black uppercase tracking-[0.04em] text-[#f3e6a7] whitespace-nowrap transition sm:h-[48px] sm:text-[8px]",
        isLongLabel ? "flex-[1.3] px-1.5 text-[7px] tracking-[0.01em] sm:px-2 sm:text-[7.5px]" : "px-2 sm:px-2.5",
        isSideShowLabel ? "min-w-[74px] text-[6.5px] sm:min-w-[88px] sm:text-[7px]" : "",
        toneClasses[tone],
        className,
        busy
          ? "cursor-wait"
          : disabled
            ? "cursor-not-allowed border-white/10 bg-[linear-gradient(180deg,rgba(46,47,43,0.9),rgba(25,26,24,0.96))] text-[#d8c89a]/35 shadow-none saturate-50"
            : "cursor-pointer active:translate-y-[1px]"
      ].join(" ")}
      onClick={onClick}
      disabled={disabled || busy}
    >
      <span
        className={[
          busy ? "opacity-0" : "",
          "block max-w-full text-center leading-none",
          isLongLabel ? "px-0.5 tracking-[0.005em] sm:px-1" : ""
        ].join(" ")}
      >
        {label}
      </span>
      {busy ? (
        <span className="absolute inset-0 flex items-center justify-center gap-1 text-[10px] tracking-[0.08em] text-[#f7e39c]">
          <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse" />
          <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse [animation-delay:120ms]" />
          <span className="h-1.5 w-1.5 rounded-full bg-current animate-pulse [animation-delay:240ms]" />
        </span>
      ) : null}
    </button>
  );
}

function StakeDisplay({ amount, compact = false }) {
  return (
    <div
      className={[
        "table-controls__stake-display flex items-center justify-center gap-2 rounded-full border border-[#efd89a]/45 bg-[linear-gradient(180deg,rgba(62,47,24,0.96),rgba(28,22,16,0.98))] shadow-[inset_0_1px_0_rgba(255,243,214,0.16),0_16px_24px_rgba(0,0,0,0.34)]",
        compact
          ? "h-[30px] min-w-[72px] px-2 sm:min-w-[84px]"
          : "h-[42px] min-w-[160px] px-4 sm:min-w-[174px]"
      ].join(" ")}
    >
      <Image
        src="/newAssets/Chip.png"
        alt=""
        width={22}
        height={22}
        className={compact ? "h-[13px] w-[13px]" : "h-[20px] w-[20px]"}
        aria-hidden="true"
      />
      <span className={compact ? "text-[10px] font-black tracking-[0.02em] text-[#ffefba] sm:text-[12px]" : "text-[17px] font-black tracking-[0.04em] text-[#ffefba] sm:text-[18px]"}>
        {amount.toLocaleString("en-IN")}
      </span>
    </div>
  );
}

function StepButton({ symbol, onClick, disabled, busy = false, compact = false }) {
  const symbolClassName =
    symbol === "+"
      ? compact ? "text-[16px]" : "text-[25px]"
      : compact ? "text-[16px]" : "text-[25px]";

  return (
    <button
      className={[
        "table-controls__step relative flex items-center justify-center rounded-full border p-0 font-black shadow-[0_14px_22px_rgba(0,0,0,0.3)] transition",
        compact ? "h-[24px] w-[24px]" : "h-[40px] w-[40px]",
        busy
          ? "cursor-wait border-[#d9bd7e]/45 bg-[linear-gradient(180deg,rgba(70,60,45,0.98),rgba(30,27,23,1))] text-[#f6e3ab]"
          : disabled
          ? "cursor-not-allowed border-[#d5b97b]/14 bg-[linear-gradient(180deg,rgba(55,49,42,0.96),rgba(25,23,20,0.98))] text-[#e5d4ab]/28"
          : "cursor-pointer border-[#d9bd7e]/45 bg-[linear-gradient(180deg,rgba(73,63,47,0.98),rgba(39,34,30,0.98)_46%,rgba(18,17,16,1)_100%)] text-[#f8e6b6] active:translate-y-[1px]"
      ].join(" ")}
      type="button"
      onClick={onClick}
      disabled={disabled || busy}
      aria-label={symbol === "+" ? "Increase amount" : "Decrease amount"}
    >
      <span className={`absolute inset-0 flex items-center justify-center leading-none ${symbolClassName}`}>
        {symbol}
      </span>
    </button>
  );
}

export function StakeSelector({
  amount,
  canIncreaseStake,
  canDecreaseStake,
  onAdjustStake,
  acting,
  compact = false,
  className = "",
}) {
  return (
    <div
      className={[
        "pointer-events-auto flex items-center justify-center rounded-[22px] border border-[#f0ddb3]/20 bg-[linear-gradient(180deg,rgba(13,28,30,0.72),rgba(7,16,19,0.84))] shadow-[0_18px_30px_rgba(0,0,0,0.34)] backdrop-blur-md",
        compact ? "flex-none gap-0.5 px-1.5 py-[3px]" : "gap-2 px-3 py-1.5",
        className
      ].join(" ")}
    >
      <StepButton
        symbol="-"
        onClick={() => onAdjustStake(-1)}
        disabled={!canDecreaseStake}
        busy={acting}
        compact={compact}
      />
      <StakeDisplay amount={amount} compact={compact} />
      <StepButton
        symbol="+"
        onClick={() => onAdjustStake(1)}
        disabled={!canIncreaseStake}
        busy={acting}
        compact={compact}
      />
    </div>
  );
}

export default function TableControls({
  round,
  seats,
  userSeat,
  acting,
  onAction,
  stakeState,
  onAdjustStake,
}) {
  const [pendingAction, setPendingAction] = useState(null);
  const viewerLegalActions = new Set(round?.viewerLegalActions || []);
  const computedStakeState = stakeState || buildStakeControlState({ round, userSeat, acting, selectedStake: 0 });
  const {
    roundInactive,
    isTurn,
    minimumStake,
    displayStake,
    canAffordCall,
    canIncreaseStake,
    canDecreaseStake,
  } = computedStakeState;
  const pendingSideShow = round?.pendingSideShow || null;
  const sideShowViewerRole = pendingSideShow?.viewerRole || null;
  const hasPendingSideShow = Boolean(pendingSideShow);
  const activePlayers = round?.remainingPlayers?.length || 0;
  const effectiveRound = seats ? { ...round, seats } : round;
  const previousActiveSeat = getPreviousActiveSeat(effectiveRound, userSeat);
  const canPack = isTurn && userSeat && !userSeat.packed;
  const canSee = isTurn && viewerLegalActions.has("see");
  const canSideshow =
    isTurn && viewerLegalActions.has("sideshow") && userSeat?.seen && activePlayers > 2 && previousActiveSeat?.seen && !hasPendingSideShow;
  const callLabel = userSeat?.seen ? "Chaal" : "Blind";
  const canShow = isTurn && viewerLegalActions.has("show") && activePlayers === 2 && canAffordCall;
  const sideLabel = canShow ? "Show" : "Side Show";
  const canUseSideAction = canShow || canSideshow;
  const actionBlocked = hasPendingSideShow || sideShowViewerRole === "target";
  const controlsDisabled = acting || actionBlocked;

  useEffect(() => {
    if (!acting) {
      setPendingAction(null);
    }
  }, [acting]);

  async function triggerAction(actionType) {
    if (acting || !actionType) {
      return;
    }

    setPendingAction(actionType);
    try {
      await onAction(actionType);
    } finally {
      setPendingAction((current) => (acting ? current : null));
    }
  }

  async function handleBet() {
    if (roundInactive || !isTurn || controlsDisabled || !displayStake) {
      return;
    }

    const isRaisedStake = displayStake > minimumStake;
    await triggerAction(isRaisedStake ? "raise" : userSeat?.seen ? "chaal" : "blind");
  }

  return (
    <section className="table-controls app-frame pointer-events-none fixed bottom-0 left-1/2 z-50 flex -translate-x-1/2 flex-col items-center px-3 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:px-4 sm:pb-4">
      <div className="flex w-full flex-col items-center gap-1.5 sm:gap-2.5">
        <div className="pointer-events-auto flex w-full justify-start sm:justify-center">
          <StakeSelector
            amount={displayStake}
            canIncreaseStake={canIncreaseStake}
            canDecreaseStake={canDecreaseStake}
            onAdjustStake={onAdjustStake}
            acting={acting}
            compact
            className="mr-auto sm:mx-auto"
          />
        </div>
        <div className="pointer-events-auto flex w-full items-center justify-center gap-1.5 rounded-[20px] border border-[#f2ddb3]/12 bg-[linear-gradient(180deg,rgba(8,21,24,0.7),rgba(6,14,17,0.86))] px-2 py-1.5 shadow-[0_18px_30px_rgba(0,0,0,0.32)] backdrop-blur-md sm:gap-2 sm:py-2">
          <ActionButton
            label="Pack"
            tone="danger"
            onClick={() => triggerAction("pack")}
            disabled={roundInactive || !canPack || controlsDisabled}
            busy={acting && pendingAction === "pack"}
            className="flex-[0.88]"
          />
          <ActionButton
            label={sideLabel}
            tone="gold"
            onClick={() => triggerAction(canShow ? "show" : "sideshow")}
            disabled={roundInactive || !canUseSideAction || controlsDisabled}
            busy={acting && pendingAction === (canShow ? "show" : "sideshow")}
            className="flex-[1.52]"
          />
          <ActionButton
            label="See"
            tone="info"
            onClick={() => triggerAction("see")}
            disabled={controlsDisabled || !canSee}
            busy={acting && pendingAction === "see"}
            className="flex-[0.78]"
          />
          <ActionButton
            label={displayStake > minimumStake ? "Raise" : callLabel}
            tone="primary"
            onClick={handleBet}
            disabled={
              roundInactive ||
              !isTurn ||
              !canAffordCall ||
              controlsDisabled ||
              (!viewerLegalActions.has(userSeat?.seen ? "chaal" : "blind") && !viewerLegalActions.has("raise"))
            }
            busy={acting && ["blind", "chaal", "raise"].includes(pendingAction)}
            className="flex-[1.02]"
          />
        </div>
      </div>
    </section>
  );
}
