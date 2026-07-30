"use client";

import { useEffect, useRef } from "react";

import { useAudioUnlocked } from "../lib/audioUnlock";

const INTRO_SOUND_SRC = "/sounds/Intro.mp3";
const INTRO_SOUND_VOLUME = 0.55;

function safelyStop(audio) {
  try {
    audio.pause();
    audio.currentTime = 0;
  } catch {}
}

/**
 * Plays Intro.mp3 once while the user is on the public menu.
 * Playback is retried as soon as the shared audio unlock reports a user gesture,
 * since browsers reject autoplay before then.
 */
export default function HomeIntroSound({ enabled = true }) {
  const isUnlocked = useAudioUnlocked();
  const audioRef = useRef(null);
  const playedRef = useRef(false);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }

    const audio = new Audio(INTRO_SOUND_SRC);
    audio.preload = "auto";
    audio.volume = INTRO_SOUND_VOLUME;
    audioRef.current = audio;
    playedRef.current = false;

    return () => {
      safelyStop(audio);
      audio.src = "";
      audioRef.current = null;
    };
  }, [enabled]);

  useEffect(() => {
    const audio = audioRef.current;

    if (!enabled || !audio || playedRef.current) {
      return;
    }

    safelyStop(audio);

    const playPromise = audio.play();

    if (playPromise?.then) {
      playPromise
        .then(() => {
          playedRef.current = true;
        })
        .catch(() => {});
      return;
    }

    playedRef.current = true;
  }, [enabled, isUnlocked]);

  return null;
}
