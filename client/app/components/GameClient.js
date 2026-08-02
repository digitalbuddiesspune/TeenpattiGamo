"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import CasinoTable from "./CasinoTable";
import GameplaySoundController from "./GameplaySoundController";
import HomeIntroSound from "./HomeIntroSound";
import TableControls, { buildStakeControlState } from "./TableControls";
import { clearStoredPublicSession, useTeenPattiGame } from "../hooks/useTeenPattiGame";
import { fetchPlatformProfile, fetchPublicLobbyConfig } from "../lib/api";
import {
  getPlatformLaunchContext,
  PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY,
  PLATFORM_PROFILE_STORAGE_KEY,
  withLaunchQuery,
} from "../lib/platformLaunch";
import { unlockAudioFromGesture } from "../lib/audioUnlock";
import { rewritePlayerNamesInText, withDisplayNames } from "../lib/playerDisplayName";
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

// Visual slots (user always 0 at bottom center):
// 2 = left-upper, 3 = right-upper  → opposite on the x-axis
// 1 = left-lower, 4 = right-lower  → two more seats below that pair
const OPPONENT_DISPLAY_SLOTS = {
  1: [2],
  2: [2, 3],
  3: [2, 3, 1],
  4: [2, 3, 1, 4],
};

function rotateSeatsForViewer(seats = []) {
  if (!seats.length) {
    return [];
  }

  const viewerIndex = seats.findIndex((seat) => seat.isUser);
  const ordered =
    viewerIndex <= 0
      ? seats
      : seats.map((_, index) => seats[(viewerIndex + index) % seats.length]);

  const viewer = ordered.find((seat) => seat.isUser) || ordered[0];
  const opponents = ordered.filter((seat) => seat !== viewer);
  const opponentSlots = OPPONENT_DISPLAY_SLOTS[opponents.length] || [1, 2, 3, 4];

  return [
    { ...viewer, seatIndex: 0 },
    ...opponents.map((seat, index) => ({
      ...seat,
      seatIndex: opponentSlots[index] ?? index + 1,
    })),
  ];
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

function TableGameplayShellBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0" aria-hidden="true">
      <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.06),rgba(0,6,8,0.18)_68%,rgba(0,0,0,0.62))]" />
    </div>
  );
}

const LOBBY_MENU_OPTIONS = [
  {
    id: "history",
    label: "Transaction History",
    hint: "Rounds, debits & credits",
  },
  {
    id: "rules",
    label: "How to Play",
    hint: "Variant rules overview",
  },
  {
    id: "refresh",
    label: "Refresh Balance",
    hint: "Sync wallet from platform",
  },
  {
    id: "exit",
    label: "Exit Lobby",
    hint: "Leave and return home",
  },
];

function LobbyMenuDropdown({ open, anchorRef, onSelect, onClose }) {
  const panelRef = useRef(null);
  const [coords, setCoords] = useState({ top: 0, right: 0 });

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    function updatePosition() {
      const anchor = anchorRef?.current;
      if (!anchor) {
        return;
      }
      const rect = anchor.getBoundingClientRect();
      setCoords({
        top: Math.round(rect.bottom + 8),
        right: Math.round(window.innerWidth - rect.right),
      });
    }

    updatePosition();
    window.addEventListener("resize", updatePosition);
    window.addEventListener("scroll", updatePosition, true);
    return () => {
      window.removeEventListener("resize", updatePosition);
      window.removeEventListener("scroll", updatePosition, true);
    };
  }, [open, anchorRef]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    function handlePointer(event) {
      const target = event.target;
      if (
        panelRef.current?.contains(target) ||
        anchorRef?.current?.contains(target)
      ) {
        return;
      }
      onClose();
    }

    function handleKey(event) {
      if (event.key === "Escape") {
        onClose();
      }
    }

    // Defer so the opening click doesn't immediately close the menu.
    const timer = window.setTimeout(() => {
      document.addEventListener("pointerdown", handlePointer);
    }, 0);
    document.addEventListener("keydown", handleKey);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener("pointerdown", handlePointer);
      document.removeEventListener("keydown", handleKey);
    };
  }, [open, onClose, anchorRef]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="lobby-menu"
      ref={panelRef}
      role="menu"
      aria-label="Lobby menu"
      style={{ top: `${coords.top}px`, right: `${coords.right}px` }}
    >
      {LOBBY_MENU_OPTIONS.map((option) => (
        <button
          key={option.id}
          type="button"
          role="menuitem"
          className="lobby-menu__item"
          onClick={() => onSelect(option.id)}
        >
          <strong>{option.label}</strong>
          <span>{option.hint}</span>
        </button>
      ))}
    </div>
  );
}

function LobbyRulesModal({ open, onClose }) {
  if (!open) {
    return null;
  }

  return (
    <div className="lobby-rules" role="dialog" aria-modal="true" aria-label="How to play">
      <button type="button" className="lobby-rules__backdrop" onClick={onClose} aria-label="Close rules" />
      <div className="lobby-rules__panel">
        <header className="lobby-rules__header">
          <h2>How to Play</h2>
          <button type="button" className="lobby-rules__close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>
        <p className="lobby-rules__intro">
          Pick a table variant below. Classic Teen Patti is the default; others change jokers or win conditions.
        </p>
        <ul className="lobby-rules__list">
          {VARIANT_OPTIONS.map((variant) => (
            <li key={variant.id}>
              <strong>{variant.label}</strong>
              <span>{variant.summary}</span>
            </li>
          ))}
        </ul>
      </div>
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

function PublicTableJoiningScreen({ message, mode = "matchmaking", onExitLobby }) {
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

            {typeof onExitLobby === "function" ? (
              <div className="joining-screen__exit">
                <button
                  type="button"
                  className="joining-screen__exit-button"
                  onClick={onExitLobby}
                >
                  Exit Lobby
                </button>
              </div>
            ) : null}
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

function VariantCard({ title, summary, onClick }) {
  return (
    <button type="button" className="variant-card" onClick={onClick}>
      <AceFan />

      <span className="variant-card__body">
        <strong className="variant-card__title">{title}</strong>
        {summary ? <span className="variant-card__summary">{summary}</span> : null}
      </span>

      <span className="variant-card__chip" aria-hidden="true">
        <Image
          src="/newAssets/chip.png"
          alt=""
          width={40}
          height={36}
          sizes="40px"
          className="object-contain"
          style={{ width: "100%", height: "auto" }}
        />
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
  const [lobbyMenuOpen, setLobbyMenuOpen] = useState(false);
  const [lobbyRulesOpen, setLobbyRulesOpen] = useState(false);
  const lobbyMenuAnchorRef = useRef(null);
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
  const [lobbyInitialBalance, setLobbyInitialBalance] = useState(null);
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
  const { seats: namedSeats, nameById } = withDisplayNames(canonicalSeats);
  const displaySeats = rotateSeatsForViewer(namedSeats);
  const resolveMappedName = (playerId, fallback) =>
    (playerId && nameById.get(playerId)) || rewritePlayerNamesInText(fallback, canonicalSeats, nameById) || fallback;
  const tableRound = round
    ? {
        ...round,
        seats: namedSeats,
        message: rewritePlayerNamesInText(round.message, canonicalSeats, nameById),
        pendingSideShow: round.pendingSideShow
          ? {
              ...round.pendingSideShow,
              requesterName: resolveMappedName(round.pendingSideShow.requesterId, round.pendingSideShow.requesterName),
              targetName: resolveMappedName(round.pendingSideShow.targetId, round.pendingSideShow.targetName),
            }
          : round.pendingSideShow,
        sideShowResult: round.sideShowResult
          ? {
              ...round.sideShowResult,
              requesterName: resolveMappedName(round.sideShowResult.requesterId, round.sideShowResult.requesterName),
              targetName: resolveMappedName(round.sideShowResult.targetId, round.sideShowResult.targetName),
            }
          : round.sideShowResult,
      }
    : round;
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

  useEffect(() => {
    if (!isPublicMenuView) {
      return undefined;
    }

    let cancelled = false;
    fetchPublicLobbyConfig()
      .then((config) => {
        if (cancelled || typeof config?.initialBalance !== "number") {
          return;
        }
        setLobbyInitialBalance(config.initialBalance);
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [isPublicMenuView]);

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
    setLobbyMenuOpen(false);

    if (actionId === "history") {
      router.push(withLaunchQuery("/transactions/history"));
      return;
    }

    if (actionId === "rules") {
      setLobbyRulesOpen(true);
      return;
    }

    if (actionId === "refresh") {
      void syncPlatformProfile().catch(() => {});
      return;
    }

    if (actionId === "exit") {
      void handleExitLobby();
    }
  }

  const closeLobbyMenu = useCallback(() => {
    setLobbyMenuOpen(false);
  }, []);

  const closeLobbyRules = useCallback(() => {
    setLobbyRulesOpen(false);
  }, []);

  const handleExitLobby = useCallback(() => {
    setLobbyMenuOpen(false);
    setLobbyRulesOpen(false);

    VARIANT_OPTIONS.forEach((entry) => {
      clearStoredPublicSession(entry.id);
    });

    try {
      window.sessionStorage.removeItem(PLATFORM_PROFILE_STORAGE_KEY);
      window.sessionStorage.removeItem(PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY);
    } catch {}

    setPlatformProfile(null);
    router.replace("/");
  }, [router]);

  const handleExitMatchmaking = useCallback(async () => {
    try {
      await leavePublicTable();
    } catch {}
    clearStoredPublicSession(variant);
    router.replace(withLaunchQuery("/public"));
  }, [leavePublicTable, router, variant]);

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
    : typeof visibleChipBalance === "number"
      ? visibleChipBalance
    : lobbyInitialBalance;
  const headerChipBalanceLabel = (
    typeof displayedChipBalance === "number" ? displayedChipBalance : 0
  ).toLocaleString("en-IN");
  const walletBalanceLabel = `₹ ${headerChipBalanceLabel}`;
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
    // Keep the same document so the lobby click unlocks audio for shuffle/deal.
    unlockAudioFromGesture();
    router.push(withLaunchQuery(`/public?variant=${encodeURIComponent(variantId)}`));
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
            <PublicTableJoiningScreen
              message={publicJoinMessage}
              onExitLobby={handleExitMatchmaking}
            />
          ) : (
            <PublicTableJoiningScreen
              mode="sync"
              message={activeError || "Preparing the live table and syncing your player session."}
              onExitLobby={handleExitMatchmaking}
            />
          )}
        </main>
      );
    } else {
      pageContent = (
        <main className={`casino-page ${screen === "table" ? "casino-page-table" : "casino-page-menu"}`}>
          {screen === "menu" ? (
            <section className="lobby relative z-[1] flex h-dvh w-full flex-col overflow-hidden">
              <div className="lobby__glow lobby__glow--a" aria-hidden="true" />
              <div className="lobby__glow lobby__glow--b" aria-hidden="true" />

              <header className="lobby-topbar">
                <span className="lobby-topbar__avatar">
                  <Image
                    src="/newAssets/avatars/avatar2.png"
                    alt="Player avatar"
                    width={52}
                    height={52}
                  />
                </span>

                <div className="lobby-topbar__plate">
                  <div className="lobby-topbar__pod">
                    <strong className="lobby-topbar__name">
                      {platformProfile?.username || "Player"}
                    </strong>
                  </div>

                  <div className="lobby-topbar__center">
                    <span className="lobby-topbar__wallet">
                      <Image
                        src="/newAssets/chip.png"
                        alt=""
                        width={24}
                        height={22}
                        aria-hidden="true"
                        className="object-contain"
                        style={{ width: 20, height: "auto" }}
                      />
                      <b>{walletBalanceLabel}</b>
                    </span>
                  </div>
                </div>

                <div className="lobby-topbar__menu-wrap" ref={lobbyMenuAnchorRef}>
                  <button
                    type="button"
                    className="lobby-topbar__menu"
                    onClick={() => setLobbyMenuOpen((open) => !open)}
                    aria-label="Open menu"
                    aria-expanded={lobbyMenuOpen}
                    aria-haspopup="menu"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" aria-hidden="true">
                      <path d="M4 7h16M4 12h16M4 17h16" strokeLinecap="round" />
                    </svg>
                  </button>
                </div>
              </header>

              <LobbyMenuDropdown
                open={lobbyMenuOpen}
                anchorRef={lobbyMenuAnchorRef}
                onSelect={handleMenuAction}
                onClose={closeLobbyMenu}
              />

              <LobbyRulesModal open={lobbyRulesOpen} onClose={closeLobbyRules} />

              <div className="lobby-main">
                <section className="lobby-grid" aria-label="Game variants">
                  {VARIANT_OPTIONS.map((variant) => (
                    <VariantCard
                      key={variant.id}
                      title={variant.label}
                      summary={variant.summary}
                      onClick={() => handleSelectVariant(variant.id)}
                    />
                  ))}
                </section>
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
                  round={tableRound}
                  seats={displaySeats}
                  userSeat={userSeat}
                  acting={activeActing}
                  onAction={handleGameplayAction}
                  stakeState={stakeControlState}
                  onAdjustStake={handleAdjustStake}
                />
              ) : waitingForSeat ? null : (
                  <div className="table-screen__status pointer-events-none fixed inset-x-0 bottom-0 z-50 flex justify-center px-3 pb-[calc(env(safe-area-inset-bottom)+12px)] sm:px-6 sm:pb-5">
                  <div className="w-full max-w-xl">
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
