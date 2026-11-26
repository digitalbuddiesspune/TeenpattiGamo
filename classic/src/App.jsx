import ButtonClickSound from "./components/ButtonClickSound";
import GameClient from "./components/GameClient";
import PlatformTransactionHistoryScreen from "./components/PlatformTransactionHistoryScreen";
import { GAME_VARIANT } from "./config/variant";
import { useAppLocation } from "./lib/navigation";

export default function App() {
  const { pathname, search } = useAppLocation();
  const normalizedPath = pathname.replace(/\/+$/, "") || "/";
  const searchParams = new URLSearchParams(search);

  let content;
  if (normalizedPath === "/transactions" || normalizedPath === "/transactions/history") {
    content = <PlatformTransactionHistoryScreen initialTab="rounds" />;
  } else if (normalizedPath === "/transactions/debit") {
    content = <PlatformTransactionHistoryScreen initialTab="debit" />;
  } else if (normalizedPath === "/transactions/credit") {
    content = <PlatformTransactionHistoryScreen initialTab="credit" />;
  } else {
    const roomCode = String(searchParams.get("room") || searchParams.get("roomCode") || "")
      .trim()
      .toUpperCase();
    const playFlag = String(searchParams.get("play") || "").trim().toLowerCase();
    const modeParam = String(searchParams.get("mode") || "").trim().toLowerCase();
    const variantParam = String(searchParams.get("variant") || "").trim().toLowerCase();

    if (roomCode) {
      content = (
        <GameClient
          view="private-room"
          variant={GAME_VARIANT.id}
          initialRoomCode={roomCode}
        />
      );
    } else if (
      playFlag === "1" ||
      playFlag === "true" ||
      modeParam === "table" ||
      variantParam === GAME_VARIANT.id
    ) {
      content = <GameClient view="public-table" variant={GAME_VARIANT.id} />;
    } else {
      content = <GameClient view="public-menu" variant={GAME_VARIANT.id} />;
    }
  }

  return (
    <>
      <ButtonClickSound />
      {content}
    </>
  );
}
