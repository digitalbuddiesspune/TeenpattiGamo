"use client";

import { useSearchParams } from "next/navigation";
import GameClient from "../components/GameClient";
import { isSupportedVariant } from "../lib/variants";

export default function PublicPageClient() {
  const searchParams = useSearchParams();
  const variant = String(searchParams.get("variant") || "").trim().toLowerCase();
  const roomCode = String(searchParams.get("room") || searchParams.get("roomCode") || "").trim().toUpperCase();

  if (roomCode) {
    return <GameClient view="private-room" initialRoomCode={roomCode} />;
  }

  if (isSupportedVariant(variant)) {
    return <GameClient view="public-table" variant={variant} />;
  }

  return <GameClient view="public-menu" />;
}
