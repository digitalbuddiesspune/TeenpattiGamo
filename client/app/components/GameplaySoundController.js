"use client";

import { useCallback, useEffect, useRef, useState } from "react";

export const GAMEPLAY_SFX_STORAGE_KEY = "teen-patti-sfx-enabled";

const SOUND_LIBRARY = {
  shuffle: {
    src: "/sounds/card-shuffle.mp3",
    volume: 0.2,
    loop: true,
  },
  deal: {
    src: "/sounds/card-deal.mp3",
    volume: 0.16,
  },
  turn: {
    src: "/sounds/turn-change.mp3",
    volume: 0.18,
  },
  ticking: {
    src: "/sounds/clock-ticking.mp3",
    volume: 0.12,
    loop: true,
  },
  win: {
    src: "/sounds/win-round.mp3",
    volume: 0.22,
  },
  lose: {
    src: "/sounds/lose-round.mp3",
    volume: 0.2,
  },
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
  enabled,
  apiRef,
}) {
  const [isUnlocked, setIsUnlocked] = useState(false);
  const soundsRef = useRef({});
  const lastResultKeyRef = useRef("");
  const lastTurnSeatIdRef = useRef(null);
  const turnReadyRef = useRef(false);

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
    if (!enabled || !isUnlocked) {
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
  }, [enabled, isUnlocked]);

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
    function unlockAudio() {
      setIsUnlocked(true);
    }

    document.addEventListener("pointerdown", unlockAudio, true);
    document.addEventListener("keydown", unlockAudio, true);

    return () => {
      document.removeEventListener("pointerdown", unlockAudio, true);
      document.removeEventListener("keydown", unlockAudio, true);
    };
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
    if (enabled) {
      return;
    }

    stopTicking();
    stopShuffle();
  }, [enabled, stopShuffle, stopTicking]);

  useEffect(() => {
    if (!enabled || !isUnlocked) {
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
  }, [enabled, isUnlocked, round?.id, round?.status, stopTicking, turnClock?.isCritical]);

  useEffect(() => {
    if (!enabled || !isUnlocked) {
      stopShuffle();
      return;
    }

    const shuffleAudio = soundsRef.current.shuffle;
    const shouldShuffle = round?.status === "starting" && Boolean(round?.id);

    if (!shuffleAudio) {
      return;
    }

    if (shouldShuffle) {
      if (shuffleAudio.paused) {
        shuffleAudio.currentTime = 0;
        safelyPlay(shuffleAudio);
      }
      return;
    }

    safelyStop(shuffleAudio);
  }, [enabled, isUnlocked, round?.id, round?.status, stopShuffle]);

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
