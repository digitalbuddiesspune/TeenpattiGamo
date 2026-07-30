"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import CasinoTable from "./CasinoTable";
import GameplaySoundController from "./GameplaySoundController";
import HomeIntroSound from "./HomeIntroSound";
import TableControls, { buildStakeControlState } from "./TableControls";
import { clearStoredPublicSession, useTeenPattiGame } from "../hooks/useTeenPattiGame";
import { fetchPlatformProfile } from "../lib/api";
import {
  getPlatformLaunchContext,
  PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY,
  PLATFORM_PROFILE_STORAGE_KEY,
  withLaunchQuery,
} from "../lib/platformLaunch";
import { DEFAULT_VARIANT_ID, VARIANT_OPTIONS } from "../lib/variants";

const menuActions = {
  public: "Opening Teen Patti game selection",
  history: "Opening round history",
  lucky: "Opening Lucky Cards",
  mini: "Opening mini games",
  trophy: "Opening rewards",
  settings: "Opening settings",
  buy: "Opening chip store",
};

const emptyTurnClock = {
  millisecondsRemaining: 0,
  secondsRemaining: 0,
  progress: 0,
  isCritical: false,
};

const emptyRoundStartClock = {
  millisecondsRemaining: 0,
  secondsRemaining: 0,
  progress: 0,
};

const emptyNextRoundDecision = {
  millisecondsRemaining: 0,
  secondsRemaining: 0,
  expired: false,
};

function getTurnClock(round, nowMs) {
  if (!round || round.status !== "active" || !round.turnDeadlineAt) {
    return emptyTurnClock;
  }

  const deadlineMs = new Date(round.turnDeadlineAt).getTime();
  const startedMs = round.turnStartedAt
    ? new Date(round.turnStartedAt).getTime()
    : deadlineMs - (round.turnDurationMs || 0);
  const totalDuration = Math.max(round.turnDurationMs || deadlineMs - startedMs || 0, 1);
  const millisecondsRemaining = Math.max(0, deadlineMs - nowMs);
  const elapsed = Math.max(0, nowMs - startedMs);

  return {
    millisecondsRemaining,
    secondsRemaining: Math.ceil(millisecondsRemaining / 1000),
    progress: Math.min(100, Math.max(0, (elapsed / totalDuration) * 100)),
    isCritical: millisecondsRemaining > 0 && millisecondsRemaining <= 5000,
  };
}

function getRoundStartClock(round, nowMs) {
  if (!round || round.status !== "starting" || !round.startCountdownEndsAt) {
    return emptyRoundStartClock;
  }

  const deadlineMs = new Date(round.startCountdownEndsAt).getTime();
  const startedMs = round.startCountdownStartedAt
    ? new Date(round.startCountdownStartedAt).getTime()
    : deadlineMs - 5000;
  const totalDuration = Math.max(deadlineMs - startedMs, 1);
  const millisecondsRemaining = Math.max(0, deadlineMs - nowMs);
  const elapsed = Math.max(0, nowMs - startedMs);

  return {
    millisecondsRemaining,
    secondsRemaining: Math.ceil(millisecondsRemaining / 1000),
    progress: Math.min(100, Math.max(0, (elapsed / totalDuration) * 100)),
  };
}

function getNextRoundDecision(round, nowMs) {
  if (!round || round.status !== "complete" || !round.nextRoundDecisionExpiresAt) {
    return emptyNextRoundDecision;
  }

  const deadlineMs = new Date(round.nextRoundDecisionExpiresAt).getTime();
  const millisecondsRemaining = Math.max(0, deadlineMs - nowMs);

  return {
    millisecondsRemaining,
    secondsRemaining: Math.ceil(millisecondsRemaining / 1000),
    expired: millisecondsRemaining === 0,
  };
}

function rotateSeatsForViewer(seats = []) {
  if (!seats.length) {
    return [];
  }

  const viewerIndex = seats.findIndex((seat) => seat.isUser);
  if (viewerIndex <= 0) {
    return seats;
  }

  return seats.map((_, index) => {
    const sourceSeat = seats[(viewerIndex + index) % seats.length];
    return {
      ...sourceSeat,
      seatIndex: index
    };
  });
}

function StatusBanner({ title, message, tone = "default", timerLabel = null }) {
  const toneClass = tone === "error"
    ? "border-[#ffb4b4]/24 bg-[linear-gradient(180deg,rgba(88,24,24,0.94),rgba(44,14,14,0.98))]"
    : "border-[#ffffff14] bg-[linear-gradient(180deg,rgba(8,52,57,0.94),rgba(4,19,22,0.98))]";

  return (
    <div className={`mx-auto w-full rounded-[18px] border px-4 py-3 text-white shadow-[0_20px_42px_rgba(0,0,0,0.34)] backdrop-blur-md ${toneClass}`}>
      <div className="flex items-start justify-between gap-3">
        <strong className={`block text-xs font-black uppercase tracking-[0.16em] sm:text-sm ${
          tone === "error" ? "text-[#ffe0d6]" : "text-[#abfff5]"
        }`}>
          {title}
        </strong>
        {timerLabel ? (
          <span
            className="shrink-0 font-mono text-sm font-black tracking-[0.06em] text-[#ffe6a0] tabular-nums"
            aria-live="polite"
            aria-atomic="true"
          >
            {timerLabel}
          </span>
        ) : null}
      </div>
      <span className={`mt-1.5 block text-xs sm:text-sm ${
        tone === "error" ? "text-white/78" : "text-white/72"
      }`}>
        {message}
      </span>
    </div>
  );
}

function PublicTableJoiningDesktopBanner({ message }) {
  const elapsedSeconds = useElapsedMatchmakingSeconds();
  const timerLabel = formatMatchmakingTimer(elapsedSeconds);

  return (
    <StatusBanner
      title="Joining table"
      message={`${message} Search time ${timerLabel}.`}
      timerLabel={timerLabel}
    />
  );
}

function GameplayBackdrop() {
  return (
    <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.06),rgba(0,6,8,0.18)_68%,rgba(0,0,0,0.62))] md:hidden" />
  );
}

function DesktopMenuBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0 hidden md:block" aria-hidden="true">
      <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.06),rgba(0,6,8,0.18)_68%,rgba(0,0,0,0.62))]" />
    </div>
  );
}

function TableGameplayShellBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0" aria-hidden="true">
      <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.06),rgba(0,6,8,0.18)_68%,rgba(0,0,0,0.62))]" />
    </div>
  );
}

function TableGameplayBackdrop({ className = "" }) {
  return (
    <div
      className={`pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_50%_18%,rgba(59,231,222,0.1),transparent_46%),linear-gradient(180deg,rgba(0,15,16,0.2),rgba(0,6,8,0.6)_62%,rgba(0,0,0,0.9))] ${className}`}
      aria-hidden="true"
    />
  );
}

function formatMatchmakingTimer(totalSeconds) {
  const safeSeconds = Math.max(0, Math.floor(totalSeconds));
  const minutes = Math.floor(safeSeconds / 60);
  const seconds = safeSeconds % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function useElapsedMatchmakingSeconds() {
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  useEffect(() => {
    const startedAt = Date.now();
    setElapsedSeconds(0);
    const timer = window.setInterval(() => {
      setElapsedSeconds(Math.floor((Date.now() - startedAt) / 1000));
    }, 250);
    return () => window.clearInterval(timer);
  }, []);

  return elapsedSeconds;
}

function PublicTableJoiningMobileScreen({ message }) {
  const elapsedSeconds = useElapsedMatchmakingSeconds();
  const timerLabel = formatMatchmakingTimer(elapsedSeconds);
  const progressPercent = Math.min(92, 18 + elapsedSeconds * 8);

  return (
    <section className="menu-screen flex min-h-screen overflow-hidden bg-[linear-gradient(180deg,#041213,#010607_76%)]">
      <DesktopMenuBackdrop />
      <div className="app-frame app-frame-surface app-frame-clip relative h-dvh">
        <TableGameplayBackdrop />
        <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.14),rgba(0,6,8,0.24)_68%,rgba(0,0,0,0.84))]" aria-hidden="true" />

        <div className="relative z-[1] flex h-dvh flex-col overflow-hidden">
          <header className="relative h-[138px] w-full pt-[max(16px,env(safe-area-inset-top))]">
            <Image
              src="/newAssets/homeTopBg.png"
              alt=""
              fill
              priority
              sizes="(max-width: 640px) 100vw, 500px"
              className="object-cover object-top"
              aria-hidden="true"
            />
          </header>

          <div className="relative flex flex-1 items-center justify-center px-[14px] pb-[max(20px,env(safe-area-inset-bottom))] pt-2">
            <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_50%_42%,rgba(59,231,222,0.08),transparent_24%),radial-gradient(circle_at_50%_74%,rgba(239,194,78,0.08),transparent_22%)]" aria-hidden="true" />

            <div className="relative w-full overflow-hidden rounded-[28px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(7,44,48,0.9),rgba(3,16,19,0.96))] shadow-[0_24px_44px_rgba(0,0,0,0.36)] backdrop-blur-[10px]">
              <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(255,255,255,0.03),transparent_36%)]" aria-hidden="true" />
              <div className="absolute left-0 top-0 h-full w-px bg-[linear-gradient(180deg,transparent,rgba(67,216,204,0.28),transparent)]" aria-hidden="true" />
              <div className="absolute right-0 top-0 h-full w-px bg-[linear-gradient(180deg,transparent,rgba(239,194,78,0.18),transparent)]" aria-hidden="true" />

              <div className="relative z-[1] px-5 pb-5 pt-5">
                <div className="flex items-center justify-between gap-3">
                  <div className="inline-flex items-center gap-2 rounded-full border border-[#43d8cc]/22 bg-[#081c1f]/72 px-2.5 py-1 text-[9px] font-black uppercase tracking-[0.18em] text-[#abfff5]">
                    <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-[#43d8cc]" />
                    Public Table
                  </div>
                  <div className="text-[10px] font-bold uppercase tracking-[0.16em] text-white/42">
                    Live Matchmaking
                  </div>
                </div>

                <div className="mt-5 flex items-end justify-between gap-4">
                  <div>
                    <h1 className="text-[1.9rem] font-black leading-[0.98] tracking-[-0.02em] text-white">
                      Joining the
                      <br />
                      table
                    </h1>
                    <p className="mt-3 max-w-[16rem] text-[0.82rem] leading-[1.5] text-white/66">
                      {message}
                    </p>
                  </div>

                  <div
                    className="shrink-0 rounded-[20px] border border-[#43d8cc]/22 bg-[rgba(8,36,40,0.88)] px-3.5 py-3 text-right shadow-[0_12px_28px_rgba(0,0,0,0.28)]"
                    aria-live="polite"
                    aria-atomic="true"
                  >
                    <div className="text-[9px] font-black uppercase tracking-[0.18em] text-[#abfff5]/78">
                      Search time
                    </div>
                    <div className="mt-1 font-mono text-[1.85rem] font-black leading-none tracking-[0.04em] text-[#ffe6a0] tabular-nums">
                      {timerLabel}
                    </div>
                  </div>
                </div>

                <div className="mt-6 rounded-[22px] border border-white/8 bg-[rgba(3,18,21,0.52)] p-4">
                  <div className="flex items-center justify-between gap-3 text-[10px] font-black uppercase tracking-[0.14em] text-white/54">
                    <span>Preparing seat</span>
                    <span className="text-[#ffe6a0]">{timerLabel}</span>
                  </div>

                  <div className="mt-3 h-[3px] w-full overflow-hidden rounded-full bg-white/8">
                    <div
                      className="h-full rounded-full bg-[linear-gradient(90deg,#2ac7bf_0%,#78efe5_48%,#efc24e_100%)] shadow-[0_0_18px_rgba(67,216,204,0.3)] transition-[width] duration-300 ease-out"
                      style={{ width: `${progressPercent}%` }}
                    />
                  </div>

                  <div className="mt-4 grid gap-2">
                    <div className="flex items-center justify-between rounded-[16px] border border-white/7 bg-[rgba(255,255,255,0.03)] px-3 py-2.5">
                      <span className="text-[11px] font-semibold text-white/74">Seat allocation</span>
                      <span className="text-[10px] font-black uppercase tracking-[0.14em] text-[#abfff5]">In progress</span>
                    </div>
                    <div className="flex items-center justify-between rounded-[16px] border border-white/7 bg-[rgba(255,255,255,0.03)] px-3 py-2.5">
                      <span className="text-[11px] font-semibold text-white/74">Matchmaking timer</span>
                      <span className="font-mono text-[11px] font-black tracking-[0.08em] text-[#ffe6a0] tabular-nums">
                        {timerLabel}
                      </span>
                    </div>
                    <div className="flex items-center justify-between rounded-[16px] border border-white/7 bg-[rgba(255,255,255,0.03)] px-3 py-2.5">
                      <span className="text-[11px] font-semibold text-white/74">Balance sync</span>
                      <span className="text-[10px] font-black uppercase tracking-[0.14em] text-white/42">Queued</span>
                    </div>
                  </div>
                </div>

                <div className="mt-5 flex items-center gap-3 rounded-[18px] border border-[#ffd778]/12 bg-[rgba(63,44,12,0.22)] px-3 py-3">
                  <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-[#ffd778]/18 bg-[rgba(255,214,120,0.08)]">
                    <div className="h-2 w-2 animate-pulse rounded-full bg-[#efc24e] shadow-[0_0_14px_rgba(239,194,78,0.65)]" />
                  </div>
                  <p className="text-[11px] leading-[1.45] text-[#ffe6a0]/78">
                    Searching for {timerLabel}. The table will open automatically once a match is ready.
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function HomeHeaderIcon({ type }) {
  if (type === "bell") {
    return (
      <svg viewBox="0 0 24 24" aria-hidden="true" className="h-7 w-7">
        <path
          d="M12 3.2a4.2 4.2 0 0 0-4.2 4.2v1.3c0 .9-.3 1.8-.8 2.5l-1.5 2a2 2 0 0 0 1.6 3.2h9.8a2 2 0 0 0 1.6-3.2l-1.5-2a4.2 4.2 0 0 1-.8-2.5V7.4A4.2 4.2 0 0 0 12 3.2Z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M10.2 18.3a1.8 1.8 0 0 0 3.6 0"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
      </svg>
    );
  }

  return (
      <Image
        src="/newAssets/settingsButton.png"
        alt=""
        width={30}
        height={30}
        className="h-7 w-7"
        aria-hidden="true"
      />
    );
  }

function AceFan() {
  return (
    <span className="variant-card__fan" aria-hidden="true">
      <Image
        src="/newAssets/cards.png"
        alt=""
        width={132}
        height={124}
        sizes="132px"
        priority
      />
    </span>
  );
}

function VariantCard({ title, onClick }) {
  return (
    <button type="button" className="variant-card" onClick={onClick}>
      <AceFan />

      <span className="variant-card__body">
        <strong className="variant-card__title">{title}</strong>
      </span>

      <span className="variant-card__chip" aria-hidden="true">
        <span>♠</span>
      </span>
    </button>
  );
}

export default function GameClient({
  view,
  variant = DEFAULT_VARIANT_ID,
}) {
  const router = useRouter();
  const [turnNow, setTurnNow] = useState(() => Date.now());
  const [selectedStake, setSelectedStake] = useState(0);
  const [isMobileDevice] = useState(true);
  const [platformProfile, setPlatformProfile] = useState(() => {
    if (typeof window === "undefined") {
      return null;
    }
    try {
      const raw = window.sessionStorage.getItem(PLATFORM_PROFILE_STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  });
  const publicDecisionHandledRef = useRef(false);
  const publicDecisionPendingExitRef = useRef(false);
  const platformProfileRequestIdRef = useRef(0);
  const bootDeductionProfileRoundKeyRef = useRef("");
  const gameplaySoundApiRef = useRef({
    playDealCard() {},
  });

  const isPublicMenuView = view === "public-menu";
  const isPublicTableView = view === "public-table";
  const activeMenuItem = "public";
  const game = useTeenPattiGame(variant, isPublicTableView);
  const {
    tableState: publicTableState,
    loading: publicLoading,
    acting: publicActing,
    error: publicError,
    runAction: publicRunAction,
    readyNextRound: publicReadyNextRound,
    leaveTable: leavePublicTable,
  } = game;
  const screen = isPublicTableView ? "table" : "menu";
  const isPublicWaiting = publicTableState?.playerStatus === "waiting_for_next_round";
  const waitingForSeat = isPublicWaiting;

  const round = publicTableState?.round;
  const isPublicLobbyWaiting =
    publicTableState?.playerStatus === "active_at_table" && !round;
  const canonicalSeats = round?.seats || [];
  const displaySeats = rotateSeatsForViewer(canonicalSeats);
  const tableRound = round;
  const userSeat = displaySeats.find((seat) => seat.isUser);
  const viewerHasDealerTipPrompt = Boolean(round?.dealerTipPending && round?.dealerTipPrompt);
  const turnClock = getTurnClock(round, turnNow);
  const roundStartClock = getRoundStartClock(round, turnNow);
  const nextRoundDecision = getNextRoundDecision(round, turnNow);
  const activeTurnDeadline =
    round?.status === "active" ? round?.turnDeadlineAt || null : null;
  const activeStartCountdown =
    round?.status === "starting" ? round?.startCountdownEndsAt || null : null;
  const activeDealingWindow =
    round?.status === "dealing" ? round?.dealingEndsAt || null : null;
  const activeDecisionDeadline =
    round?.status === "complete"
      ? round?.nextRoundDecisionExpiresAt || null
      : null;
  const activePublicLobbyDeadline = isPublicLobbyWaiting
    ? publicTableState?.publicJoinWaitEndsAt || null
    : null;
  const publicLobbySecondsRemaining = activePublicLobbyDeadline
    ? Math.max(0, Math.ceil((new Date(activePublicLobbyDeadline).getTime() - turnNow) / 1000))
    : 0;
  const nextRoundState = publicTableState?.nextRound || (publicTableState ? {
      viewerAccepted: Boolean(publicTableState.nextRoundReady || publicTableState.viewerNextRoundReady),
      waitingForAcceptances: round?.status === "complete",
      acceptedPlayerIds: [],
      pendingPlayerIds: [],
    } : null);
  const dealerTipPending = viewerHasDealerTipPrompt;
  const platformLaunchContext = getPlatformLaunchContext();
  const platformLaunchToken = platformLaunchContext?.token || "";
  const platformLaunchGameId = platformLaunchContext?.gameId || 0;

  const commitPlatformProfile = useCallback((profile) => {
    setPlatformProfile(profile);
    try {
      window.sessionStorage.setItem(PLATFORM_PROFILE_STORAGE_KEY, JSON.stringify(profile));
    } catch {}
  }, []);

  useEffect(() => {
    if (platformLaunchToken || platformLaunchGameId) {
      return undefined;
    }

    try {
      window.sessionStorage.removeItem(PLATFORM_PROFILE_STORAGE_KEY);
      window.sessionStorage.removeItem(PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY);
    } catch {}
    const timer = window.setTimeout(() => {
      setPlatformProfile(null);
    }, 0);
    return () => {
      window.clearTimeout(timer);
    };
  }, [platformLaunchGameId, platformLaunchToken]);

  const syncPlatformProfile = useCallback(async () => {
    if (!platformLaunchToken || !platformLaunchGameId) {
      return null;
    }

    const requestId = platformProfileRequestIdRef.current + 1;
    platformProfileRequestIdRef.current = requestId;
    const profile = await fetchPlatformProfile(platformLaunchToken, platformLaunchGameId);
    if (requestId !== platformProfileRequestIdRef.current) {
      return profile;
    }

    commitPlatformProfile(profile);
    return profile;
  }, [commitPlatformProfile, platformLaunchGameId, platformLaunchToken]);

  useEffect(() => {
    if (!platformLaunchToken || !platformLaunchGameId) {
      return undefined;
    }

    let cancelled = false;
    const requestId = platformProfileRequestIdRef.current + 1;
    platformProfileRequestIdRef.current = requestId;

    fetchPlatformProfile(platformLaunchToken, platformLaunchGameId)
      .then((profile) => {
        if (cancelled || requestId !== platformProfileRequestIdRef.current) {
          return;
        }

        commitPlatformProfile(profile);
      })
      .catch(() => {
        if (!cancelled && requestId === platformProfileRequestIdRef.current && isPublicMenuView) {
          setPlatformProfile(null);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [
    isPublicMenuView,
    platformLaunchGameId,
    platformLaunchToken,
    screen,
    variant,
    view,
    commitPlatformProfile,
  ]);

  const refreshPlatformProfileAfterAction = useCallback(async (action) => {
    const result = await action();
    if (platformLaunchToken && platformLaunchGameId) {
      try {
        await syncPlatformProfile();
      } catch {}
    }
    return result;
  }, [platformLaunchGameId, platformLaunchToken, syncPlatformProfile]);

  const handleGameplayAction = useCallback((actionType, payload) => (
    refreshPlatformProfileAfterAction(() => publicRunAction(actionType, payload))
  ), [publicRunAction, refreshPlatformProfileAfterAction]);

  useEffect(() => {
    if (!platformLaunchToken || !platformLaunchGameId || screen !== "table" || !round?.id) {
      return undefined;
    }

    if (!["starting", "dealing", "active"].includes(round.status)) {
      return undefined;
    }

    const roundKey = `public:${round.id}`;
    if (bootDeductionProfileRoundKeyRef.current === roundKey) {
      return undefined;
    }

    bootDeductionProfileRoundKeyRef.current = roundKey;

    const requestId = platformProfileRequestIdRef.current + 1;
    platformProfileRequestIdRef.current = requestId;

    fetchPlatformProfile(platformLaunchToken, platformLaunchGameId)
      .then((profile) => {
        if (requestId !== platformProfileRequestIdRef.current) {
          return;
        }

        commitPlatformProfile(profile);
      })
      .catch(() => {});

    return undefined;
  }, [
    commitPlatformProfile,
    platformLaunchGameId,
    platformLaunchToken,
    round?.id,
    round?.status,
    screen,
  ]);

  useEffect(() => {
    if (
      !activeTurnDeadline &&
      !activeStartCountdown &&
      !activeDealingWindow &&
      !activeDecisionDeadline &&
      !activePublicLobbyDeadline
    ) {
      return undefined;
    }

    const timer = window.setInterval(() => {
      setTurnNow(Date.now());
    }, 250);

    return () => {
      window.clearInterval(timer);
    };
  }, [
    activeTurnDeadline,
    activeStartCountdown,
    activeDealingWindow,
    activeDecisionDeadline,
    activePublicLobbyDeadline,
    round?.id,
  ]);

  function handleMenuAction(actionId) {
    if (actionId === "history") {
      router.push(withLaunchQuery("/transactions/history"));
    }
  }

  const handleExitTable = useCallback(async () => {
    await leavePublicTable();
    router.replace(withLaunchQuery("/public"));
  }, [leavePublicTable, router]);

  async function handleStartNextPublicRound() {
    await refreshPlatformProfileAfterAction(() => publicReadyNextRound());
  }

  useEffect(() => {
    if (screen !== "table") {
      publicDecisionHandledRef.current = false;
      publicDecisionPendingExitRef.current = false;
      return undefined;
    }

    if (nextRoundState?.viewerAccepted || dealerTipPending) {
      publicDecisionHandledRef.current = false;
      publicDecisionPendingExitRef.current = false;
      return undefined;
    }

    if (round?.status === "complete" && publicTableState?.playerStatus !== "waiting_for_next_round") {
      publicDecisionPendingExitRef.current = true;
    }

    const shouldExitForExpiredDecision =
      publicDecisionPendingExitRef.current &&
      (nextRoundDecision.expired || publicTableState?.playerStatus === "waiting_for_next_round");

    if (!shouldExitForExpiredDecision) {
      publicDecisionHandledRef.current = false;
      return undefined;
    }

    if (publicDecisionHandledRef.current) {
      return undefined;
    }

    publicDecisionHandledRef.current = true;
    const timer = window.setTimeout(() => {
      void handleExitTable();
    }, 0);

    return () => {
      window.clearTimeout(timer);
    };
  }, [
    handleExitTable,
    dealerTipPending,
    nextRoundDecision.expired,
    nextRoundState?.viewerAccepted,
    publicTableState?.playerStatus,
    round?.status,
    screen,
  ]);

  const activeLoading = publicLoading;
  const activeActing = publicActing;
  const activeError = publicError;
  const platformBalanceFallback = publicTableState?.viewerPlatformBalance;
  const visibleChipBalance = userSeat?.balance ?? publicTableState?.table?.balance;
  const displayedChipBalance = typeof platformProfile?.balance === "number"
    ? platformProfile.balance
    : typeof platformBalanceFallback === "number"
      ? platformBalanceFallback
    : visibleChipBalance;
  const headerChipBalanceLabel = (
    typeof displayedChipBalance === "number" ? displayedChipBalance : 0
  ).toLocaleString("en-IN");
  const walletBalanceLabel = `₹ ${headerChipBalanceLabel}`;
  const lobbyChipsLabel = (
    typeof visibleChipBalance === "number" ? visibleChipBalance : 0
  ).toLocaleString("en-IN");
  const minimumBootAmount = publicTableState?.config?.bootAmount || round?.bootAmount || 0;
  const roundAllowsLowBalanceView = round?.status === "active" || round?.status === "starting" || round?.status === "dealing";
  const shouldKickForLowBalance = Boolean(
    screen === "table" &&
    minimumBootAmount > 0 &&
    typeof visibleChipBalance === "number" &&
    visibleChipBalance < minimumBootAmount &&
    !roundAllowsLowBalanceView
  );

  useEffect(() => {
    if (!shouldKickForLowBalance) {
      return undefined;
    }

    const timer = window.setTimeout(() => {
      void handleExitTable();
    }, 0);

    return () => {
      window.clearTimeout(timer);
    };
  }, [handleExitTable, shouldKickForLowBalance]);

  const shellClassName = [
    "app-shell",
    isMobileDevice ? "is-mobile-device" : "",
  ].filter(Boolean).join(" ");

  const handleDealCardSound = useCallback(() => {
    gameplaySoundApiRef.current.playDealCard();
  }, []);
  const stakeControlState = buildStakeControlState({
    round,
    userSeat,
    acting: activeActing,
    selectedStake,
  });

  const handleAdjustStake = useCallback((direction) => {
    const { selectedIndex, stakeOptions } = stakeControlState;

    if (!stakeOptions.length) {
      return;
    }

    const fallbackIndex = selectedIndex === -1 ? 0 : selectedIndex;
    const nextIndex = Math.max(0, Math.min(stakeOptions.length - 1, fallbackIndex + direction));
    setSelectedStake(stakeOptions[nextIndex]);
  }, [stakeControlState]);

  function handleSelectVariant(variantId) {
    try {
      clearStoredPublicSession(variantId);
    } catch {}
    window.location.assign(withLaunchQuery(`/public?variant=${encodeURIComponent(variantId)}`));
  }

  useEffect(() => {
    document.body.classList.toggle("app-body-mobile", isMobileDevice);

    return () => {
      document.body.classList.remove("app-body-mobile");
    };
  }, [isMobileDevice]);

  let pageContent = <main className="casino-page casino-page-menu" />;

  if (screen !== null) {
    if (activeLoading) {
      const publicJoinMessage = activeError || "Connecting you to the live table. Please wait a moment.";
      const showPublicJoiningScreen = screen === "table" && isMobileDevice;

      pageContent = (
        <main className={`casino-page ${screen === "table" ? "casino-page-table" : "casino-page-menu"}`}>
          {screen === "table" && !showPublicJoiningScreen ? <TableGameplayShellBackdrop /> : null}
          {screen === "table" ? (
            showPublicJoiningScreen ? (
              <PublicTableJoiningMobileScreen message={publicJoinMessage} />
            ) : (
              <section className="relative z-[1] flex min-h-screen items-center justify-center px-4">
                <TableGameplayBackdrop />
                <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.14),rgba(0,6,8,0.24)_68%,rgba(0,0,0,0.84))]" aria-hidden="true" />
                <PublicTableJoiningDesktopBanner
                  message={activeError || "Connecting you to the live table. Please wait a moment."}
                />
              </section>
            )
          ) : (
            <section className="menu-screen flex min-h-screen overflow-hidden bg-[linear-gradient(180deg,#041213,#010607_76%)]">
              <DesktopMenuBackdrop />
              <div className="app-frame app-frame-surface app-frame-clip relative h-dvh">
                <div
                  className="pointer-events-none absolute inset-0 bg-cover bg-top bg-no-repeat"
                  aria-hidden="true"
                  style={{
                    backgroundImage:
                      "linear-gradient(180deg, rgba(0, 15, 16, 0.08), rgba(0, 6, 8, 0.26) 68%, rgba(0, 0, 0, 0.88))",
                  }}
                />

                <div className="relative z-[1] flex h-dvh flex-col overflow-hidden">
                  <header className="relative h-[138px] w-full pt-[max(16px,env(safe-area-inset-top))]">
                    <Image
                      src="/newAssets/homeTopBg.png"
                      alt=""
                      fill
                      priority
                      sizes="(max-width: 640px) 100vw, 500px"
                      className="object-cover object-top"
                      aria-hidden="true"
                    />
                  </header>

                  <div className="relative flex flex-1 items-center justify-center px-[14px] pb-[max(20px,env(safe-area-inset-bottom))] pt-2">
                    <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(circle_at_50%_36%,rgba(59,231,222,0.12),transparent_24%),radial-gradient(circle_at_50%_74%,rgba(239,194,78,0.08),transparent_22%)]" aria-hidden="true" />

                    <div className="relative w-full overflow-hidden rounded-[28px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(7,44,48,0.92),rgba(3,16,19,0.97))] shadow-[0_24px_44px_rgba(0,0,0,0.36)] backdrop-blur-[10px]">
                      <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(255,255,255,0.03),transparent_36%)]" aria-hidden="true" />
                      <div className="absolute left-0 top-0 h-full w-px bg-[linear-gradient(180deg,transparent,rgba(67,216,204,0.28),transparent)]" aria-hidden="true" />
                      <div className="absolute right-0 top-0 h-full w-px bg-[linear-gradient(180deg,transparent,rgba(239,194,78,0.18),transparent)]" aria-hidden="true" />

                      <div className="relative z-[1] px-5 pb-5 pt-5">
                        <div className="flex items-center justify-between gap-3">
                          <div className="inline-flex items-center gap-2 rounded-full border border-[#43d8cc]/22 bg-[#081c1f]/72 px-2.5 py-1 text-[9px] font-black uppercase tracking-[0.18em] text-[#abfff5]">
                            <span className="h-1.5 w-1.5 rounded-full bg-[#43d8cc]" />
                            Teen Patti
                          </div>
                          <div className="text-[10px] font-bold uppercase tracking-[0.16em] text-white/42">
                            Syncing
                          </div>
                        </div>

                        <div className="mt-6">
                          <h1 className="text-[1.9rem] font-black leading-[0.98] tracking-[-0.02em] text-white">
                            Loading
                            <br />
                            table
                          </h1>
                          <p className="mt-3 max-w-[18rem] text-[0.82rem] leading-[1.5] text-white/66">
                            {activeError || "Preparing the live table and syncing your player session."}
                          </p>
                        </div>

                        <div className="mt-6 rounded-[22px] border border-white/8 bg-[rgba(3,18,21,0.52)] p-4">
                          <div className="flex items-center justify-between gap-3 text-[10px] font-black uppercase tracking-[0.14em] text-white/54">
                            <span>Table sync</span>
                            <span className="text-[#ffe6a0]">Please wait</span>
                          </div>

                          <div className="mt-3 h-[3px] w-full overflow-hidden rounded-full bg-white/8">
                            <div className="h-full w-[54%] rounded-full bg-[linear-gradient(90deg,#2ac7bf_0%,#78efe5_48%,#efc24e_100%)] shadow-[0_0_18px_rgba(67,216,204,0.3)]" style={{ animation: "pulse 1.8s ease-in-out infinite" }} />
                          </div>

                          <div className="mt-4 grid gap-2">
                            <div className="flex items-center justify-between rounded-[16px] border border-white/7 bg-[rgba(255,255,255,0.03)] px-3 py-2.5">
                              <span className="text-[11px] font-semibold text-white/74">Session recovery</span>
                              <span className="text-[10px] font-black uppercase tracking-[0.14em] text-[#abfff5]">In progress</span>
                            </div>
                            <div className="flex items-center justify-between rounded-[16px] border border-white/7 bg-[rgba(255,255,255,0.03)] px-3 py-2.5">
                              <span className="text-[11px] font-semibold text-white/74">Table state sync</span>
                              <span className="text-[10px] font-black uppercase tracking-[0.14em] text-white/42">Queued</span>
                            </div>
                          </div>
                        </div>

                        <div className="mt-5 flex items-center gap-3 rounded-[18px] border border-[#43d8cc]/14 bg-[rgba(9,54,58,0.28)] px-3 py-3">
                          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-[#43d8cc]/18 bg-[rgba(67,216,204,0.08)]">
                            <div className="h-2 w-2 rounded-full bg-[#43d8cc] shadow-[0_0_14px_rgba(67,216,204,0.65)]" />
                          </div>
                          <p className="text-[11px] leading-[1.45] text-[#d8fffb]/72">
                            The table will reopen automatically as soon as the game state is ready.
                          </p>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          )}
        </main>
      );
    } else {
      pageContent = (
        <main className={`casino-page ${screen === "table" ? "casino-page-table" : "casino-page-menu"}`}>
          {screen === "menu" ? (
            <section className="menu-screen flex min-h-screen overflow-hidden bg-[linear-gradient(180deg,#041213,#010607_76%)]">
              <DesktopMenuBackdrop />
              <div className="app-frame app-frame-surface app-frame-clip relative h-dvh">
                <div
                  className="pointer-events-none absolute inset-0 bg-cover bg-top bg-no-repeat"
                  aria-hidden="true"
                  style={{
                    backgroundImage:
                      "linear-gradient(180deg, rgba(0, 15, 16, 0.06), rgba(0, 6, 8, 0.3) 68%, rgba(0, 0, 0, 0.9))",
                  }}
                />

                <div className="lobby relative z-[1] h-dvh overflow-x-hidden overflow-y-auto overscroll-y-contain">
                  <header className="lobby-topbar">
                    <div className="lobby-topbar__plate">
                      <span className="lobby-topbar__avatar">
                        <Image
                          src="/newAssets/avatars/avatar2.png"
                          alt="Player avatar"
                          width={62}
                          height={62}
                        />
                      </span>
                      <Image
                        src="/newAssets/crown.png"
                        alt=""
                        width={26}
                        height={32}
                        className="lobby-topbar__crown"
                        aria-hidden="true"
                      />

                      <span className="lobby-topbar__identity">
                        <strong className="lobby-topbar__name">
                          {platformProfile?.username || "Player"}
                        </strong>
                        <span className="lobby-topbar__wallet">
                          <Image src="/newAssets/Chip.png" alt="" width={17} height={17} aria-hidden="true" />
                          {walletBalanceLabel}
                          <span className="lobby-topbar__add" aria-hidden="true">+</span>
                        </span>
                      </span>

                      <span className="lobby-topbar__chips">{lobbyChipsLabel}</span>
                    </div>

                    <button
                      type="button"
                      className="lobby-topbar__menu"
                      onClick={() => handleMenuAction("history")}
                      disabled={!platformLaunchContext}
                      aria-label="Open transaction history"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
                        <path d="M4 7h16M4 12h16M4 17h16" strokeLinecap="round" />
                      </svg>
                    </button>
                  </header>

                  <section className="lobby-grid">
                    {VARIANT_OPTIONS.map((variant) => (
                      <VariantCard
                        key={variant.id}
                        title={variant.label}
                        onClick={() => handleSelectVariant(variant.id)}
                      />
                    ))}
                  </section>
                </div>
              </div>

              <div className="sr-only" aria-live="polite">
                {menuActions[activeMenuItem] || "Feature selected"}
              </div>
            </section>
          ) : (
            <section className="table-screen">
              <GameplaySoundController
                round={tableRound}
                seats={displaySeats}
                turnClock={turnClock}
                viewerSeatId={userSeat?.id || null}
                apiRef={gameplaySoundApiRef}
              />
              <CasinoTable
                round={tableRound}
                seats={displaySeats}
                onAction={handleGameplayAction}
                acting={activeActing}
                onExitTable={handleExitTable}
                onStartNextRound={handleStartNextPublicRound}
                onDeclineNextRound={handleExitTable}
                nextRoundDecision={nextRoundDecision}
                nextRoundState={nextRoundState}
                turnClock={turnClock}
                roundStartClock={roundStartClock}
                nowMs={turnNow}
                waitingForSeat={waitingForSeat}
                waitingMessage={
                  publicTableState?.admissionMessage ||
                  publicTableState?.round?.message ||
                  "You will join automatically when the next round starts."
                }
                onDealCard={handleDealCardSound}
                variant={publicTableState?.config?.variant || null}
                variantState={round?.variantState || null}
                chipBalance={displayedChipBalance || 0}
              />

              {round?.status === "active" && !waitingForSeat && userSeat ? (
                <TableControls
                  round={round}
                  seats={displaySeats}
                  userSeat={userSeat}
                  acting={activeActing}
                  onAction={handleGameplayAction}
                  stakeState={stakeControlState}
                  onAdjustStake={handleAdjustStake}
                />
              ) : waitingForSeat ? null : (
                <div className="table-screen__status pointer-events-none fixed inset-x-0 bottom-0 z-50 flex justify-center px-3 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:px-6 sm:pb-5">
                  <div className="app-frame">
                  {shouldKickForLowBalance ? (
                    <StatusBanner
                      title="Not enough balance"
                      message="You do not have enough balance for the next round. Returning to the home screen."
                      tone="error"
                    />
                  ) : isPublicWaiting ? (
                    <StatusBanner
                      title="Waiting for next round"
                      message={
                        publicTableState?.admissionMessage ||
                        publicTableState?.round?.message ||
                        "You will join automatically when the next round starts."
                      }
                    />
                  ) : isPublicLobbyWaiting ? (
                    <StatusBanner
                      title="Waiting for players"
                      message={
                        publicLobbySecondsRemaining > 0
                          ? `Your seat is reserved. Round starts in ${publicLobbySecondsRemaining}s.`
                          : "Your seat is reserved. Starting the round now."
                      }
                    />
                  ) : activeError ? (
                    <StatusBanner
                      title="Table update"
                      message={activeError}
                      tone={activeError.toLowerCase().includes("insufficient") ? "error" : "default"}
                    />
                  ) : null}
                  </div>
                </div>
              )}

              <div className="sr-only" aria-live="polite">
                {activeError ||
                  round?.result?.reason ||
                  round?.message ||
                  "Waiting for live table state."}
              </div>
            </section>
          )}

        </main>
      );
    }
  }

  return (
    <div className={shellClassName}>
      <HomeIntroSound enabled={isPublicMenuView} />
      <div className="app-shell__content">
        {pageContent}
      </div>
    </div>
  );
}
