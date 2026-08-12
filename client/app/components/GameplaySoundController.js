"use client";

import { useCallback, useEffect, useRef } from "react";

import { useAudioUnlocked } from "../lib/audioUnlock";

const SOUND_LIBRARY = {
  shuffle: {
    src: "/sounds/card-shuffle.mp3",
    volume: 0.35,
    loop: true,
  },
  deal: {
    src: "/sounds/card-deal.mp3",
    volume: 0.45,
  },
  turn: {
    src: "/sounds/turn-change.mp3",
    volume: 0.5,
  },
  ticking: {
    src: "/sounds/clock-ticking.mp3",
    volume: 0.3,
    loop: true,
  },
  win: {
    src: "/sounds/win-round.mp3",
    volume: 0.7,
  },
  lose: {
    src: "/sounds/lose-round.mp3",
    volume: 0.6,
  },
  chaal: {
    src: "/sounds/Chaal.mp3",
    volume: 0.8,
  },
  blind: {
    src: "/sounds/Blind.mp3",
    volume: 0.8,
  },
  raise: {
    src: "/sounds/Raise.mp3",
    volume: 0.8,
  },
  seen: {
    src: "/sounds/Seen.mp3",
    volume: 0.8,
  },
  pack: {
    src: "/sounds/Pack.mp3",
    volume: 0.8,
  },
  show: {
    src: "/sounds/show.mp3",
    volume: 0.85,
  },
};

const ACTION_SOUND_BY_TYPE = {
  blind: "blind",
  see: "seen",
  chaal: "chaal",
  raise: "raise",
  pack: "pack",
  show: "show",
};

function createAudio({ src, volume, loop = false }) {
  const audio = new Audio(src);
  audio.preload = "auto";
  audio.volume = volume;
  audio.loop = loop;
  return audio;
}

function safelyPlay(audio) {
  const playPromise = audio.play();

  if (playPromise?.catch) {
    playPromise.catch(() => {});
  }
}

function safelyStop(audio) {
  try {
    audio.pause();
    audio.currentTime = 0;
  } catch {}
}

export default function GameplaySoundController({
  round,
  seats,
  turnClock,
  viewerSeatId,
  apiRef,
}) {
  const isUnlocked = useAudioUnlocked();
  const soundsRef = useRef({});
  const lastResultKeyRef = useRef("");
  const lastTurnSeatIdRef = useRef(null);
  const turnReadyRef = useRef(false);
  const lastActionKeyRef = useRef("");
  const actionSoundsReadyRef = useRef(false);

  useEffect(() => {
    const sounds = Object.fromEntries(
      Object.entries(SOUND_LIBRARY).map(([key, config]) => [key, createAudio(config)]),
    );

    soundsRef.current = sounds;

    return () => {
      Object.values(sounds).forEach((audio) => {
        safelyStop(audio);
        audio.src = "";
      });
      soundsRef.current = {};
    };
  }, []);

  const playSound = useCallback((soundKey, { clone = false } = {}) => {
    if (!isUnlocked) {
      return;
    }

    const audio = soundsRef.current[soundKey];

    if (!audio) {
      return;
    }

    if (clone) {
      const cloneAudio = new Audio(audio.src);
      cloneAudio.volume = audio.volume;
      cloneAudio.preload = "auto";
      safelyPlay(cloneAudio);
      return;
    }

    safelyStop(audio);
    safelyPlay(audio);
  }, [isUnlocked]);

  const stopTicking = useCallback(() => {
    const tickingAudio = soundsRef.current.ticking;

    if (!tickingAudio) {
      return;
    }

    safelyStop(tickingAudio);
  }, []);

  const stopShuffle = useCallback(() => {
    const shuffleAudio = soundsRef.current.shuffle;

    if (!shuffleAudio) {
      return;
    }

    safelyStop(shuffleAudio);
  }, []);

  useEffect(() => {
    if (!apiRef) {
      return undefined;
    }

    apiRef.current = {
      playDealCard() {
        playSound("deal", { clone: true });
      },
    };

    return () => {
      apiRef.current = {
        playDealCard() {},
      };
    };
  }, [apiRef, playSound]);

  useEffect(() => {
    if (!isUnlocked) {
      stopTicking();
      return;
    }

    const tickingAudio = soundsRef.current.ticking;
    const shouldTick = round?.status === "active" && Boolean(turnClock?.isCritical);

    if (!tickingAudio) {
      return;
    }

    if (shouldTick) {
      safelyPlay(tickingAudio);
      return;
    }

    safelyStop(tickingAudio);
  }, [isUnlocked, round?.id, round?.status, stopTicking, turnClock?.isCritical]);

  useEffect(() => {
    if (!isUnlocked) {
      stopShuffle();
      return undefined;
    }

    const shuffleAudio = soundsRef.current.shuffle;
    const shouldShuffle = round?.status === "starting" && Boolean(round?.id);

    if (!shuffleAudio) {
      return undefined;
    }

    if (!shouldShuffle) {
      safelyStop(shuffleAudio);
      return undefined;
    }

    // Retry briefly — join/start can race the first unlock frame.
    const tryPlay = () => {
      if (shuffleAudio.paused) {
        try {
          shuffleAudio.currentTime = 0;
        } catch {}
        safelyPlay(shuffleAudio);
      }
    };

    tryPlay();
    const retryTimer = window.setTimeout(tryPlay, 120);

    return () => {
      window.clearTimeout(retryTimer);
    };
  }, [isUnlocked, round?.id, round?.status, stopShuffle]);

  useEffect(() => {
    const activeTurnSeatId =
      round?.status === "active"
        ? seats?.find((seat) => seat.isTurn)?.id || null
        : null;

    if (round?.status !== "active" || !round?.id) {
      lastTurnSeatIdRef.current = activeTurnSeatId;
      turnReadyRef.current = false;
      return;
    }

    if (!turnReadyRef.current) {
      lastTurnSeatIdRef.current = activeTurnSeatId;
      turnReadyRef.current = true;
      return;
    }

    if (
      activeTurnSeatId &&
      lastTurnSeatIdRef.current &&
      activeTurnSeatId !== lastTurnSeatIdRef.current
    ) {
      playSound("turn");
    }

    lastTurnSeatIdRef.current = activeTurnSeatId;
  }, [playSound, round?.id, round?.status, seats]);

  useEffect(() => {
    const actionLog = Array.isArray(round?.actionLog) ? round.actionLog : [];
    const roundId = round?.id || "";

    if (!roundId) {
      lastActionKeyRef.current = "";
      actionSoundsReadyRef.current = false;
      return;
    }

    const latestAction = actionLog[actionLog.length - 1] || null;
    const actionKey = latestAction
      ? `${roundId}:${latestAction.id || actionLog.length}:${latestAction.actionType}:${latestAction.timestamp || ""}`
      : `${roundId}:empty`;

    if (!actionSoundsReadyRef.current || !lastActionKeyRef.current.startsWith(`${roundId}:`)) {
      lastActionKeyRef.current = actionKey;
      actionSoundsReadyRef.current = true;
      return;
    }

    if (actionKey === lastActionKeyRef.current) {
      return;
    }

    lastActionKeyRef.current = actionKey;

    const soundKey = ACTION_SOUND_BY_TYPE[latestAction?.actionType];
    if (soundKey) {
      playSound(soundKey);
    }
  }, [playSound, round?.actionLog, round?.id]);

  useEffect(() => {
    if (round?.status !== "complete" || !round?.id || !round?.result?.winnerId || !viewerSeatId) {
      return;
    }

    const resultKey = `${round.id}:${round.result.winnerId}:${viewerSeatId}`;

    if (lastResultKeyRef.current === resultKey) {
      return;
    }

    lastResultKeyRef.current = resultKey;
    playSound(round.result.winnerId === viewerSeatId ? "win" : "lose");
  }, [playSound, round?.id, round?.result?.winnerId, round?.status, viewerSeatId]);

  return null;
}
