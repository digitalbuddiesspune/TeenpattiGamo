"use client";

import { useEffect } from "react";

const CLICK_SOUND_SRC = "/sounds/button-click.wav";
const CLICK_SOUND_VOLUME = 0.1;

export default function ButtonClickSound() {
  useEffect(() => {
    const audio = new Audio(CLICK_SOUND_SRC);
    audio.preload = "auto";
    audio.volume = CLICK_SOUND_VOLUME;

    function playClickSound() {
      try {
        audio.pause();
        audio.currentTime = 0;
      } catch {}

      const playPromise = audio.play();

      if (playPromise?.catch) {
        playPromise.catch(() => {});
      }
    }

    function getTargetButton(event) {
      const target =
        event.target instanceof Element
          ? event.target
          : event.target instanceof Node
            ? event.target.parentElement
            : null;
      const button = target?.closest("button") || null;

      if (!button || button.disabled) {
        return null;
      }

      return button;
    }

    function handlePointerDown(event) {
      const button = getTargetButton(event);

      if (!button) {
        return;
      }

      playClickSound();
    }

    function handleKeyDown(event) {
      if (event.key !== "Enter" && event.key !== " ") {
        return;
      }

      const button = getTargetButton(event);

      if (!button) {
        return;
      }

      playClickSound();
    }

    document.addEventListener("pointerdown", handlePointerDown, true);
    document.addEventListener("keydown", handleKeyDown, true);

    return () => {
      document.removeEventListener("pointerdown", handlePointerDown, true);
      document.removeEventListener("keydown", handleKeyDown, true);
      audio.pause();
      audio.src = "";
    };
  }, []);

  return null;
}
