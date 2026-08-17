import Image from "next/image";
import { useCallback, useEffect, useRef, useState } from "react";
import PlayingCard from "./PlayingCard";
import Seat from "./Seat";
import TableCelebration from "./TableCelebration";

const DEAL_SEQUENCE_ORDER = [2, 3, 4, 0, 1];
const FALLBACK_DEALING_WINDOW_MS = 1800;
const POT_PAYOUT_DURATION_MS = 2800;
const POT_CHIP_COUNT = 8;
const CHIP_TRANSFER_COUNT = 5;
const CHIP_TRANSFER_DURATION_MS = 1800;
const CELEBRATION_DURATION_MS = 5000;

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

function buildChipFlightsBetweenPoints(startCenter, targetCenter, idPrefix, amount, amountPrefix = "+") {
  return Array.from({ length: CHIP_TRANSFER_COUNT }, (_, index) => {
    const spread = (index - (CHIP_TRANSFER_COUNT - 1) / 2) * 10;
    const deltaX = targetCenter.x - startCenter.x + spread;
    const deltaY = targetCenter.y - startCenter.y + (index % 2 === 0 ? -6 : 6);

    return {
      id: `${idPrefix}-${index}`,
      startX: startCenter.x,
      startY: startCenter.y,
      deltaX,
      deltaY,
      midX: deltaX * 0.45,
      midY: deltaY * 0.35 - 40,
      delayMs: index * 100,
      durationMs: CHIP_TRANSFER_DURATION_MS,
      showAmount: index === Math.floor(CHIP_TRANSFER_COUNT / 2),
      amount,
      amountPrefix,
    };
  });
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
      className={`flex h-9 w-9 items-center justify-center rounded-[12px] transition active:translate-y-[1px] sm:h-[48px] sm:w-[48px] sm:rounded-[14px] ${
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

function InfoBadge({ label, value, align = "left", tone = "default" }) {
  const toneClass = tone === "red"
    ? "border-[#e8b53c]/35 bg-[linear-gradient(180deg,rgba(140,22,28,0.94),rgba(72,8,14,0.98))]"
    : "border-[#ffffff14] bg-[linear-gradient(180deg,rgba(6,44,48,0.84),rgba(4,22,26,0.94))]";

  return (
    <div className={`rounded-[12px] border px-2.5 py-1 shadow-[0_12px_22px_rgba(0,0,0,0.28)] backdrop-blur-sm sm:rounded-[16px] sm:px-3 sm:py-2 sm:shadow-[0_16px_28px_rgba(0,0,0,0.3)] ${toneClass} ${align === "right" ? "text-right" : ""}`}>
      <span className={`block text-[7px] font-black uppercase tracking-[0.16em] sm:text-[8px] sm:tracking-[0.18em] ${tone === "red" ? "text-[#ffe6a0]/78" : "text-white/60"}`}>
        {label}
      </span>
      <strong className={`mt-0.5 block text-[11px] font-black sm:mt-1 sm:text-[14px] ${tone === "red" ? "text-[#fff4d4]" : "text-white"}`}>
        {value}
      </strong>
    </div>
  );
}

function ChipBalanceDisplay({ chipBalance }) {
  return (
    <div className="relative flex h-[34px] min-w-[100px] items-center pl-[12px] pr-[6px] sm:h-[40px] sm:min-w-[118px] sm:pl-[14px] sm:pr-[8px]">
      <div className="absolute inset-y-[3px] left-[12px] right-0 rounded-full border border-white/12 bg-[linear-gradient(180deg,rgba(60,60,60,0.96),rgba(38,38,38,0.98))] shadow-[0_10px_18px_rgba(0,0,0,0.26)] sm:inset-y-[4px] sm:left-[14px] sm:shadow-[0_12px_22px_rgba(0,0,0,0.28)]" />
      <div className="relative z-[1] mr-[-4px] flex h-[28px] w-[28px] shrink-0 items-center justify-center rounded-full bg-[radial-gradient(circle_at_35%_30%,rgba(255,255,255,0.18),rgba(255,255,255,0.02))] shadow-[0_8px_14px_rgba(0,0,0,0.28)] sm:mr-[-5px] sm:h-[32px] sm:w-[32px]">
        <Image
          src="/newAssets/chip.png"
          alt=""
          width={32}
          height={29}
          aria-hidden="true"
          className="object-contain"
          style={{ width: 24, height: "auto" }}
        />
      </div>
      <div className="relative z-[1] ml-1 flex min-w-0 flex-1 items-center justify-end pr-1.5 sm:ml-1.5 sm:pr-2">
        <strong className="truncate text-[11px] font-black tracking-[0.05em] text-white sm:text-[13px]">
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
  maxPotAmount = 0,
  roomCode = "",
  roomName = "",
  isPrivateMode = false,
  isHost = false,
  canStartNextRound = true,
  isPublicTable = false,
}) {
  const [dismissedSideShowResultAt, setDismissedSideShowResultAt] = useState(null);
  const [midGameTipAmount, setMidGameTipAmount] = useState(10);
  const [midGameTipSending, setMidGameTipSending] = useState(false);
  const [dealFlightCards, setDealFlightCards] = useState([]);
  const [dealtCounts, setDealtCounts] = useState({});
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [revealedCompleteRoundId, setRevealedCompleteRoundId] = useState(null);
  const [potPayoutFlights, setPotPayoutFlights] = useState([]);
  const [chipTransferFlights, setChipTransferFlights] = useState([]);
  const [potCollected, setPotCollected] = useState(false);
  const [potTransferring, setPotTransferring] = useState(false);
  const [celebrateWin, setCelebrateWin] = useState(false);
  const [seatActionNotice, setSeatActionNotice] = useState(null);
  const surfaceRef = useRef(null);
  const potRef = useRef(null);
  const deckAnchorRef = useRef(null);
  const seatAnchorRef = useRef(new Map());
  const dealTimersRef = useRef([]);
  const potPayoutRoundIdRef = useRef("");
  const celebrationRoundIdRef = useRef("");
  const chipTransferKeyRef = useRef("");
  const activeDealAnimationKeyRef = useRef("");
  const lastNotifiedActionRef = useRef("");
  const isStarting = round?.status === "starting";
  const isDealing = round?.status === "dealing";
  const isComplete = round?.status === "complete";
  const tableSeats = seats ?? round?.seats;
  const result = round?.result || null;
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
  const potLimitReachedFromResult = Boolean(result?.potLimitReached);
  const potLimitReachedFromPot =
    isComplete &&
    maxPotAmount > 0 &&
    typeof round?.potAmount === "number" &&
    round.potAmount >= maxPotAmount;
  const potLimitReachedFromMessage = /maximum pot amount reached/i.test(
    `${result?.reason || ""} ${round?.message || ""}`,
  );
  const potLimitReached =
    potLimitReachedFromResult || potLimitReachedFromPot || potLimitReachedFromMessage;
  const isHoldingRoundCompleteOverlay =
    isComplete &&
    revealedCompleteRoundId !== round?.id;
  const showRoundCompleteOverlay =
    isComplete &&
    (result || isQueuedSpectator) &&
    !isHoldingRoundCompleteOverlay &&
    !(viewerReady && nextRoundDecision?.expired);
  const finalPayout = typeof result?.payout === "number" ? result.payout : 0;
  const roundPotAmount = typeof round?.potAmount === "number" ? round.potAmount : 0;
  const winnerDisplayName =
    tableSeats?.find((seat) => seat.id === result?.winnerId)?.name ||
    result?.winnerName ||
    "Winner";
  const displayPotWinAmount = roundPotAmount > 0 ? roundPotAmount : finalPayout;
  const roundWinSummary = result
    ? displayPotWinAmount > 0
      ? `${winnerDisplayName} won ₹${displayPotWinAmount.toLocaleString("en-IN")} with ${result.winningHand}.`
      : `${winnerDisplayName} won with ${result.winningHand}.`
    : "";
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
  const roundStatusProgress = isStarting
    ? Math.min(1, Math.max(0, (roundStartClock?.progress ?? 0) / 100))
    : dealingProgress;
  const sharedJokers = variantState?.sharedJokers || [];
  const cardsPerSeat = Math.max(3, Number(variant?.cardsPerSeat) || 3);
  const showSharedJokersTray = sharedJokers.length > 0 && variant?.publicCardMode !== "third_card_rank_joker";

  const processedChipKeysRef = useRef(new Set());
  const tableSeatsRef = useRef(tableSeats);
  useEffect(() => {
    tableSeatsRef.current = tableSeats;
  }, [tableSeats]);

  useEffect(() => {
    processedChipKeysRef.current.clear();
  }, [round?.id]);

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
    }
  }, []);

  const findSeatAnchorNode = useCallback((fromSeatId) => {
    if (!fromSeatId) {
      return null;
    }
    const map = seatAnchorRef.current;
    if (map.has(fromSeatId)) {
      const node = map.get(fromSeatId);
      if (node && typeof document !== "undefined" && document.body.contains(node)) {
        return node;
      }
    }
    const seats = tableSeatsRef.current || [];
    const seatIdStr = String(fromSeatId).toLowerCase();
    const match = seats.find((s) => {
      if (!s) return false;
      const sId = String(s.id || "").toLowerCase();
      const sPlayerId = String(s.playerId || "").toLowerCase();
      const sIndex = String(s.seatIndex);
      return (
        sId === seatIdStr ||
        sPlayerId === seatIdStr ||
        sIndex === seatIdStr ||
        (sId && (sId.includes(seatIdStr) || seatIdStr.includes(sId))) ||
        (sPlayerId && (sPlayerId.includes(seatIdStr) || seatIdStr.includes(sPlayerId)))
      );
    });

    if (match) {
      const candidates = [
        match.id,
        match.playerId,
        `index-${match.seatIndex}`,
        match.seatIndex,
      ];
      for (const cand of candidates) {
        if (cand !== undefined && cand !== null && map.has(cand)) {
          const node = map.get(cand);
          if (node && typeof document !== "undefined" && document.body.contains(node)) {
            return node;
          }
        }
      }
    }

    if (typeof document !== "undefined" && surfaceRef.current) {
      const domNode =
        surfaceRef.current.querySelector(`[data-seat-id="${fromSeatId}"]`) ||
        (match ? surfaceRef.current.querySelector(`.table-seat--index-${match.seatIndex}`) : null);
      if (domNode) {
        return domNode;
      }
    }

    if (typeof document !== "undefined") {
      const domNode =
        document.querySelector(`[data-seat-id="${fromSeatId}"]`) ||
        (match ? document.querySelector(`.table-seat--index-${match.seatIndex}`) : null);
      if (domNode) {
        return domNode;
      }
    }

    return null;
  }, []);

  const launchChipTransferToPot = useCallback((fromSeatId, amount, dedupeKey) => {
    if (!fromSeatId) {
      return undefined;
    }

    if (dedupeKey) {
      if (processedChipKeysRef.current.has(dedupeKey)) {
        return undefined;
      }
      processedChipKeysRef.current.add(dedupeKey);
    }

    let cancelled = false;
    let retryTimer = null;
    let clearTimer = null;

    const startFlight = (attempt = 0) => {
      if (cancelled) {
        return;
      }

      const surface = surfaceRef.current;
      const potNode = potRef.current;
      const fromNode = findSeatAnchorNode(fromSeatId);

      if (!surface || !potNode || !fromNode) {
        if (attempt < 30) {
          retryTimer = window.setTimeout(() => startFlight(attempt + 1), 50);
        }
        return;
      }

      const surfaceRect = surface.getBoundingClientRect();
      const fromCenter = getRelativeCenter(fromNode.getBoundingClientRect(), surfaceRect);
      const potCenter = getRelativeCenter(potNode.getBoundingClientRect(), surfaceRect);
      const flights = buildChipFlightsBetweenPoints(
        fromCenter,
        potCenter,
        dedupeKey || `chip-${Date.now()}`,
        amount,
        "-",
      );

      setChipTransferFlights(flights);
      clearTimer = window.setTimeout(() => {
        setChipTransferFlights([]);
      }, CHIP_TRANSFER_DURATION_MS + CHIP_TRANSFER_COUNT * 100 + 240);
    };

    startFlight();

    return () => {
      cancelled = true;
      if (retryTimer) {
        window.clearTimeout(retryTimer);
      }
      if (clearTimer) {
        window.clearTimeout(clearTimer);
      }
    };
  }, [findSeatAnchorNode]);

  useEffect(() => () => {
    clearDealTimers();
  }, [clearDealTimers]);

  useEffect(() => {
    if (round?.status !== "active") {
      setSeatActionNotice(null);
      lastNotifiedActionRef.current = "";
      return undefined;
    }

    const actionLog = Array.isArray(round?.actionLog) ? round.actionLog : [];
    const lastAction =
      round?.lastAction ||
      (actionLog.length > 0 ? actionLog[actionLog.length - 1] : null);
    const actionType = String(
      lastAction?.actionType || lastAction?.type || ""
    ).toLowerCase();
    if (!lastAction?.playerId || actionType === "boot") {
      return undefined;
    }

    const actionSeq =
      lastAction.id ||
      lastAction.timestamp ||
      (actionLog.length ? `seq-${actionLog.length}` : "") ||
      Date.now();
    const actionKey = `${round.id}:${actionSeq}:${lastAction.playerId}:${actionType}`;
    if (lastNotifiedActionRef.current === actionKey) {
      return undefined;
    }

    const shortLabelByType = {
      see: "Seen",
      blind: "Blind",
      chaal: "Chaal",
      raise: "Raised",
      pack: "Packed",
      timeout: "Timed out",
      "sideshow-requested": "Side show",
      "sideshow-denied": "Denied",
      "sideshow-accepted": "Accepted",
      "sideshow-loss": "Lost side",
      show: "Show",
      "dealer_tip": "Tipped!",
    };

    let label = shortLabelByType[actionType];
    if (!label) {
      return undefined;
    }

    lastNotifiedActionRef.current = actionKey;
    setSeatActionNotice({
      seatId: lastAction.playerId,
      text: label,
      key: actionKey,
    });

    const timer = window.setTimeout(() => {
      setSeatActionNotice((current) => (current?.key === actionKey ? null : current));
    }, 2400);

    return () => {
      window.clearTimeout(timer);
    };
  }, [round?.id, round?.lastAction, round?.actionLog, round?.status, viewerSeat?.id]);

  useEffect(() => {
    if (round?.status !== "active") {
      return undefined;
    }

    const actionLog = Array.isArray(round?.actionLog) ? round.actionLog : [];
    const lastAction =
      round?.lastAction ||
      (actionLog.length > 0 ? actionLog[actionLog.length - 1] : null);

    if (!lastAction?.playerId) {
      return undefined;
    }

    const actionType = String(
      lastAction.actionType || lastAction.type || ""
    ).toLowerCase();
    if (!["blind", "chaal", "raise", "dealer_tip"].includes(actionType)) {
      return undefined;
    }

    const amount = typeof lastAction.amount === "number" ? lastAction.amount : 0;
    const actionSeq =
      lastAction.id ||
      lastAction.timestamp ||
      (actionLog.length ? `seq-${actionLog.length}` : "") ||
      Date.now();
    const actionKey = `${round.id}:${actionSeq}:${lastAction.playerId}:${actionType}:${amount}:chip-transfer`;

    return launchChipTransferToPot(lastAction.playerId, amount, actionKey);
  }, [
    launchChipTransferToPot,
    round?.id,
    round?.lastAction,
    round?.actionLog,
    round?.status,
  ]);

  useEffect(() => {
    if (!isComplete) {
      setPotPayoutFlights([]);
      // NOTE: Do NOT clear chipTransferFlights here — it is managed by
      // launchChipTransferToPot's own timeout. Clearing here was cancelling
      // every chip-flight animation because potAmount changes on every action.
      setPotCollected(false);
      setPotTransferring(false);
      setCelebrateWin(false);
      potPayoutRoundIdRef.current = "";
      celebrationRoundIdRef.current = "";
      return undefined;
    }

    if (!result?.winnerId || !round?.id) {
      return undefined;
    }

    if (potPayoutRoundIdRef.current === round.id) {
      return undefined;
    }

    let cancelled = false;
    let retryTimer = null;
    let clearTimer = null;
    let fadeTimer = null;

    const startPayoutFlight = (attempt = 0) => {
      if (cancelled) {
        return;
      }

      const surface = surfaceRef.current;
      const potNode = potRef.current;
      const winnerNode = seatAnchorRef.current.get(result.winnerId);

      if (!surface || !potNode || !winnerNode) {
        if (attempt < 40) {
          retryTimer = window.setTimeout(() => startPayoutFlight(attempt + 1), 100);
        }
        return;
      }

      potPayoutRoundIdRef.current = round.id;
      const surfaceRect = surface.getBoundingClientRect();
      const potCenter = getRelativeCenter(potNode.getBoundingClientRect(), surfaceRect);
      const winnerCenter = getRelativeCenter(winnerNode.getBoundingClientRect(), surfaceRect);
      const potAmount = typeof round.potAmount === "number"
        ? round.potAmount
        : (typeof result?.payout === "number" ? result.payout : 0);

      const flights = Array.from({ length: POT_CHIP_COUNT }, (_, index) => {
        const spread = (index - (POT_CHIP_COUNT - 1) / 2) * 14;
        const deltaX = winnerCenter.x - potCenter.x + spread;
        const deltaY = winnerCenter.y - potCenter.y + (index % 2 === 0 ? -8 : 8);
        return {
          id: `pot-chip-${round.id}-${index}`,
          startX: potCenter.x,
          startY: potCenter.y,
          deltaX,
          deltaY,
          midX: deltaX * 0.45,
          midY: deltaY * 0.35 - 56,
          delayMs: index * 140,
          durationMs: POT_PAYOUT_DURATION_MS,
          showAmount: index === Math.floor(POT_CHIP_COUNT / 2),
          amount: potAmount,
        };
      });

      setPotPayoutFlights(flights);
      setChipTransferFlights([]);   // clear any lingering chip-transfer flights
      setPotTransferring(true);
      setPotCollected(false);

      fadeTimer = window.setTimeout(() => {
        if (!cancelled) {
          setPotCollected(true);
        }
      }, Math.floor(POT_PAYOUT_DURATION_MS * 0.55));

      clearTimer = window.setTimeout(() => {
        if (cancelled) {
          return;
        }
        setPotPayoutFlights([]);
        setPotTransferring(false);
      }, POT_PAYOUT_DURATION_MS + POT_CHIP_COUNT * 140 + 320);
    };

    startPayoutFlight();

    return () => {
      cancelled = true;
      if (retryTimer) {
        window.clearTimeout(retryTimer);
      }
      if (clearTimer) {
        window.clearTimeout(clearTimer);
      }
      if (fadeTimer) {
        window.clearTimeout(fadeTimer);
      }
    };
  }, [isComplete, result?.winnerId, result?.payout, round?.id, round?.potAmount]);

  useEffect(() => {
    if (!isComplete || !isWinningViewer || !round?.id) {
      return undefined;
    }

    if (celebrationRoundIdRef.current === round.id) {
      return undefined;
    }

    let cancelled = false;
    const startTimer = window.setTimeout(() => {
      if (cancelled) {
        return;
      }
      celebrationRoundIdRef.current = round.id;
      setCelebrateWin(true);
    }, 280);
    const endTimer = window.setTimeout(() => {
      if (cancelled) {
        return;
      }
      setCelebrateWin(false);
    }, 280 + CELEBRATION_DURATION_MS);

    return () => {
      cancelled = true;
      window.clearTimeout(startTimer);
      window.clearTimeout(endTimer);
    };
  }, [isComplete, isWinningViewer, round?.id]);

  useEffect(() => {
    if (!isComplete || !round?.id) {
      return undefined;
    }

    if (revealedCompleteRoundId === round.id) {
      return undefined;
    }

    const ROUND_COMPLETE_OVERLAY_DELAY_MS = 5000;
    const decisionDeadlineMs = round?.nextRoundDecisionExpiresAt
      ? Math.max(0, new Date(round.nextRoundDecisionExpiresAt).getTime() - Date.now())
      : ROUND_COMPLETE_OVERLAY_DELAY_MS;
    // Show the next-round popup after 5s, but never later than the decision window.
    const delayMs = Math.min(ROUND_COMPLETE_OVERLAY_DELAY_MS, Math.max(decisionDeadlineMs - 400, 0));
    const timer = window.setTimeout(() => {
      setRevealedCompleteRoundId(round.id);
    }, delayMs);

    return () => {
      window.clearTimeout(timer);
    };
  }, [isComplete, revealedCompleteRoundId, round?.id, round?.nextRoundDecisionExpiresAt]);

  useEffect(() => {
    clearDealTimers();
    activeDealAnimationKeyRef.current = "";
    setDealFlightCards([]);
    setDealtCounts({});
  }, [clearDealTimers, round?.id]);

  useEffect(() => {
    if (isDealing) {
      return undefined;
    }

    clearDealTimers();
    activeDealAnimationKeyRef.current = "";
    setDealFlightCards([]);
    setDealtCounts({});
    return undefined;
  }, [clearDealTimers, isDealing]);

  useEffect(() => {
    if (!isDealing || !round?.id || !round?.dealingStartedAt || !round?.dealingEndsAt) {
      return undefined;
    }

    if (round.startCountdownEndsAt) {
      const countdownEndsMs = new Date(round.startCountdownEndsAt).getTime();
      if (nowMs < countdownEndsMs) {
        return undefined;
      }
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
    round?.startCountdownEndsAt,
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

  async function handleNextRoundAccept() {
    await onStartNextRound();
  }

  async function handleNextRoundDecline() {
    await onDeclineNextRound();
  }

  async function handleSendTip() {
    if (midGameTipSending) {
      return;
    }

    setMidGameTipSending(true);
    try {
      // The server now logs dealer_tip into lastAction/actionLog.
      // The chip transfer animation is triggered for ALL players (including
      // the tipper) via the chip-transfer useEffect watching round.lastAction.
      await onAction("dealer_tip", { amount: midGameTipAmount });
    } catch (error) {
      console.error(error);
    } finally {
      setMidGameTipSending(false);
    }
  }

  return (
    <section className="casino-table-scene relative h-dvh overflow-hidden bg-black text-white">
      <div className="pointer-events-none absolute inset-0" aria-hidden="true">
        <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.06),rgba(0,6,8,0.18)_68%,rgba(0,0,0,0.62))]" />
      </div>

      {celebrateWin ? (
        <TableCelebration title="You Win!" tone="win" />
      ) : null}

      <div className="casino-table-scene__page relative z-[20] mx-auto h-dvh w-full max-w-none overflow-x-hidden overflow-y-auto overscroll-y-contain">
        <div className="casino-table-scene__column flex min-h-full w-full flex-col">
          <div className="casino-table-scene__hud sticky top-0 z-[40] -mx-3 px-3 pb-1 pt-[max(4px,env(safe-area-inset-top))] sm:mx-0 sm:px-0 sm:pb-3 sm:pt-[18px]">
            <div className="relative flex items-center justify-between gap-2">
              <div className="flex min-w-0 items-center gap-1.5 sm:gap-2">
                <div className="relative shrink-0" onPointerDown={(event) => event.stopPropagation()}>
                  <TopHudButton
                    label="Open settings"
                    onClick={() => setSettingsOpen((current) => !current)}
                    active={settingsOpen}
                  >
                    <Image
                      src="/newAssets/settingsButton.png"
                      alt=""
                      width={22}
                      height={22}
                      aria-hidden="true"
                      className="sm:h-[26px] sm:w-[26px]"
                    />
                  </TopHudButton>
                  <SettingsPanel
                    open={settingsOpen}
                    onExitTable={onExitTable}
                  />
                </div>

                <InfoBadge label="Variant" value={variant?.label || "Teen Patti"} tone="red" />
              </div>

              <div
                className="pointer-events-auto absolute left-1/2 top-1/2 z-[2] -translate-x-1/2 -translate-y-1/2"
                onPointerDown={(event) => event.stopPropagation()}
              >
                <div className="flex items-center gap-0.5 rounded-full border border-[#ffe888]/60 bg-[linear-gradient(135deg,rgba(40,25,5,0.94)_0%,rgba(15,10,2,0.98)_100%)] p-0.5 text-white shadow-[0_4px_14px_rgba(0,0,0,0.5),0_0_12px_rgba(255,232,136,0.3)] sm:gap-1 sm:p-1">
                  <button
                    type="button"
                    onClick={() => setMidGameTipAmount((prev) => Math.max(10, prev - 10))}
                    disabled={midGameTipAmount <= 10 || midGameTipSending}
                    className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full border border-white/12 bg-white/8 text-sm font-black text-white transition-transform active:scale-90 disabled:opacity-40 sm:h-8 sm:w-8 sm:text-base"
                    aria-label="Decrease tip amount"
                  >
                    −
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      void handleSendTip();
                    }}
                    disabled={midGameTipSending}
                    className="group flex min-w-[7.5rem] items-center justify-center gap-1.5 rounded-full px-2 py-1 transition-all duration-200 hover:bg-white/6 active:scale-95 disabled:opacity-70 sm:min-w-[8.5rem] sm:px-2.5 sm:py-1.5"
                    title="Tip Dealer"
                  >
                    <div className="flex h-5 w-5 items-center justify-center rounded-full border border-[#ffe888]/80 bg-[linear-gradient(180deg,#fff2a8_0%,#d0a22e_100%)] text-[10px] shadow-sm sm:h-6 sm:w-6 sm:text-[11px]">
                      🪙
                    </div>
                    <div className="flex flex-col text-left">
                      <span className="text-[7.5px] font-black uppercase tracking-[0.14em] text-[#ffe888] sm:text-[8px]">
                        {midGameTipSending ? "Sending..." : "Tip Dealer"}
                      </span>
                      <span className="text-[9px] font-extrabold text-[#fff7d6] sm:text-[10px]">
                        ₹{midGameTipAmount}
                      </span>
                    </div>
                  </button>

                  <button
                    type="button"
                    onClick={() => setMidGameTipAmount((prev) => prev + 10)}
                    disabled={midGameTipSending}
                    className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full border border-[#ffe888]/40 bg-[linear-gradient(180deg,#fff2a8_0%,#d0a22e_100%)] text-sm font-black text-[#4a2e00] shadow-sm transition-transform active:scale-90 disabled:opacity-40 sm:h-8 sm:w-8 sm:text-base"
                    aria-label="Increase tip amount"
                  >
                    +
                  </button>
                </div>
              </div>

              <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
                {isPrivateMode && (roomCode || roomName) ? (
                  <InfoBadge
                    label={roomCode ? "Room" : "Private"}
                    value={roomCode || roomName}
                    align="right"
                  />
                ) : null}
                <ChipBalanceDisplay chipBalance={chipBalance} />
              </div>
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
                src="/newAssets/landscapeProd.png"
                alt=""
                fill
                priority
                aria-hidden="true"
                className="casino-table-scene__felt casino-table-scene__felt--landscape object-fill drop-shadow-[0_30px_50px_rgba(0,0,0,0.46)]"
              />

              {showSharedJokersTray ? <SharedJokersTray sharedJokers={sharedJokers} /> : null}

              {isStarting ? (
                <div className="casino-table-scene__deck-layer pointer-events-none absolute inset-0 z-[25]">
                  <div className="casino-table-scene__deck is-shuffling">
                    <div ref={deckAnchorRef} className="casino-table-scene__deck-anchor">
                      <HiddenDealCard className="casino-table-scene__deck-card casino-table-scene__deck-card--base" />
                      <HiddenDealCard className="casino-table-scene__deck-card casino-table-scene__deck-card--mid" />
                      <HiddenDealCard className="casino-table-scene__deck-card casino-table-scene__deck-card--top" />
                    </div>
                  </div>
                </div>
              ) : null}

              {isDealing ? (
                <div className="casino-table-scene__deck-layer pointer-events-none absolute inset-0 z-[25]">
                  <div className="casino-table-scene__deck is-dealing">
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
                  actionNotice={
                    seatActionNotice?.seatId === seat.id ? seatActionNotice.text : null
                  }
                />
              ))}

              <div
                ref={potRef}
                className={`casino-table-scene__pot pointer-events-none absolute left-1/2 top-[48.6%] z-[29] -translate-x-1/2 -translate-y-1/2 text-center transition-opacity duration-[1200ms] ease-out ${
                  potCollected ? "opacity-0" : potTransferring ? "opacity-35" : "opacity-100"
                }`}
              >
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

              {(potPayoutFlights.length > 0 || chipTransferFlights.length > 0) ? (
                <div className="casino-table-scene__pot-flight-layer pointer-events-none absolute inset-0 z-[35]">
                  {[...chipTransferFlights, ...potPayoutFlights].map((flight) => (
                    <div
                      key={flight.id}
                      className="casino-table-scene__pot-flight"
                      style={{
                        left: `${flight.startX}px`,
                        top: `${flight.startY}px`,
                        animationDelay: `${flight.delayMs}ms`,
                        animationDuration: `${flight.durationMs}ms`,
                        "--pot-x": `${flight.deltaX}px`,
                        "--pot-y": `${flight.deltaY}px`,
                        "--pot-mid-x": `${flight.midX}px`,
                        "--pot-mid-y": `${flight.midY}px`,
                      }}
                    >
                      <Image
                        src="/newAssets/chip.png"
                        alt=""
                        width={34}
                        height={30}
                        aria-hidden="true"
                        className="object-contain drop-shadow-[0_10px_16px_rgba(0,0,0,0.35)]"
                        style={{ width: flight.amountPrefix === "-" ? 28 : 34, height: "auto" }}
                      />
                      {flight.showAmount && flight.amount > 0 ? (
                        <strong className="casino-table-scene__pot-flight-amount">
                          {flight.amountPrefix || "+"}{flight.amount.toLocaleString("en-IN")}
                        </strong>
                      ) : null}
                    </div>
                  ))}
                </div>
              ) : null}

              {showRoundCompleteOverlay ? (
                <div className="casino-table-scene__complete-shell pointer-events-none fixed inset-x-0 bottom-0 z-[34] flex justify-center px-4 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:pb-5">
                  <div className="casino-table-scene__complete-card pointer-events-auto flex w-full max-w-[22rem] flex-col items-center gap-3 rounded-[22px] border border-[#ffffff18] bg-[linear-gradient(180deg,rgba(7,44,48,0.48),rgba(3,16,19,0.58))] px-5 py-4 text-center shadow-[0_22px_40px_rgba(0,0,0,0.22)] backdrop-blur-[10px]">
                    {potLimitReached && !isQueuedSpectator ? (
                      <div className="rounded-full border border-[#ffd08a]/35 bg-[rgba(88,52,18,0.88)] px-3 py-1 text-[11px] font-black uppercase tracking-[0.14em] text-[#ffe8bf]">
                        Pot Limit Reached This Round
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
                                          ? "Everyone is ready. Start the next round when you want."
                                          : "Wait for another player to join or get ready before starting the next round.")
                                      : "You are ready for the host to start the next round.")
                                  : "You are in for the next round.")
                              : roundWinSummary
                            : viewerReady
                              ? (isPrivateMode
                                  ? (isHost
                                      ? (canStartNextRound
                                          ? "Everyone is ready. Start the next round when you want."
                                          : "Wait for another player to join or get ready before starting the next round.")
                                      : "You are ready. Waiting for the host to start the next round.")
                                  : "You are in for the next round.")
                              : roundWinSummary}
                      </span>
                    </div>

                    {!isQueuedSpectator && isWinningViewer && finalPayout > 0 ? (
                      <div className="rounded-full border border-[#3be7de]/24 bg-black/22 px-3 py-1 text-[11px] font-bold uppercase tracking-[0.14em] text-[#a6fff2]">
                        {finalPayout !== displayPotWinAmount
                          ? `You receive ₹${finalPayout.toLocaleString("en-IN")} after commission`
                          : `Final payout ₹${finalPayout.toLocaleString("en-IN")}`}
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
