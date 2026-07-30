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

function handleGesture() {
  if (unlocked) {
    return;
  }

  unlocked = true;
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

export function useAudioUnlocked() {
  const [value, setValue] = useState(false);

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
