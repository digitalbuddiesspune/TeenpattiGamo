"use client";

function toHex(bytes) {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
}

export function createClientSeed() {
  if (!globalThis.crypto?.getRandomValues) {
    throw new Error("Secure random client seed generation is unavailable.");
  }

  const bytes = new Uint8Array(32);
  globalThis.crypto.getRandomValues(bytes);
  return toHex(bytes);
}
