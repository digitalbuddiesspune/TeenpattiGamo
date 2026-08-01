import Image from "next/image";
import { useCallback, useEffect, useRef, useState } from "react";
import PlayingCard from "./PlayingCard";
import Seat from "./Seat";

const DEAL_SEQUENCE_ORDER = [5, 3, 4, 0, 1, 2];
const FALLBACK_DEALING_WINDOW_MS = 1800;

function HiddenDealCard({ className = "" }) {
  return (
    <div className={className}>
      <PlayingCard
        card={{ id: "deal-card-back", hidden: true }}
        revealed={false}
        compact
      />
    </div>
  );
}

function getRelativeCenter(rect, surfaceRect) {
  return {
    x: rect.left - surfaceRect.left + (rect.width / 2),
    y: rect.top - surfaceRect.top + (rect.height / 2),
  };
}

function getClockwiseSeatOrder(seats = []) {
  const seatLookup = new Map(seats.map((seat) => [seat.seatIndex, seat]));
  return DEAL_SEQUENCE_ORDER.map((seatIndex) => seatLookup.get(seatIndex)).filter(Boolean);
}

function ActionPillButton({ children, tone = "dark", disabled, onClick }) {
  const toneClass = tone === "green"
    ? "border-[#3be7de]/34 bg-[linear-gradient(180deg,#0d7576_0%,#083a3d_100%)] text-white"
    : "border-[#ffffff14] bg-[linear-gradient(180deg,rgba(15,74,78,0.9),rgba(7,30,33,0.95))] text-white";

  return (
    <button
      type="button"
      className={`casino-table-scene__action-pill rounded-full border px-4 py-2 text-[12px] font-black uppercase tracking-[0.14em] shadow-[0_14px_26px_rgba(0,0,0,0.24)] transition ${toneClass} ${
        disabled ? "cursor-not-allowed opacity-45" : "cursor-pointer active:translate-y-[1px]"
      }`}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function TopHudButton({ children, label, onClick, active }) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={active}
      onClick={onClick}
      className={`flex h-[48px] w-[48px] items-center justify-center rounded-[14px] transition active:translate-y-[1px] ${
        active
          ? "opacity-100"
          : "opacity-90"
      }`}
    >
      {children}
    </button>
  );
}

function SettingsPanel({
  open,
  onExitTable,
}) {
  if (!open) {
    return null;
  }

  return (
    <div className="absolute left-0 top-[calc(100%+10px)] z-[44] w-[12.5rem] rounded-[18px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(5,41,45,0.96),rgba(4,20,23,0.98))] p-2 shadow-[0_20px_38px_rgba(0,0,0,0.42)] backdrop-blur-md">
      <button
        type="button"
        className="flex w-full items-center justify-between rounded-[12px] px-3 py-2 text-left text-[12px] font-black uppercase tracking-[0.1em] text-white/88 transition hover:bg-white/6"
        onClick={onExitTable}
      >
        <span>Leave Table</span>
        <span className="text-[#ffcf99]">Exit</span>
      </button>
    </div>
  );
}

function SharedJokersTray({ sharedJokers = [] }) {
  if (!sharedJokers.length) {
    return null;
  }

  return (
    <div className="absolute left-1/2 top-[18%] z-[30] flex -translate-x-1/2 flex-col items-center gap-2 rounded-[18px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(7,49,54,0.92),rgba(4,23,27,0.96))] px-4 py-3 shadow-[0_18px_34px_rgba(0,0,0,0.28)] backdrop-blur-md">
      <span className="text-[9px] font-black uppercase tracking-[0.18em] text-white/72">
        Shared Jokers
      </span>
      <div className="flex gap-2">
        {sharedJokers.map((card, index) => (
          <PlayingCard
            key={card.id || `shared-${index}`}
            card={card}
            revealed={!card.hidden}
            compact
          />
        ))}
      </div>
    </div>
  );
}

function InfoBadge({ label, value, align = "left" }) {
  return (
    <div className={`rounded-[16px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(6,44,48,0.84),rgba(4,22,26,0.94))] px-3 py-2 shadow-[0_16px_28px_rgba(0,0,0,0.3)] backdrop-blur-sm ${align === "right" ? "text-right" : ""}`}>
      <span className="block text-[8px] font-black uppercase tracking-[0.18em] text-white/60">
        {label}
      </span>
      <strong className="mt-1 block text-[13px] font-black text-white sm:text-[14px]">
        {value}
      </strong>
    </div>
  );
}

function ChipBalanceDisplay({ chipBalance }) {
  return (
    <div className="relative flex h-[40px] min-w-[118px] items-center pl-[14px] pr-[8px]">
      <div className="absolute inset-y-[4px] left-[14px] right-0 rounded-full border border-white/12 bg-[linear-gradient(180deg,rgba(60,60,60,0.96),rgba(38,38,38,0.98))] shadow-[0_12px_22px_rgba(0,0,0,0.28)]" />
      <div className="relative z-[1] mr-[-5px] flex h-[32px] w-[32px] shrink-0 items-center justify-center rounded-full bg-[radial-gradient(circle_at_35%_30%,rgba(255,255,255,0.18),rgba(255,255,255,0.02))] shadow-[0_8px_14px_rgba(0,0,0,0.28)]">
        <Image
          src="/newAssets/chip.png"
          alt=""
          width={32}
          height={29}
          aria-hidden="true"
          className="object-contain"
          style={{ width: 28, height: "auto" }}
        />
      </div>
      <div className="relative z-[1] ml-1.5 flex min-w-0 flex-1 items-center justify-end pr-2">
        <strong className="truncate text-[12px] font-black tracking-[0.05em] text-white sm:text-[13px]">
          {(chipBalance || 0).toLocaleString("en-IN")}
        </strong>
      </div>
    </div>
  );
}

export default function CasinoTable({
  round,
  seats,
  onAction,
  onExitTable,
  onStartNextRound,
  onDeclineNextRound,
  nextRoundDecision,
  nextRoundState,
  acting,
  turnClock,
  roundStartClock,
  nowMs,
  waitingForSeat = false,
  waitingMessage = "",
  onDealCard,
  variantState,
  variant,
  chipBalance = 0,
  roomCode = "",
  roomName = "",
  isPrivateMode = false,
  isHost = false,
  canStartNextRound = true,
}) {
  const [dismissedSideShowResultAt, setDismissedSideShowResultAt] = useState(null);
  const [dealerTipRoundId, setDealerTipRoundId] = useState("");
  const [dealerTipAmount, setDealerTipAmount] = useState("");
  const [dealerTipError, setDealerTipError] = useState("");
  const [dealFlightCards, setDealFlightCards] = useState([]);
  const [dealtCounts, setDealtCounts] = useState({});
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [revealedCompleteRoundId, setRevealedCompleteRoundId] = useState(null);
  const surfaceRef = useRef(null);
  const deckAnchorRef = useRef(null);
  const seatAnchorRef = useRef(new Map());
  const dealTimersRef = useRef([]);
  const activeDealAnimationKeyRef = useRef("");
  const isStarting = round?.status === "starting";
  const isDealing = round?.status === "dealing";
  const isComplete = round?.status === "complete";
  const tableSeats = seats ?? round?.seats;
  const result = round?.result || null;
  const dealerTipPrompt = round?.dealerTipPrompt || null;
  const dealerTipPending = Boolean(round?.dealerTipPending && dealerTipPrompt);
  const viewerSeat = tableSeats?.find((seat) => seat.isUser) || null;
  const isWinningViewer = Boolean(result?.winnerId && viewerSeat?.id === result.winnerId);
  const pendingSideShow = round?.pendingSideShow || null;
  const autoAcceptSideshow = Boolean(variantState?.autoAcceptSideshow);
  const sideShowResult = round?.sideShowResult || null;
  const visibleSideShowResult =
    sideShowResult && sideShowResult.resolvedAt !== dismissedSideShowResultAt
      ? sideShowResult
      : null;
  const isTargetPrompt = pendingSideShow?.viewerRole === "target";
  const isRequesterWaiting = pendingSideShow?.viewerRole === "requester";
  const viewerReady = Boolean(nextRoundState?.viewerAccepted);
  const isQueuedSpectator = waitingForSeat;
  const showHostStartAction = isPrivateMode && isHost && !isQueuedSpectator;
  const isHoldingRoundCompleteOverlay =
    isComplete &&
    revealedCompleteRoundId !== round?.id;
  const showRoundCompleteOverlay =
    isComplete &&
    (result || isQueuedSpectator) &&
    !isHoldingRoundCompleteOverlay &&
    !(viewerReady && nextRoundDecision?.expired);
  const finalPayout = typeof result?.payout === "number" ? result.payout : 0;
  const activeDealerTipAmount = dealerTipPending && dealerTipRoundId === round?.id ? dealerTipAmount : "";
  const activeDealerTipError = dealerTipPending && dealerTipRoundId === round?.id ? dealerTipError : "";
  const pendingSideShowSeconds = pendingSideShow?.expiresAt
    ? Math.max(0, Math.ceil((new Date(pendingSideShow.expiresAt).getTime() - nowMs) / 1000))
    : 0;
  const dealingProgress = isDealing && round?.dealingStartedAt && round?.dealingEndsAt
    ? Math.min(
        1,
        Math.max(
          0,
          (nowMs - new Date(round.dealingStartedAt).getTime()) /
            Math.max(new Date(round.dealingEndsAt).getTime() - new Date(round.dealingStartedAt).getTime(), 1),
        ),
      )
    : 0;
  const centeredNotification =
    round?.status === "active" &&
    typeof round?.message === "string" &&
    round.message.includes("has seen their cards")
      ? round.message
      : null;
  const roundStatusProgress = isStarting
    ? Math.min(1, Math.max(0, (roundStartClock?.progress ?? 0) / 100))
    : dealingProgress;
  const sharedJokers = variantState?.sharedJokers || [];
  const cardsPerSeat = Math.max(3, Number(variant?.cardsPerSeat) || 3);
  const showSharedJokersTray = sharedJokers.length > 0 && variant?.publicCardMode !== "third_card_rank_joker";

  const clearDealTimers = useCallback(() => {
    dealTimersRef.current.forEach((timerId) => {
      window.clearTimeout(timerId);
    });
    dealTimersRef.current = [];
  }, []);

  const registerCardAnchor = useCallback((seatId, node) => {
    if (!seatId) {
      return;
    }

    if (node) {
      seatAnchorRef.current.set(seatId, node);
      return;
    }

    seatAnchorRef.current.delete(seatId);
  }, []);

  useEffect(() => () => {
    clearDealTimers();
  }, [clearDealTimers]);

  useEffect(() => {
    if (!isComplete || !round?.id) {
      return undefined;
    }

    if (revealedCompleteRoundId === round.id) {
      return undefined;
    }

    const decisionDeadlineMs = round?.nextRoundDecisionExpiresAt
      ? Math.max(0, new Date(round.nextRoundDecisionExpiresAt).getTime() - Date.now())
      : 2500;
    const delayMs = Math.min(2500, decisionDeadlineMs);
    const timer = window.setTimeout(() => {
      setRevealedCompleteRoundId(round.id);
    }, delayMs);

    return () => {
      window.clearTimeout(timer);
    };
  }, [isComplete, revealedCompleteRoundId, round?.id, round?.nextRoundDecisionExpiresAt]);

  useEffect(() => {
    if (isDealing) {
      return undefined;
    }

    clearDealTimers();
    activeDealAnimationKeyRef.current = "";
    return undefined;
  }, [clearDealTimers, isDealing]);

  useEffect(() => {
    if (!isDealing || !round?.id || !round?.dealingStartedAt || !round?.dealingEndsAt) {
      return undefined;
    }

    const animationKey = `${round.id}:${round.dealingStartedAt}`;

    if (activeDealAnimationKeyRef.current === animationKey) {
      return undefined;
    }

    let cancelled = false;
    let retryTimer = null;
    let attempts = 0;

    const startAnimation = () => {
      if (cancelled || activeDealAnimationKeyRef.current === animationKey) {
        return;
      }

      const orderedSeats = getClockwiseSeatOrder(tableSeats || []);
      const surfaceNode = surfaceRef.current;
      const deckNode = deckAnchorRef.current;
      const anchorsReady = orderedSeats.every((seat) => seatAnchorRef.current.get(seat.id));

      if (!surfaceNode || !deckNode || !orderedSeats.length || !anchorsReady) {
        if (attempts >= 20) {
          return;
        }

        attempts += 1;
        retryTimer = window.setTimeout(startAnimation, 32);
        return;
      }

      const dealingStartedMs = new Date(round.dealingStartedAt).getTime();
      const dealingEndsMs = new Date(round.dealingEndsAt).getTime();
      const dealingWindowMs = Math.max(dealingEndsMs - dealingStartedMs, FALLBACK_DEALING_WINDOW_MS);
      const elapsedMs = Math.max(0, Math.min(dealingWindowMs, nowMs - dealingStartedMs));
      const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      const totalCards = orderedSeats.length * cardsPerSeat;
      const baseTravelMs = prefersReducedMotion ? 140 : Math.max(260, Math.min(380, Math.round(dealingWindowMs / 4.6)));
      const slotGapMs = totalCards > 1
        ? Math.max(48, Math.floor(Math.max(dealingWindowMs - baseTravelMs, 0) / (totalCards - 1)))
        : 0;
      const surfaceRect = surfaceNode.getBoundingClientRect();
      const deckCenter = getRelativeCenter(deckNode.getBoundingClientRect(), surfaceRect);
      const initialCounts = Object.fromEntries(orderedSeats.map((seat) => [seat.id, 0]));
      const nextFlightCards = [];
      let finalAnimationMs = 0;

      clearDealTimers();

      orderedSeats.forEach((seat) => {
        initialCounts[seat.id] = 0;
      });

      orderedSeats.forEach((seat, seatOrderIndex) => {
        const anchorNode = seatAnchorRef.current.get(seat.id);
        const targetCenter = getRelativeCenter(anchorNode.getBoundingClientRect(), surfaceRect);

        for (let passIndex = 0; passIndex < cardsPerSeat; passIndex += 1) {
          const cardOrder = (passIndex * orderedSeats.length) + seatOrderIndex;
          const delayMs = cardOrder * slotGapMs;
          const durationMs = prefersReducedMotion ? 140 : baseTravelMs + (seat.isUser ? 30 : 0);
          const animationEndMs = delayMs + durationMs;
          const landingMs = animationEndMs;
          const flightRotation = ((passIndex - 1) * 7) + ((seat.seatIndex - 2) * 4);

          if (landingMs <= elapsedMs) {
            initialCounts[seat.id] += 1;
          } else {
            const timerId = window.setTimeout(() => {
              setDealtCounts((current) => ({
                ...current,
                [seat.id]: Math.min(cardsPerSeat, (current[seat.id] || 0) + 1),
              }));
              onDealCard?.();
            }, landingMs - elapsedMs);

            dealTimersRef.current.push(timerId);
          }

          if (animationEndMs > elapsedMs) {
            nextFlightCards.push({
              id: `${animationKey}-${seat.id}-${passIndex}`,
              passIndex,
              startX: deckCenter.x,
              startY: deckCenter.y,
              deltaX: targetCenter.x - deckCenter.x,
              deltaY: targetCenter.y - deckCenter.y,
              midX: (targetCenter.x - deckCenter.x) * 0.45,
              midY: ((targetCenter.y - deckCenter.y) * 0.45) - 42,
              midRotation: flightRotation * 0.5,
              delayMs: delayMs - elapsedMs,
              durationMs,
              rotation: flightRotation,
              zIndex: 18 + passIndex,
            });
          }

          finalAnimationMs = Math.max(finalAnimationMs, animationEndMs);
        }
      });

      setDealtCounts(initialCounts);
      setDealFlightCards(nextFlightCards);
      activeDealAnimationKeyRef.current = animationKey;

      if (finalAnimationMs > elapsedMs) {
        const cleanupTimer = window.setTimeout(() => {
          setDealFlightCards([]);
        }, finalAnimationMs - elapsedMs + 40);

        dealTimersRef.current.push(cleanupTimer);
      } else {
        setDealFlightCards([]);
      }
    };

    startAnimation();

    return () => {
      cancelled = true;
      if (retryTimer) {
        window.clearTimeout(retryTimer);
      }
    };
  }, [
    cardsPerSeat,
    clearDealTimers,
    isDealing,
    nowMs,
    onDealCard,
    round?.dealingEndsAt,
    round?.dealingStartedAt,
    round?.id,
    tableSeats,
  ]);

  useEffect(() => {
    if (!settingsOpen) {
      return undefined;
    }

    const close = () => setSettingsOpen(false);
    window.addEventListener("pointerdown", close, { once: true });

    return () => {
      window.removeEventListener("pointerdown", close);
    };
  }, [settingsOpen]);

  async function resolveDealerTipForDecision() {
    const parsed = Number.parseInt(activeDealerTipAmount, 10);

    if (!dealerTipPending) {
      return true;
    }

    if (!Number.isInteger(parsed) || parsed <= 0) {
      setDealerTipRoundId(round?.id || "");
      setDealerTipAmount("");
      setDealerTipError("");
      await onAction("dealer_tip", { amount: 0 });
      return true;
    }

    if (parsed > dealerTipPrompt.maxAmount) {
      setDealerTipRoundId(round?.id || "");
      setDealerTipError(`Tip must be less than ${dealerTipPrompt.winnerReceivableBeforeTip.toLocaleString("en-IN")}.`);
      return false;
    }

    setDealerTipRoundId(round?.id || "");
    setDealerTipError("");
    await onAction("dealer_tip", { amount: parsed });
    return true;
  }

  async function handleNextRoundAccept() {
    if (isWinningViewer && dealerTipPending) {
      const resolved = await resolveDealerTipForDecision();
      if (!resolved) {
        return;
      }
    }
    await onStartNextRound();
  }

  async function handleNextRoundDecline() {
    if (isWinningViewer && dealerTipPending) {
      const resolved = await resolveDealerTipForDecision();
      if (!resolved) {
        return;
      }
    }
    await onDeclineNextRound();
  }

  return (
    <section className="casino-table-scene relative h-dvh overflow-hidden bg-black text-white">
      <div className="pointer-events-none absolute inset-0" aria-hidden="true">
        <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.06),rgba(0,6,8,0.18)_68%,rgba(0,0,0,0.62))]" />
      </div>

      <div className="app-frame app-frame-surface relative z-[20] mx-auto h-dvh overflow-x-hidden overflow-y-auto overscroll-y-contain">
        <div>
          <div className="sticky top-0 z-[40] -mx-3 px-3 pb-2 pt-[max(10px,env(safe-area-inset-top))] sm:mx-0 sm:px-0 sm:pb-3 sm:pt-[18px]">
            <div className="relative flex items-start justify-between">
              <div className="relative" onPointerDown={(event) => event.stopPropagation()}>
                <TopHudButton
                  label="Open settings"
                  onClick={() => setSettingsOpen((current) => !current)}
                  active={settingsOpen}
                >
                  <Image
                    src="/newAssets/settingsButton.png"
                    alt=""
                    width={26}
                    height={26}
                    aria-hidden="true"
                  />
                </TopHudButton>
                <SettingsPanel
                  open={settingsOpen}
                  onExitTable={onExitTable}
                />
              </div>

              <ChipBalanceDisplay chipBalance={chipBalance} />
            </div>

            <div className="pointer-events-none relative mt-1.5 flex items-start justify-between gap-2 sm:mt-2">
              <InfoBadge label="Variant" value={variant?.label || "Teen Patti"} />
              {isPrivateMode && (roomCode || roomName) ? (
                <InfoBadge
                  label={roomCode ? "Room" : "Private"}
                  value={roomCode || roomName}
                  align="right"
                />
              ) : null}
            </div>
          </div>

          <div className="casino-table-scene__stage relative mx-auto flex w-full flex-none items-start justify-center sm:flex-1">
          <div className="casino-table-scene__viewport relative">
            <div ref={surfaceRef} className="casino-table-scene__surface relative">
              <Image
                src="/newAssets/portraitTable.png"
                alt="Teen Patti table"
                fill
                className="casino-table-scene__felt casino-table-scene__felt--portrait object-fill drop-shadow-[0_30px_50px_rgba(0,0,0,0.46)]"
                priority
              />
              <Image
                src="/newAssets/landscape-light.png"
                alt=""
                fill
                priority
                aria-hidden="true"
                className="casino-table-scene__felt casino-table-scene__felt--landscape object-contain drop-shadow-[0_30px_50px_rgba(0,0,0,0.46)]"
              />

              <div className="casino-table-scene__dealer absolute left-1/2 top-0 z-[22] -translate-x-1/2 -translate-y-[58%]">
                <div className="relative">
                  <Image
                    src="/newAssets/dealer.png"
                    alt="Dealer"
                    fill
                    className="object-contain object-center drop-shadow-[0_22px_40px_rgba(0,0,0,0.42)]"
                    priority
                  />
                </div>
              </div>

              {centeredNotification ? (
                <div className="casino-table-scene__message absolute left-1/2 top-[16.5%] z-[36] w-[72%] -translate-x-1/2 rounded-[16px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(7,44,48,0.95),rgba(5,24,27,0.98))] px-3 py-2 text-center text-[11px] font-semibold text-white/82 shadow-[0_18px_36px_rgba(0,0,0,0.32)] backdrop-blur-sm">
                  {centeredNotification}
                </div>
              ) : null}

              {(isStarting || isDealing) ? (
                <div className="casino-table-scene__deck-layer pointer-events-none absolute inset-0 z-[25]">
                  <div className={`casino-table-scene__deck ${isStarting ? "is-shuffling" : "is-dealing"}`}>
                    <div ref={deckAnchorRef} className="casino-table-scene__deck-anchor">
                      <HiddenDealCard className="casino-table-scene__deck-card casino-table-scene__deck-card--base" />
                      <HiddenDealCard className="casino-table-scene__deck-card casino-table-scene__deck-card--mid" />
                      <HiddenDealCard className="casino-table-scene__deck-card casino-table-scene__deck-card--top" />
                    </div>
                  </div>

                  {dealFlightCards.map((card) => (
                    <div
                      key={card.id}
                      className="casino-table-scene__deal-card"
                      style={{
                        left: `${card.startX}px`,
                        top: `${card.startY}px`,
                        animationDelay: `${card.delayMs}ms`,
                        animationDuration: `${card.durationMs}ms`,
                        zIndex: card.zIndex,
                        "--deal-x": `${card.deltaX}px`,
                        "--deal-y": `${card.deltaY}px`,
                        "--deal-mid-x": `${card.midX}px`,
                        "--deal-mid-y": `${card.midY}px`,
                        "--deal-mid-rotation": `${card.midRotation}deg`,
                        "--deal-rotation": `${card.rotation}deg`,
                      }}
                    >
                      <HiddenDealCard />
                    </div>
                  ))}
                </div>
              ) : null}

              {(tableSeats || []).map((seat) => (
                <Seat
                  key={seat.id}
                  seat={seat}
                  isUser={seat.isUser}
                  isRoundComplete={round?.status === "complete"}
                  turnClock={seat.isTurn ? turnClock : null}
                  roundStatus={round?.status}
                  winnerId={result?.winnerId || null}
                  sideShowResult={visibleSideShowResult}
                  dealtCount={isDealing ? (dealtCounts[seat.id] || 0) : 0}
                  cardsPerSeat={cardsPerSeat}
                  publicCardMode={variant?.publicCardMode || "none"}
                  registerCardAnchor={registerCardAnchor}
                />
              ))}

              <div className="casino-table-scene__pot pointer-events-none absolute left-1/2 top-[48.6%] z-[29] -translate-x-1/2 -translate-y-1/2 text-center">
                <div className="mx-auto flex h-[56px] w-[56px] items-center justify-center">
                  <Image
                    src="/newAssets/chip.png"
                    alt=""
                    width={38}
                    height={34}
                    aria-hidden="true"
                    className="object-contain"
                    style={{ width: 38, height: "auto" }}
                  />
                </div>
                <span className="mt-0.5 block text-[8px] font-black uppercase tracking-[0.16em] text-white/54">
                  Table Pot
                </span>
                <strong className="block text-[28px] font-black leading-none text-[#ffde83] sm:text-[32px]">
                  {round?.potAmount?.toLocaleString("en-IN") || "0"}
                </strong>
              </div>

              {showRoundCompleteOverlay ? (
                <div className="casino-table-scene__complete-shell pointer-events-none fixed inset-x-0 bottom-0 z-[34] flex justify-center px-4 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:pb-5">
                  <div className="casino-table-scene__complete-card pointer-events-auto flex w-full max-w-[22rem] flex-col items-center gap-3 rounded-[22px] border border-[#ffffff18] bg-[linear-gradient(180deg,rgba(7,44,48,0.48),rgba(3,16,19,0.58))] px-5 py-4 text-center shadow-[0_22px_40px_rgba(0,0,0,0.22)] backdrop-blur-[10px]">
                    {!isQueuedSpectator && isWinningViewer ? (
                      <div className="rounded-full bg-[linear-gradient(180deg,#fff0a7_0%,#d1a22c_100%)] px-3 py-1 text-[11px] font-black uppercase tracking-[0.16em] text-[#4d2600]">
                        You won this round
                      </div>
                    ) : null}

                    <div>
                      <strong className="block text-lg font-black text-white sm:text-xl">
                        {isQueuedSpectator
                          ? (isPrivateMode ? "Waiting to join next round" : "You are in for the next round")
                          : isWinningViewer
                            ? (isPrivateMode && isHost ? "Start next round?" : "Play next round?")
                            : isPrivateMode && isHost
                              ? "Start another round?"
                              : "Play another round?"}
                      </strong>
                      <span className="mt-1.5 block text-sm font-medium text-white/86 sm:text-[15px]">
                        {isQueuedSpectator
                          ? `You will be seated automatically as soon as the ${isPrivateMode ? "host starts" : "next"} round begins.`
                          : isWinningViewer
                            ? viewerReady
                              ? (isPrivateMode
                                  ? (isHost
                                      ? (canStartNextRound
                                          ? "You won this round. Everyone is ready. Start the next round when you want."
                                          : "You won this round. Wait for another player to join or get ready before starting the next round.")
                                      : "You won this round. You are ready for the host to start the next round.")
                                  : "You won this round. You are in for the next round.")
                              : dealerTipPending
                                ? "You won this round. Add an optional dealer tip, then decide whether you want to continue."
                                : `You won this round. ${round?.message || ""}`.trim()
                            : viewerReady
                              ? (isPrivateMode
                                  ? (isHost
                                      ? (canStartNextRound
                                          ? "Everyone is ready. Start the next round when you want."
                                          : "Wait for another player to join or get ready before starting the next round.")
                                      : "You are ready. Waiting for the host to start the next round.")
                                  : "You are in for the next round.")
                              : round?.dealerTipPending
                                ? `${result.winnerName} won with ${result.winningHand}.`
                                : round?.message}
                      </span>
                    </div>

                    {!isQueuedSpectator && isWinningViewer && finalPayout > 0 ? (
                      <div className="rounded-full border border-[#3be7de]/24 bg-black/22 px-3 py-1 text-[11px] font-bold uppercase tracking-[0.14em] text-[#a6fff2]">
                        Final payout {finalPayout.toLocaleString("en-IN")}
                      </div>
                    ) : null}

                    {!isQueuedSpectator && dealerTipPending && dealerTipPrompt ? (
                      <div className="w-full rounded-[18px] border border-white/10 bg-black/24 p-3 text-left">
                        <label className="mb-2 block text-[12px] font-black uppercase tracking-[0.18em] text-white/82" htmlFor="dealer-tip-input">
                          Dealer&apos;s tip
                        </label>
                        <input
                          id="dealer-tip-input"
                          className="w-full rounded-2xl border border-white/12 bg-white/8 px-4 py-2.5 text-base font-semibold text-white outline-none placeholder:text-white/50"
                          type="text"
                          inputMode="numeric"
                          pattern="[0-9]*"
                          placeholder="Enter amount"
                          value={activeDealerTipAmount}
                          onChange={(event) => {
                            setDealerTipRoundId(round?.id || "");
                            setDealerTipError("");
                            setDealerTipAmount(event.target.value.replace(/[^\d]/g, ""));
                          }}
                        />
                        <span className="mt-2 block text-[13px] text-white/78">
                          Leave blank to skip the tip automatically.
                        </span>
                        {activeDealerTipError ? (
                          <p className="mt-2 text-sm font-semibold text-[#ff9c9c]">{activeDealerTipError}</p>
                        ) : null}
                      </div>
                    ) : null}

                    <div className="rounded-full border border-white/12 bg-white/8 px-4 py-2 text-[13px] font-bold uppercase tracking-[0.14em] text-white/90">
                      {isPrivateMode
                        ? isQueuedSpectator
                          ? "Waiting for host"
                          : isHost
                            ? (canStartNextRound ? "Start when ready" : "Waiting for players")
                            : viewerReady
                              ? "Waiting for host"
                              : "Ready up"
                        : viewerReady || isQueuedSpectator
                          ? `Round will begin in ${nextRoundDecision?.secondsRemaining ?? 0}s`
                          : `${nextRoundDecision?.secondsRemaining ?? 0}s to decide`}
                    </div>

                    {isQueuedSpectator || (viewerReady && !showHostStartAction) ? null : (
                      <div className="flex flex-wrap justify-center gap-2">
                        <ActionPillButton
                          tone="green"
                          onClick={() => {
                            void handleNextRoundAccept();
                          }}
                          disabled={acting || (isPrivateMode && isHost && !canStartNextRound)}
                        >
                          {isPrivateMode && isHost ? "Start" : "Play"}
                        </ActionPillButton>
                        <ActionPillButton
                          onClick={() => {
                            void handleNextRoundDecline();
                          }}
                          disabled={acting}
                        >
                          No
                        </ActionPillButton>
                      </div>
                    )}
                  </div>
                </div>
              ) : null}
            </div>
          </div>
        </div>

        {(isStarting || isDealing) ? (
          <div className="casino-table-scene__deal-status pointer-events-none fixed inset-x-0 bottom-0 z-[32] flex justify-center px-4 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:pb-5">
            <div className="flex w-[min(100%,20rem)] items-center gap-3 rounded-[18px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(8,52,57,0.92),rgba(4,22,25,0.96))] px-3 py-2.5 shadow-[0_18px_34px_rgba(0,0,0,0.26)] backdrop-blur-md">
              <div className="casino-table-scene__deal-icon flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-white/12 bg-white/8">
                <span className={`casino-table-scene__deal-icon-core ${isStarting ? "is-shuffling" : "is-dealing"}`} />
              </div>
              <div className="min-w-0 flex-1">
                <strong className="block text-[11px] font-black uppercase tracking-[0.18em] text-white/90">
                  {isStarting ? "Shuffling cards" : "Dealing clockwise"}
                </strong>
                <span className="mt-1 block truncate text-[11px] text-white/70">
                  {round?.message}
                </span>
                <div className="mt-2 h-[6px] overflow-hidden rounded-full bg-white/10">
                  <span
                    className="block h-full rounded-full bg-[linear-gradient(90deg,#ffd778_0%,#66f7d1_100%)]"
                    style={{ width: `${roundStatusProgress * 100}%` }}
                  />
                </div>
              </div>
              <div className="rounded-full border border-[#3be7de]/20 bg-black/25 px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.16em] text-[#aafff3]">
                {isStarting ? `${roundStartClock?.secondsRemaining ?? 0}s` : `${Math.round(dealingProgress * 100)}%`}
              </div>
            </div>
          </div>
        ) : null}

        {showSharedJokersTray ? <SharedJokersTray sharedJokers={sharedJokers} /> : null}

        {pendingSideShow ? (
          <div className="casino-table-scene__side-show fixed inset-x-0 top-[154px] z-[32] flex justify-center px-4 sm:top-[164px]">
            <div className="w-[min(calc(100vw-24px),21rem)] rounded-[18px] border border-[#ffffff12] bg-[linear-gradient(180deg,rgba(8,52,57,0.95),rgba(4,19,22,0.98))] px-4 py-3 shadow-[0_18px_34px_rgba(0,0,0,0.28)] backdrop-blur-md">
              <div className="mb-2 flex items-center justify-between gap-3">
                <span className="rounded-full border border-[#43d8cc]/22 bg-[#081c1f]/72 px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.16em] text-[#abfff5]">
                  Side Show
                </span>
                {isRequesterWaiting ? (
                  <div className="rounded-full border border-white/12 bg-black/28 px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.14em] text-white/78">
                    {pendingSideShowSeconds}s
                  </div>
                ) : null}
              </div>
              <div className="flex flex-col gap-3">
                <span className="text-sm font-semibold text-white/82">
                  {isTargetPrompt
                    ? `${pendingSideShow.requesterName} requested a side show. ${autoAcceptSideshow ? "Accept" : "Accept or deny"} in ${pendingSideShowSeconds}s.`
                    : isRequesterWaiting
                      ? `Waiting for ${pendingSideShow.targetName} to respond to your side show request.`
                      : `${pendingSideShow.requesterName} requested a side show with ${pendingSideShow.targetName}.`}
                </span>
                {isTargetPrompt ? (
                  <div className="flex gap-2">
                    <ActionPillButton tone="green" disabled={acting} onClick={() => onAction("sideshow_accept")}>
                      Accept
                    </ActionPillButton>
                    {autoAcceptSideshow ? null : (
                      <ActionPillButton disabled={acting} onClick={() => onAction("sideshow_deny")}>
                        Deny
                      </ActionPillButton>
                    )}
                  </div>
                ) : null}
              </div>
            </div>
          </div>
        ) : visibleSideShowResult && !showRoundCompleteOverlay ? (
          <div className="casino-table-scene__side-show-result fixed inset-x-0 top-[154px] z-[32] flex justify-center px-4 sm:top-[164px]">
            <div className="flex w-[min(calc(100vw-24px),21rem)] items-center justify-between gap-3 rounded-[18px] border border-[#ffffff12] bg-[linear-gradient(180deg,rgba(8,52,57,0.95),rgba(4,19,22,0.98))] px-4 py-3 shadow-[0_18px_34px_rgba(0,0,0,0.28)] backdrop-blur-md">
              <div className="min-w-0">
                <span className="mb-1 block rounded-full border border-[#43d8cc]/22 bg-[#081c1f]/72 px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.16em] text-[#abfff5] w-fit">
                  Side Show
                </span>
                <span className="block text-sm font-semibold text-white/82">
                {(visibleSideShowResult.winnerId === visibleSideShowResult.requesterId
                  ? visibleSideShowResult.requesterName
                  : visibleSideShowResult.targetName)} won the side show
                </span>
              </div>
              <button
                type="button"
                className="rounded-full border border-white/10 bg-white/8 px-3 py-1 text-sm font-bold text-white/70"
                aria-label="Close side show result"
                onClick={() => setDismissedSideShowResultAt(visibleSideShowResult.resolvedAt)}
              >
                ×
              </button>
            </div>
          </div>
        ) : null}

        {waitingForSeat ? (
          <div className="casino-table-scene__spectator fixed inset-x-0 bottom-0 z-[32] flex justify-center px-4 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:pb-5">
            <div className="w-full max-w-[22rem] rounded-[16px] border border-[#3be7de]/24 bg-[linear-gradient(180deg,rgba(7,49,54,0.94),rgba(4,21,24,0.98))] px-4 py-3 text-center shadow-[0_14px_24px_rgba(0,0,0,0.3)] backdrop-blur-sm">
              <strong className="block text-[11px] font-black uppercase tracking-[0.14em] text-[#abfff5]">
                Spectating current round
              </strong>
              <span className="mt-1 block text-[12px] text-white/74">
                {waitingMessage}
              </span>
            </div>
          </div>
        ) : null}
        </div>
      </div>
    </section>
  );
}
