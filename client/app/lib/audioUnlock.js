"use client";

import { useEffect, useState } from "react";

/**
 * Browsers only allow audio playback after a user gesture, but that permission
 * is per-document and persists for the whole session. Components that mount
 * later (the table, for example) would otherwise never observe the gesture that
 * navigated the user to them, so the unlock flag is tracked once at module
 * scope and shared by every sound component.
 */
let unlocked = false;
const subscribers = new Set();

function primeAudioPipeline() {
  if (typeof window === "undefined") {
    return;
  }

  try {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (AudioContextClass) {
      const context = new AudioContextClass();
      if (context.state === "suspended") {
        void context.resume().catch(() => {});
      }
      const buffer = context.createBuffer(1, 1, 22050);
      const source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(context.destination);
      source.start(0);
      window.setTimeout(() => {
        void context.close().catch(() => {});
      }, 0);
    }
  } catch {}

  try {
    const silent = new Audio(
      "data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAAAAAAAABAAEAZGF0YQAAAAA=",
    );
    silent.volume = 0.001;
    const playPromise = silent.play();
    if (playPromise?.then) {
      playPromise
        .then(() => {
          silent.pause();
          silent.src = "";
        })
        .catch(() => {});
    }
  } catch {}
}

function handleGesture() {
  if (unlocked) {
    return;
  }

  unlocked = true;
  primeAudioPipeline();
  detachListeners();
  subscribers.forEach((notify) => {
    try {
      notify();
    } catch {}
  });
  subscribers.clear();
}

function attachListeners() {
  if (typeof document === "undefined") {
    return;
  }

  document.addEventListener("pointerdown", handleGesture, true);
  document.addEventListener("touchstart", handleGesture, true);
  document.addEventListener("keydown", handleGesture, true);
  document.addEventListener("click", handleGesture, true);
}

function detachListeners() {
  if (typeof document === "undefined") {
    return;
  }

  document.removeEventListener("pointerdown", handleGesture, true);
  document.removeEventListener("touchstart", handleGesture, true);
  document.removeEventListener("keydown", handleGesture, true);
  document.removeEventListener("click", handleGesture, true);
}

attachListeners();

export function isAudioUnlocked() {
  return unlocked;
}

/** Mark audio as unlocked from an explicit user gesture (e.g. joining a table). */
export function unlockAudioFromGesture() {
  handleGesture();
}

export function useAudioUnlocked() {
  const [value, setValue] = useState(() => unlocked);

  useEffect(() => {
    if (unlocked) {
      setValue(true);
      return undefined;
    }

    function notify() {
      setValue(true);
    }

    subscribers.add(notify);
    return () => {
      subscribers.delete(notify);
    };
  }, []);

  return value;
}
