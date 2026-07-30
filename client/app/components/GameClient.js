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
    : "border-[#e8b53c]/35 bg-[linear-gradient(180deg,rgba(125,16,25,0.94),rgba(61,7,13,0.98))]";

  return (
    <div className={`mx-auto w-full max-w-xl rounded-[18px] border px-4 py-3 text-white shadow-[0_20px_42px_rgba(0,0,0,0.34)] backdrop-blur-md ${toneClass}`}>
      <div className="flex items-start justify-between gap-3">
        <strong className={`block text-xs font-black uppercase tracking-[0.16em] sm:text-sm ${
          tone === "error" ? "text-[#ffe0d6]" : "text-[#ffe6a0]"
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

function PublicTableJoiningScreen({ message, mode = "matchmaking" }) {
  const elapsedSeconds = useElapsedMatchmakingSeconds();
  const timerLabel = formatMatchmakingTimer(elapsedSeconds);
  const progressPercent = mode === "matchmaking"
    ? Math.min(92, 18 + elapsedSeconds * 8)
    : 54;
  const isSync = mode === "sync";
  const ring = 2 * Math.PI * 54;

  return (
    <section className="joining-screen menu-screen">
      <div className="app-frame app-frame-surface app-frame-clip joining-screen__frame">
        <div className="joining-screen__atmosphere" aria-hidden="true">
          <span className="joining-screen__orb joining-screen__orb--a" />
          <span className="joining-screen__orb joining-screen__orb--b" />
          <span className="joining-screen__orb joining-screen__orb--c" />
        </div>

        <div className="joining-screen__content">
          <div className="joining-screen__stage">
            <div className="joining-screen__chip">
              <span className="joining-screen__chip-dot" />
              {isSync ? "Syncing session" : "Public table"}
            </div>

            <div className="joining-screen__ring-wrap" aria-live="polite" aria-atomic="true">
              <div className="joining-screen__ring">
                <svg className="joining-screen__ring-svg" viewBox="0 0 140 140" aria-hidden="true">
                  <defs>
                    <linearGradient id="joiningRingGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                      <stop offset="0%" stopColor="#ff8a72" />
                      <stop offset="55%" stopColor="#f03a2d" />
                      <stop offset="100%" stopColor="#efc24e" />
                    </linearGradient>
                  </defs>
                  <circle
                    className="joining-screen__ring-track"
                    cx="70"
                    cy="70"
                    r="54"
                    fill="none"
                  />
                  <circle
                    className="joining-screen__ring-value"
                    cx="70"
                    cy="70"
                    r="54"
                    fill="none"
                    stroke="url(#joiningRingGrad)"
                    style={{
                      strokeDasharray: `${ring}`,
                      strokeDashoffset: `${ring * (1 - progressPercent / 100)}`,
                    }}
                  />
                </svg>

                <div className="joining-screen__spinner" aria-hidden="true" />

                <div className="joining-screen__ring-core">
                  <span className="joining-screen__cards" aria-hidden="true">
                    <Image
                      src="/newAssets/bg-remo-cards-Photoroom.png"
                      alt=""
                      width={52}
                      height={48}
                      sizes="(max-width: 480px) 40px, 52px"
                      className="joining-screen__cards-img"
                    />
                  </span>
                  <span className="joining-screen__ring-label">
                    {isSync ? "Please wait" : "Search time"}
                  </span>
                  <strong className="joining-screen__ring-time">
                    {isSync ? "--:--" : timerLabel}
                  </strong>
                  <span className="joining-screen__ring-pct">{Math.round(progressPercent)}%</span>
                </div>
              </div>
            </div>

            <h1 className="joining-screen__title">
              {isSync ? "Loading table" : "Finding your table"}
            </h1>
            <p className="joining-screen__message">{message}</p>

            <ol className="joining-screen__steps">
              <li className="joining-screen__step is-done">
                <span className="joining-screen__step-num">1</span>
                {isSync ? "Recover" : "Seat"}
              </li>
              <li className={`joining-screen__step ${progressPercent > 40 ? "is-active" : ""}`}>
                <span className="joining-screen__step-num">2</span>
                {isSync ? "Sync" : "Match"}
              </li>
              <li className={`joining-screen__step ${progressPercent > 75 ? "is-active" : ""}`}>
                <span className="joining-screen__step-num">3</span>
                Ready
              </li>
            </ol>

            <p className="joining-screen__hint">
              {isSync
                ? "Your table will reopen automatically."
                : `Searching ${timerLabel} · opens when a seat is ready`}
            </p>
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
        src="/newAssets/bg-remo-cards-Photoroom.png"
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

      pageContent = (
        <main className={`casino-page ${screen === "table" ? "casino-page-table" : "casino-page-menu"}`}>
          {screen === "table" ? (
            <PublicTableJoiningScreen message={publicJoinMessage} />
          ) : (
            <PublicTableJoiningScreen
              mode="sync"
              message={activeError || "Preparing the live table and syncing your player session."}
            />
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
