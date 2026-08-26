import Image from "next/image";
import { useEffect, useState } from "react";

export function buildStakeControlState({
  round,
  userSeat,
  acting,
}) {
  const roundInactive = !round || round.status !== "active";
  const isTurn = userSeat?.isTurn && round?.status === "active";
  const balance = userSeat?.balance || 0;
  const stake = round?.currentStake || 0;
  const minimumStake = userSeat?.seen ? stake * 2 : stake;
  const controlsLocked = Boolean(acting);

  return {
    roundInactive,
    isTurn,
    balance,
    stake,
    minimumStake,
    canAffordCall: balance >= minimumStake,
    controlsLocked,
  };
}

function ActionButton({ label, onClick, disabled, busy = false, tone = "primary", className = "" }) {
  const toneClasses = {
    pack:
      "border-[#ff8f7a]/55 bg-[linear-gradient(180deg,#d43a2a_0%,#9a1610_55%,#6a0c0a_100%)] text-[#fff1e4] shadow-[inset_0_1px_0_rgba(255,210,190,0.28),0_10px_18px_rgba(70,8,6,0.45)]",
    blind:
      "border-[#8eb6ff]/55 bg-[linear-gradient(180deg,#3d6fe0_0%,#2448a8_55%,#152a72_100%)] text-[#f3f7ff] shadow-[inset_0_1px_0_rgba(210,225,255,0.28),0_10px_18px_rgba(12,28,80,0.45)]",
    chaal:
      "border-[#6dff9a]/55 bg-[linear-gradient(180deg,#22c45a_0%,#148a3a_55%,#0b5a26_100%)] text-[#f0fff4] shadow-[inset_0_1px_0_rgba(210,255,220,0.28),0_10px_18px_rgba(6,50,20,0.45)]",
    sideshow:
      "border-[#ffd56a]/55 bg-[linear-gradient(180deg,#e8a820_0%,#b87410_55%,#7a4a08_100%)] text-[#fff8e6] shadow-[inset_0_1px_0_rgba(255,240,190,0.3),0_10px_18px_rgba(60,35,4,0.45)]",
    show:
      "border-[#f0a0ff]/55 bg-[linear-gradient(180deg,#b44ad8_0%,#7a28a8_55%,#4e1872_100%)] text-[#fdf0ff] shadow-[inset_0_1px_0_rgba(240,210,255,0.28),0_10px_18px_rgba(50,12,70,0.45)]",
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
        "table-controls__button relative inline-flex h-[40px] min-w-0 flex-1 items-center justify-center overflow-hidden rounded-[12px] border px-1 text-[9px] font-black uppercase tracking-[0.03em] whitespace-nowrap transition sm:h-[52px] sm:rounded-[14px] sm:px-1.5 sm:text-[10px] sm:tracking-[0.06em]",
        toneClasses[tone] || toneClasses.primary,
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
      <span className={["block max-w-full text-center leading-tight", busy ? "opacity-0" : ""].join(" ")}>
        {label}
      </span>
      {busy ? (
        <span className="absolute inset-0 flex items-center justify-center gap-1 text-[9px] tracking-[0.05em] text-[#f7e39c] sm:text-[10px] sm:tracking-[0.08em]">
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
        src="/newAssets/chip.png"
        alt=""
        width={22}
        height={20}
        className="object-contain"
        style={compact ? { width: 13, height: "auto" } : { width: 20, height: "auto" }}
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

function RaiseStakeButton({ label, amount, selected, onClick, disabled, busy }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || busy}
      className={[
        "table-controls__raise-option relative flex min-w-0 flex-1 items-center justify-center gap-1 rounded-[10px] border px-1.5 py-1 transition sm:gap-1.5 sm:px-2 sm:py-1.5",
        selected
          ? "border-[#ffe888]/65 bg-[linear-gradient(180deg,rgba(72,48,10,0.98),rgba(36,20,4,0.98))] text-[#fff4d0]"
          : "border-[#f0ddb3]/16 bg-[linear-gradient(180deg,rgba(16,30,32,0.94),rgba(8,14,16,0.98))] text-[#d8c89a]",
        disabled || busy
          ? "cursor-not-allowed opacity-45"
          : "cursor-pointer active:translate-y-[1px]",
      ].join(" ")}
    >
      <span className="text-[9px] font-black uppercase leading-none tracking-[0.06em] sm:text-[11px]">
        {label}
      </span>
      <Image
        src="/newAssets/chip.png"
        alt=""
        width={14}
        height={13}
        className="object-contain"
        style={{ width: 11, height: "auto" }}
        aria-hidden="true"
      />
      <span className="text-[11px] font-black leading-none sm:text-[13px]">
        {amount.toLocaleString("en-IN")}
      </span>
      {busy ? (
        <span className="absolute inset-0 flex items-center justify-center rounded-[inherit] bg-black/20">
          <span className="h-1 w-1 animate-pulse rounded-full bg-[#ffe888]" />
        </span>
      ) : null}
    </button>
  );
}

export function RaiseStakeSelector({
  stakeOptions,
  resolvedStake,
  onSelectStake,
  acting,
  disabled = false,
  className = "",
}) {
  const primaryStake = stakeOptions[0] || 0;
  const doubleRaiseStake = stakeOptions[1] || 0;
  const showDoubleRaise = stakeOptions.length > 1 && doubleRaiseStake > primaryStake;

  return (
    <div
      className={[
        "pointer-events-auto flex max-w-[11rem] items-stretch justify-center gap-1 rounded-[14px] border border-[#f0ddb3]/18 bg-[linear-gradient(180deg,rgba(13,28,30,0.72),rgba(7,16,19,0.84))] px-1.5 py-1 shadow-[0_12px_22px_rgba(0,0,0,0.28)] backdrop-blur-md sm:max-w-[12.5rem] sm:gap-1.5 sm:px-2 sm:py-1.5",
        className,
      ].join(" ")}
    >
      <RaiseStakeButton
        label="Raise"
        amount={primaryStake}
        selected={resolvedStake === primaryStake}
        onClick={() => onSelectStake(primaryStake)}
        disabled={disabled || !primaryStake}
        busy={acting}
      />
      {showDoubleRaise ? (
        <RaiseStakeButton
          label="2X"
          amount={doubleRaiseStake}
          selected={resolvedStake === doubleRaiseStake}
          onClick={() => onSelectStake(doubleRaiseStake)}
          disabled={disabled}
          busy={acting}
        />
      ) : null}
    </div>
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
}) {
  const [pendingAction, setPendingAction] = useState(null);
  const viewerLegalActions = new Set(round?.viewerLegalActions || []);
  const computedStakeState = stakeState || buildStakeControlState({ round, userSeat, acting });
  const {
    roundInactive,
    isTurn,
    canAffordCall,
  } = computedStakeState;
  const pendingSideShow = round?.pendingSideShow || null;
  const sideShowViewerRole = pendingSideShow?.viewerRole || null;
  const hasPendingSideShow = Boolean(pendingSideShow);
  const activePlayers = round?.remainingPlayers?.length || 0;
  const hasSeenCards = Boolean(userSeat?.seen);
  const canPack = isTurn && userSeat && !userSeat.packed;
  const canSee = isTurn && viewerLegalActions.has("see");
  const canBlind =
    isTurn &&
    !hasSeenCards &&
    canAffordCall &&
    (viewerLegalActions.has("blind") || viewerLegalActions.has("raise"));
  const canRaise =
    isTurn &&
    hasSeenCards &&
    viewerLegalActions.has("raise");
  const canChaal =
    isTurn &&
    hasSeenCards &&
    canAffordCall &&
    (viewerLegalActions.has("chaal") || viewerLegalActions.has("raise"));
  const canSideshow =
    isTurn && viewerLegalActions.has("sideshow") && !hasPendingSideShow;
  const canShow = isTurn && viewerLegalActions.has("show") && activePlayers === 2 && canAffordCall;
  const canUseSideAction = canShow || canSideshow;
  const actionBlocked = hasPendingSideShow || sideShowViewerRole === "target";
  const controlsDisabled = acting || actionBlocked;
  const sideLabel = canShow ? "Show" : "Side Show";

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

  async function handlePrimaryBet() {
    if (roundInactive || controlsDisabled) {
      return;
    }

    if (hasSeenCards) {
      if (!canRaise) {
        return;
      }
      await triggerAction("raise");
      return;
    }

    if (!canBlind) {
      return;
    }
    await triggerAction("blind");
  }

  async function handleChaal() {
    if (roundInactive || !canChaal || controlsDisabled) {
      return;
    }
    await triggerAction("chaal");
  }

  return (
    <section className="table-controls pointer-events-none fixed bottom-0 left-1/2 z-50 flex w-full max-w-none -translate-x-1/2 flex-col items-center px-2 pb-[calc(env(safe-area-inset-bottom)+10px)] sm:px-4 sm:pb-4">
      <div className="flex w-full flex-col items-center gap-1.5 sm:gap-2">
        {canSee ? (
          <div className="pointer-events-auto flex w-full justify-end">
            <ActionButton
              label="See"
              tone="info"
              onClick={() => triggerAction("see")}
              disabled={controlsDisabled}
              busy={acting && pendingAction === "see"}
              className="!flex-none min-w-[68px] max-w-[92px] sm:min-w-[76px]"
            />
          </div>
        ) : null}

        <div className="pointer-events-auto grid w-full grid-cols-4 gap-1.5 rounded-[18px] border border-[#f2ddb3]/14 bg-[linear-gradient(180deg,rgba(8,12,18,0.78),rgba(4,8,12,0.9))] px-1.5 py-1.5 shadow-[0_18px_30px_rgba(0,0,0,0.38)] backdrop-blur-md sm:gap-2 sm:px-2 sm:py-2">
          <ActionButton
            label="Pack"
            tone="pack"
            onClick={() => triggerAction("pack")}
            disabled={roundInactive || !canPack || controlsDisabled}
            busy={acting && pendingAction === "pack"}
          />
          <ActionButton
            label={hasSeenCards ? "Raise" : "Blind"}
            tone={hasSeenCards ? "gold" : "blind"}
            onClick={handlePrimaryBet}
            disabled={roundInactive || (hasSeenCards ? !canRaise : !canBlind) || controlsDisabled}
            busy={acting && ["blind", "raise"].includes(pendingAction)}
          />
          <ActionButton
            label="Chaal"
            tone="chaal"
            onClick={handleChaal}
            disabled={roundInactive || !canChaal || controlsDisabled}
            busy={acting && pendingAction === "chaal"}
          />
          <ActionButton
            label={sideLabel}
            tone={canShow ? "show" : "sideshow"}
            onClick={() => triggerAction(canShow ? "show" : "sideshow")}
            disabled={roundInactive || !canUseSideAction || controlsDisabled}
            busy={acting && pendingAction === (canShow ? "show" : "sideshow")}
          />
        </div>
      </div>
    </section>
  );
}
