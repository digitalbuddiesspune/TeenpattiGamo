import { useCallback, useEffect, useRef, useState } from "react";
import Image from "./AppImage";
import CasinoTable from "./CasinoTable";
import CustomRoomModal from "./CustomRoomModal";
import GameplaySoundController from "./GameplaySoundController";
import HomeIntroSound from "./HomeIntroSound";
import TableControls from "./TableControls";
import { GAME_VARIANT } from "../config/variant";
import { clearStoredPrivateSession, readStoredPrivateSession, usePrivateRoom } from "../hooks/usePrivateRoom";
import { clearStoredPublicSession, useTeenPattiGame } from "../hooks/useTeenPattiGame";
import { fetchPlatformProfile, fetchPublicLobbyConfig } from "../lib/api";
import { unlockAudioFromGesture } from "../lib/audioUnlock";
import { navigatePush, navigateReplace } from "../lib/navigation";
import {
  getPlatformLaunchContext,
  PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY,
  PLATFORM_PROFILE_STORAGE_KEY,
  withLaunchQuery,
} from "../lib/platformLaunch";
import { rewritePlayerNamesInText, withDisplayNames } from "../lib/playerDisplayName";
import { buildStakeControlState } from "../lib/stakeControl";

const menuActions = {
  public: `Opening ${GAME_VARIANT.title}`,
  custom: "Opening custom room",
  history: "Opening round history",
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

// Visual seat indices follow clockwise seating from the viewer:
// 0 = bottom center (viewer), then 1 = left-lower, 2 = left-upper, 3 = right-upper, 4 = right-lower.
function rotateSeatsForViewer(seats = []) {
  if (!seats.length) {
    return [];
  }

  const viewerIndex = seats.findIndex((seat) => seat.isUser);
  const ordered =
    viewerIndex <= 0
      ? seats
      : seats.map((_, index) => seats[(viewerIndex + index) % seats.length]);

  return ordered.map((seat, index) => ({
    ...seat,
    seatIndex: index,
  }));
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

const LOBBY_MENU_OPTIONS = [
  {
    id: "custom_room",
    label: "Custom Room",
    hint: "Create or join with code",
  },
  {
    id: "history",
    label: "Transaction History",
    hint: "Rounds, debits & credits",
  },
  {
    id: "rules",
    label: "How to Play",
    hint: `${GAME_VARIANT.label} rules overview`,
  },
  {
    id: "refresh",
    label: "Refresh Balance",
    hint: "Sync wallet from platform",
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
    <div className="lobby-rules" role="dialog" aria-modal="true" aria-label={`How to play ${GAME_VARIANT.label}`}>
      <button type="button" className="lobby-rules__backdrop" onClick={onClose} aria-label="Close rules" />
      <div className="lobby-rules__panel">
        <header className="lobby-rules__header">
          <h2>How to Play {GAME_VARIANT.label}</h2>
          <button type="button" className="lobby-rules__close" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>
        <p className="lobby-rules__intro">
          {GAME_VARIANT.rulesIntro}
        </p>
        <ul className="lobby-rules__list">
          {GAME_VARIANT.rules.map((rule) => (
            <li key={rule.title}>
              <strong>{rule.title}</strong>
              <span>{rule.description}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}

function LeaveTableConfirmModal({ open, onCancel, onConfirm, confirming, error }) {
  const dialogRef = useRef(null);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!open || !dialog) return;
    const previousFocus = document.activeElement;
    dialog.showModal();
    return () => {
      dialog.close();
      if (previousFocus?.isConnected) previousFocus.focus();
    };
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <dialog
      ref={dialogRef}
      className="leave-confirm"
      aria-labelledby="leave-confirm-title"
      aria-describedby="leave-confirm-copy"
      aria-busy={confirming}
      onCancel={(event) => {
        event.preventDefault();
        if (!confirming) onCancel();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget && !confirming) onCancel();
      }}
    >
      <div className="leave-confirm__panel">
        <header className="leave-confirm__header">
          <h2 id="leave-confirm-title">Leave Table?</h2>
        </header>
        <p id="leave-confirm-copy" className="leave-confirm__copy">
          Going back will leave this table and forfeit the current round. Are you sure you want to exit?
        </p>
        {error ? <p className="leave-confirm__error" role="alert">{error}</p> : null}
        <div className="leave-confirm__actions">
          <button
            type="button"
            className="leave-confirm__button leave-confirm__button--stay"
            onClick={onCancel}
            disabled={confirming}
          >
            Stay
          </button>
          <button
            type="button"
            className="leave-confirm__button leave-confirm__button--leave"
            onClick={onConfirm}
            disabled={confirming}
          >
            {confirming ? "Leaving..." : "Leave"}
          </button>
        </div>
      </div>
    </dialog>
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
              {isSync ? "Syncing session" : `${GAME_VARIANT.label} table`}
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
              {isSync ? "Loading table" : `Finding your ${GAME_VARIANT.label} table`}
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

function SingleVariantHeroCard({ title, tagline, summary, bootAmount, onClick }) {
  return (
    <button type="button" className="variant-card lobby-single__card" onClick={onClick}>
      <AceFan />

      <span className="variant-card__body">
        <span className="lobby-single__eyebrow">{tagline}</span>
        <strong className="variant-card__title">{title}</strong>
        {summary ? <span className="variant-card__summary">{summary}</span> : null}
        <span className="lobby-single__meta">
          <span className="lobby-single__cta">Play Public Table</span>
          {typeof bootAmount === "number" ? (
            <span className="lobby-single__boot">
              Boot ₹{bootAmount.toLocaleString("en-IN")}
            </span>
          ) : null}
        </span>
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
  variant = GAME_VARIANT.id,
  initialRoomCode = "",
}) {
  const [turnNow, setTurnNow] = useState(() => Date.now());
  const [isMobileDevice] = useState(true);
  const [lobbyMenuOpen, setLobbyMenuOpen] = useState(false);
  const [lobbyRulesOpen, setLobbyRulesOpen] = useState(false);
  const [leaveConfirmOpen, setLeaveConfirmOpen] = useState(false);
  const [leaveConfirming, setLeaveConfirming] = useState(false);
  const [leaveError, setLeaveError] = useState("");
  const leaveInFlightRef = useRef(false);
  const lobbyMenuAnchorRef = useRef(null);
  const allowHistoryExitRef = useRef(false);
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
  const [platformProfileLoading, setPlatformProfileLoading] = useState(false);
  const [platformLaunchContext, setPlatformLaunchContext] = useState(() => {
    if (typeof window === "undefined") {
      return null;
    }
    return getPlatformLaunchContext();
  });
  const [lobbyInitialBalance, setLobbyInitialBalance] = useState(null);
  const [globalBootAmount, setGlobalBootAmount] = useState(null);
  const publicDecisionHandledRef = useRef(false);
  const publicDecisionPendingExitRef = useRef(false);
  const platformProfileRequestIdRef = useRef(0);
  const bootDeductionProfileRoundKeyRef = useRef("");
  const gameplaySoundApiRef = useRef({
    playDealCard() {},
  });

  const [activePrivateRoomCode, setActivePrivateRoomCode] = useState(() => (initialRoomCode || "").trim().toUpperCase());
  const [customRoomModalOpen, setCustomRoomModalOpen] = useState(() => Boolean(initialRoomCode && typeof window !== "undefined" && !readStoredPrivateSession(initialRoomCode)));
  const [customRoomInitialMode, setCustomRoomInitialMode] = useState(() => (initialRoomCode ? "join" : "create"));
  const [customRoomBusy, setCustomRoomBusy] = useState(false);
  const [customRoomError, setCustomRoomError] = useState("");

  useEffect(() => {
    const normalized = (initialRoomCode || "").trim().toUpperCase();
    if (normalized) {
      setActivePrivateRoomCode(normalized);
      if (!readStoredPrivateSession(normalized)) {
        setCustomRoomInitialMode("join");
        setCustomRoomModalOpen(true);
      }
    } else if (view !== "private-room") {
      setActivePrivateRoomCode("");
    }
  }, [initialRoomCode, view]);

  const isPrivateRoomView = view === "private-room" || Boolean(activePrivateRoomCode);
  const isPublicTableView = view === "public-table" && !isPrivateRoomView;
  const isPublicMenuView = !isPrivateRoomView && !isPublicTableView;
  const activeMenuItem = isPrivateRoomView ? "custom" : "public";

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

  const privateRoom = usePrivateRoom(activePrivateRoomCode, isPrivateRoomView);
  const {
    roomCode: currentPrivateRoomCode,
    roomState: privateRoomState,
    loading: privateLoading,
    acting: privateActing,
    error: privateError,
    createRoom: createPrivateRoomAction,
    joinRoom: joinPrivateRoomAction,
    runAction: privateRunAction,
    startRound: privateStartRound,
    readyNextRound: privateReadyNextRound,
    startNextRound: privateStartNextRound,
    leaveRoom: leavePrivateRoomAction,
  } = privateRoom;

  const screen = (isPublicTableView || isPrivateRoomView) ? "table" : "menu";
  const activeTableState = isPrivateRoomView ? privateRoomState : publicTableState;
  const isHost = Boolean(isPrivateRoomView && privateRoomState?.isHost);

  const isPublicWaiting = publicTableState?.playerStatus === "waiting_for_next_round";
  const isPrivateWaiting = Boolean(isPrivateRoomView && privateRoomState?.viewerPlayerStatus === "waiting_for_next_round");
  const waitingForSeat = isPrivateRoomView ? isPrivateWaiting : isPublicWaiting;

  const round = activeTableState?.round || null;
  const isPublicLobbyWaiting = !isPrivateRoomView && publicTableState?.playerStatus === "active_at_table" && !round;

  const rawSeats = round?.seats || (isPrivateRoomView && privateRoomState?.players ? privateRoomState.players.map((p, idx) => ({
    id: p.id,
    name: p.name,
    avatar: p.avatar || "you",
    connected: p.connected !== false,
    status: p.status,
    isHost: p.isHost,
    isUser: p.isViewer || p.id === privateRoomState?.viewerPlayerId,
    seatIndex: idx,
    balance: typeof p.balance === "number" ? p.balance : (privateRoomState?.config?.bootAmount ? privateRoomState.config.bootAmount * 10 : 10000),
    cards: [],
    packed: false,
    seen: false,
    currentStake: 0,
    totalContributed: 0,
  })) : []);

  const canonicalSeats = rawSeats;
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
  const nextRoundState = isPrivateRoomView
    ? privateRoomState?.nextRound
    : (publicTableState?.nextRound || (publicTableState ? {
        viewerAccepted: Boolean(publicTableState.nextRoundReady || publicTableState.viewerNextRoundReady),
        waitingForAcceptances: round?.status === "complete",
        acceptedPlayerIds: [],
        pendingPlayerIds: [],
      } : null));

  const canStartNextRound = isPrivateRoomView
    ? Boolean(
        isHost &&
        (privateRoomState?.nextRound?.acceptedPlayerIds?.length || 0) >= 2 &&
        (!privateRoomState?.nextRound?.pendingPlayerIds || privateRoomState.nextRound.pendingPlayerIds.length === 0)
      )
    : true;
  const platformLaunchToken = platformLaunchContext?.token || "";
  const platformLaunchGameId = platformLaunchContext?.gameId || 0;
  const isPlatformLaunch = Boolean(platformLaunchToken && platformLaunchGameId > 0);

  useEffect(() => {
    setPlatformLaunchContext(getPlatformLaunchContext());
  }, [view, variant]);

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
        if (cancelled) {
          return;
        }
        if (typeof config?.initialBalance === "number") {
          setLobbyInitialBalance(config.initialBalance);
        }
        if (typeof config?.bootAmount === "number") {
          setGlobalBootAmount(config.bootAmount);
        }
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

    setPlatformProfileLoading(true);
    const requestId = platformProfileRequestIdRef.current + 1;
    platformProfileRequestIdRef.current = requestId;
    try {
      const profile = await fetchPlatformProfile(platformLaunchToken, platformLaunchGameId);
      if (requestId !== platformProfileRequestIdRef.current) {
        return profile;
      }
      commitPlatformProfile(profile);
      return profile;
    } finally {
      if (requestId === platformProfileRequestIdRef.current) {
        setPlatformProfileLoading(false);
      }
    }
  }, [commitPlatformProfile, platformLaunchGameId, platformLaunchToken]);

  useEffect(() => {
    if (!platformLaunchToken || !platformLaunchGameId) {
      setPlatformProfileLoading(false);
      return undefined;
    }

    let cancelled = false;
    setPlatformProfileLoading(true);
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
      })
      .finally(() => {
        if (!cancelled && requestId === platformProfileRequestIdRef.current) {
          setPlatformProfileLoading(false);
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
    refreshPlatformProfileAfterAction(() => {
      if (isPrivateRoomView) {
        return privateRunAction(actionType, payload);
      }
      return publicRunAction(actionType, payload);
    })
  ), [isPrivateRoomView, privateRunAction, publicRunAction, refreshPlatformProfileAfterAction]);

  useEffect(() => {
    if (!platformLaunchToken || !platformLaunchGameId || screen !== "table" || !round?.id) {
      return undefined;
    }

    if (!["starting", "dealing", "active"].includes(round.status)) {
      return undefined;
    }

    const roundKey = `${isPrivateRoomView ? "private" : "public"}:${round.id}`;
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
    isPrivateRoomView,
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

    if (actionId === "custom_room") {
      setCustomRoomInitialMode("create");
      setCustomRoomError("");
      setCustomRoomModalOpen(true);
      return;
    }

    if (actionId === "history") {
      navigatePush(withLaunchQuery("/transactions/history"));
      return;
    }

    if (actionId === "rules") {
      setLobbyRulesOpen(true);
      return;
    }

    if (actionId === "refresh") {
      void syncPlatformProfile().catch(() => {});
    }
  }

  const closeLobbyMenu = useCallback(() => {
    setLobbyMenuOpen(false);
  }, []);

  const closeLobbyRules = useCallback(() => {
    setLobbyRulesOpen(false);
  }, []);

  const handleOpenCreateCustomRoom = useCallback(() => {
    setCustomRoomInitialMode("create");
    setCustomRoomError("");
    setCustomRoomModalOpen(true);
  }, []);

  const handleOpenJoinCustomRoom = useCallback(() => {
    setCustomRoomInitialMode("join");
    setCustomRoomError("");
    setCustomRoomModalOpen(true);
  }, []);

  const handleCloseCustomRoomModal = useCallback(() => {
    if (customRoomBusy) return;
    setCustomRoomModalOpen(false);
    setCustomRoomError("");
  }, [customRoomBusy]);

  const handleCreateCustomRoomSubmit = useCallback(async ({ variant: selectedVariant }) => {
    setCustomRoomBusy(true);
    setCustomRoomError("");
    try {
      unlockAudioFromGesture();
      const payload = {
        variant: selectedVariant || GAME_VARIANT.id,
        platformLaunchToken,
        platformLaunchGameId,
      };
      const room = await createPrivateRoomAction(payload);
      if (room?.roomCode) {
        setActivePrivateRoomCode(room.roomCode);
        setCustomRoomModalOpen(true);
        navigateReplace(withLaunchQuery(`/?room=${encodeURIComponent(room.roomCode)}`));
      }
    } catch (err) {
      setCustomRoomError(err?.message || "Failed to create custom room. Please try again.");
    } finally {
      setCustomRoomBusy(false);
    }
  }, [createPrivateRoomAction, platformLaunchGameId, platformLaunchToken]);

  const handleJoinCustomRoomSubmit = useCallback(async ({ roomCode }) => {
    setCustomRoomBusy(true);
    setCustomRoomError("");
    try {
      unlockAudioFromGesture();
      const code = (roomCode || "").trim().toUpperCase();
      const payload = {
        roomCode: code,
        platformLaunchToken,
        platformLaunchGameId,
      };
      const room = await joinPrivateRoomAction(payload);
      if (room?.roomCode) {
        setActivePrivateRoomCode(room.roomCode);
        setCustomRoomModalOpen(true);
        navigateReplace(withLaunchQuery(`/?room=${encodeURIComponent(room.roomCode)}`));
      }
    } catch (err) {
      setCustomRoomError(err?.message || "Failed to join custom room. Please check the code and try again.");
    } finally {
      setCustomRoomBusy(false);
    }
  }, [joinPrivateRoomAction, platformLaunchGameId, platformLaunchToken]);

  const handleHostStartFirstRound = useCallback(async () => {
    if (!isHost) return;
    setCustomRoomBusy(true);
    setCustomRoomError("");
    try {
      await refreshPlatformProfileAfterAction(() => privateStartRound());
      setCustomRoomModalOpen(false);
    } catch (err) {
      setCustomRoomError(err?.message || "Failed to start private round.");
    } finally {
      setCustomRoomBusy(false);
    }
  }, [isHost, privateStartRound, refreshPlatformProfileAfterAction]);

  useEffect(() => {
    if (round?.id && round?.status !== "complete" && customRoomModalOpen) {
      setCustomRoomModalOpen(false);
    }
  }, [round?.id, round?.status, customRoomModalOpen]);

  useEffect(() => {
    if (!isPrivateRoomView || !privateRoomState) {
      return undefined;
    }

    if (!round || privateRoomState.status === "lobby") {
      if (!customRoomModalOpen) {
        const timer = window.setTimeout(() => {
          setCustomRoomModalOpen(true);
        }, 0);
        return () => window.clearTimeout(timer);
      }
      return undefined;
    }

    if (round.status === "complete") {
      if (customRoomModalOpen) {
        return undefined;
      }
      const timer = window.setTimeout(() => {
        setCustomRoomError("");
        setCustomRoomModalOpen(true);
      }, 3200);
      return () => window.clearTimeout(timer);
    }

    return undefined;
  }, [customRoomModalOpen, isPrivateRoomView, privateRoomState, round]);

  const handleExitMatchmaking = useCallback(async () => {
    try {
      if (isPrivateRoomView) {
        await leavePrivateRoomAction();
      } else {
        await leavePublicTable();
      }
    } catch {}
    if (isPrivateRoomView) {
      if (currentPrivateRoomCode) {
        clearStoredPrivateSession(currentPrivateRoomCode);
      }
      setActivePrivateRoomCode("");
    } else {
      clearStoredPublicSession(variant);
    }
    allowHistoryExitRef.current = true;
    navigateReplace(withLaunchQuery("/"));
  }, [currentPrivateRoomCode, isPrivateRoomView, leavePrivateRoomAction, leavePublicTable, variant]);

  const handleExitTable = useCallback(async () => {
    if (leaveInFlightRef.current) return false;
    leaveInFlightRef.current = true;
    setLeaveConfirming(true);
    setLeaveError("");
    try {
      if (isPrivateRoomView) {
        await leavePrivateRoomAction();
      } else {
        await leavePublicTable();
      }
      allowHistoryExitRef.current = true;
      if (isPrivateRoomView) {
        if (currentPrivateRoomCode) {
          clearStoredPrivateSession(currentPrivateRoomCode);
        }
        setActivePrivateRoomCode("");
      }
      setLeaveConfirmOpen(false);
      setCustomRoomModalOpen(false);
      navigateReplace(withLaunchQuery("/"));
      return true;
    } catch (error) {
      allowHistoryExitRef.current = false;
      setLeaveError(error?.message || "Unable to leave the table. Please try again.");
      setLeaveConfirmOpen(true);
      return false;
    } finally {
      leaveInFlightRef.current = false;
      setLeaveConfirming(false);
    }
  }, [currentPrivateRoomCode, isPrivateRoomView, leavePrivateRoomAction, leavePublicTable]);

  const handleLeaveCustomRoomLobby = useCallback(async () => {
    setCustomRoomBusy(true);
    try {
      await handleExitTable();
    } finally {
      setCustomRoomBusy(false);
    }
  }, [handleExitTable]);

  const requestLeaveTable = useCallback(() => {
    if (leaveInFlightRef.current) return;
    setLeaveError("");
    setLeaveConfirmOpen(true);
  }, []);

  const cancelLeaveTable = useCallback(() => {
    if (leaveInFlightRef.current) {
      return;
    }
    setLeaveConfirmOpen(false);
  }, []);

  useEffect(() => {
    if (screen !== "table" || typeof window === "undefined") {
      return undefined;
    }

    allowHistoryExitRef.current = false;
    const tableUrl = window.location.href;
    const guardState = { __teenPattiLeaveGuard: tableUrl };

    if (window.history.state?.__teenPattiLeaveGuard !== tableUrl) {
      window.history.pushState(guardState, "", tableUrl);
    }

    const onPopState = (event) => {
      if (allowHistoryExitRef.current) {
        return;
      }
      event.stopImmediatePropagation();
      window.history.pushState(guardState, "", tableUrl);
      requestLeaveTable();
    };

    window.addEventListener("popstate", onPopState, true);
    return () => {
      window.removeEventListener("popstate", onPopState, true);
    };
  }, [screen, view, variant, initialRoomCode, requestLeaveTable]);

  const handleStartNextRound = useCallback(async () => {
    if (isPrivateRoomView) {
      if (isHost && canStartNextRound) {
        await refreshPlatformProfileAfterAction(() => privateStartNextRound());
      } else {
        await refreshPlatformProfileAfterAction(() => privateReadyNextRound());
      }
      return;
    }
    await refreshPlatformProfileAfterAction(() => publicReadyNextRound());
  }, [
    canStartNextRound,
    isHost,
    isPrivateRoomView,
    privateReadyNextRound,
    privateStartNextRound,
    publicReadyNextRound,
    refreshPlatformProfileAfterAction,
  ]);

  useEffect(() => {
    if (isPrivateRoomView || screen !== "table") {
      publicDecisionHandledRef.current = false;
      publicDecisionPendingExitRef.current = false;
      return undefined;
    }

    if (nextRoundState?.viewerAccepted) {
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
    isPrivateRoomView,
    nextRoundDecision.expired,
    nextRoundState?.viewerAccepted,
    publicTableState?.playerStatus,
    round?.status,
    screen,
  ]);

  const activeLoading = isPrivateRoomView ? privateLoading : publicLoading;
  const activeActing = isPrivateRoomView ? privateActing : publicActing;
  const activeError = isPrivateRoomView ? privateError : publicError;
  const platformBalanceFallback = isPrivateRoomView
    ? privateRoomState?.viewerPlatformBalance
    : publicTableState?.viewerPlatformBalance;
  const platformNameFallback = isPrivateRoomView
    ? privateRoomState?.viewerPlayerName
    : publicTableState?.playerName;
  const visibleChipBalance = userSeat?.balance ?? (
    isPrivateRoomView ? privateRoomState?.viewerPlatformBalance : publicTableState?.table?.balance
  );
  const displayedChipBalance = typeof platformProfile?.balance === "number"
    ? platformProfile.balance
    : typeof platformBalanceFallback === "number"
      ? platformBalanceFallback
      : typeof visibleChipBalance === "number"
        ? visibleChipBalance
        : isPlatformLaunch
          ? null
          : lobbyInitialBalance;
  const displayedUsername = platformProfile?.username
    || platformNameFallback
    || (isPlatformLaunch && platformProfileLoading ? "Loading..." : "Player");
  const headerChipBalanceLabel = platformProfileLoading && isPlatformLaunch && displayedChipBalance == null
    ? "..."
    : (typeof displayedChipBalance === "number" ? displayedChipBalance : 0).toLocaleString("en-IN");
  const walletBalanceLabel = `₹ ${headerChipBalanceLabel}`;
  const minimumBootAmount = isPrivateRoomView
    ? (privateRoomState?.config?.bootAmount || round?.bootAmount || 0)
    : (publicTableState?.config?.bootAmount || round?.bootAmount || 0);
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
  });

  function handlePlayPublicTable() {
    try {
      clearStoredPublicSession(GAME_VARIANT.id);
    } catch {}
    // Keep the same document so the lobby click unlocks audio for shuffle/deal.
    unlockAudioFromGesture();
    navigatePush(withLaunchQuery(`/?play=1`));
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
                      {displayedUsername}
                    </strong>
                  </div>

                  <div className="lobby-topbar__center">
                    <div className="hidden md:flex items-center gap-2">
                      <button
                        type="button"
                        onClick={handleOpenCreateCustomRoom}
                        className="rounded-xl border border-[#efc24e]/50 bg-gradient-to-b from-[#ffd973] to-[#c98e1f] px-3.5 py-1.5 text-xs lg:text-sm font-extrabold text-[#2a1300] shadow-[0_4px_12px_rgba(0,0,0,0.25)] transition-transform hover:scale-[1.02] active:scale-[0.98] whitespace-nowrap"
                      >
                        Create Room
                      </button>
                      <button
                        type="button"
                        onClick={handleOpenJoinCustomRoom}
                        className="rounded-xl border border-white/20 bg-gradient-to-b from-white/15 to-white/5 px-3.5 py-1.5 text-xs lg:text-sm font-extrabold text-white shadow-[0_4px_12px_rgba(0,0,0,0.25)] transition-transform hover:scale-[1.02] active:scale-[0.98] whitespace-nowrap"
                      >
                        Join with Code
                      </button>
                    </div>

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

              <div className="lobby-main lobby-main--single">
                <section className="lobby-single" aria-label={GAME_VARIANT.title}>
                  <SingleVariantHeroCard
                    title={GAME_VARIANT.title}
                    tagline={GAME_VARIANT.tagline}
                    summary={GAME_VARIANT.summary}
                    bootAmount={globalBootAmount}
                    onClick={handlePlayPublicTable}
                  />

                  <div className="lobby-single__actions">
                    <button
                      type="button"
                      onClick={handleOpenCreateCustomRoom}
                      className="lobby-single__action lobby-single__action--gold"
                    >
                      <strong>Create Room</strong>
                      <span>Host a private {GAME_VARIANT.label} table</span>
                    </button>
                    <button
                      type="button"
                      onClick={handleOpenJoinCustomRoom}
                      className="lobby-single__action"
                    >
                      <strong>Join with Code</strong>
                      <span>Enter a friend&apos;s room code</span>
                    </button>
                  </div>
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
                onExitTable={requestLeaveTable}
                onStartNextRound={handleStartNextRound}
                onDeclineNextRound={handleExitTable}
                nextRoundDecision={nextRoundDecision}
                nextRoundState={nextRoundState}
                turnClock={turnClock}
                roundStartClock={roundStartClock}
                nowMs={turnNow}
                waitingForSeat={waitingForSeat}
                waitingMessage={
                  isPrivateRoomView
                    ? (isPrivateWaiting ? "You will be seated when the next round starts." : "Waiting for next round.")
                    : (publicTableState?.admissionMessage || publicTableState?.round?.message || "You will join automatically when the next round starts.")
                }
                onDealCard={handleDealCardSound}
                variant={isPrivateRoomView ? (privateRoomState?.config?.variant || variant) : (publicTableState?.config?.variant || GAME_VARIANT)}
                variantState={round?.variantState || null}
                chipBalance={displayedChipBalance || 0}
                maxPotAmount={isPrivateRoomView ? (privateRoomState?.config?.maxPotAmount || 0) : (publicTableState?.config?.maxPotAmount || 0)}
                isPublicTable={isPublicTableView}
                isPrivateMode={isPrivateRoomView}
                roomCode={currentPrivateRoomCode || activePrivateRoomCode}
                roomName={privateRoomState?.roomName || privateRoomState?.name || ""}
                isHost={isHost}
                canStartNextRound={canStartNextRound}
              />

              {round?.status === "active" && !waitingForSeat && userSeat ? (
                <TableControls
                  round={tableRound}
                  seats={displaySeats}
                  userSeat={userSeat}
                  acting={activeActing}
                  onAction={handleGameplayAction}
                  stakeState={stakeControlState}
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
      <LeaveTableConfirmModal
        open={leaveConfirmOpen}
        onCancel={cancelLeaveTable}
        onConfirm={() => {
          void handleExitTable();
        }}
        confirming={leaveConfirming}
        error={leaveError}
      />
      <CustomRoomModal
        open={customRoomModalOpen}
        roomState={privateRoomState}
        roomCode={currentPrivateRoomCode || activePrivateRoomCode}
        isHost={isHost}
        initialMode={customRoomInitialMode}
        initialRoomCode={initialRoomCode || activePrivateRoomCode}
        globalBootAmount={globalBootAmount}
        onClose={handleCloseCustomRoomModal}
        onCreateRoom={handleCreateCustomRoomSubmit}
        onJoinRoom={handleJoinCustomRoomSubmit}
        onStartGame={handleHostStartFirstRound}
        onLeaveRoom={handleLeaveCustomRoomLobby}
        busy={customRoomBusy}
        error={customRoomError}
      />
    </div>
  );
}
