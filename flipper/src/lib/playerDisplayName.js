import { pickStableBotName } from "./bot-usernames";

const GUEST_NAME_RE = /^Guest(?:[_\s-]?\d+)?$/i;
const GUEST_PLAYER_RE = /^guest\s*player$/i;
const BOT_ID_RE = /-bot-\d+$/i;

function isPlaceholderName(name) {
  const value = String(name || "").trim();
  if (!value) {
    return true;
  }
  return GUEST_NAME_RE.test(value) || GUEST_PLAYER_RE.test(value);
}

function stripBotWord(name) {
  return String(name || "")
    .replace(/\b[Bb][Oo][Tt]\b/g, " ")
    .replace(/\s+/g, " ")
    .replace(/^[\s\-_|]+|[\s\-_|]+$/g, "")
    .trim();
}

export function isBotSeat(seat) {
  if (!seat) {
    return false;
  }
  if (seat.isBot || seat.isRealPlayer === false) {
    return true;
  }
  return BOT_ID_RE.test(String(seat.id || ""));
}

/**
 * Prefer real usernames for table seats. Bots with Guest_ / Bot labels get a
 * stable name from the bot username list.
 */
export function resolvePlayerDisplayName(seat, exclude = []) {
  const raw = String(seat?.name || "").trim();
  const withoutBotWord = stripBotWord(raw);
  const bot = isBotSeat(seat);

  if (bot && (isPlaceholderName(raw) || isPlaceholderName(withoutBotWord) || !withoutBotWord)) {
    return pickStableBotName(seat?.id || raw || "bot", exclude);
  }

  if (!withoutBotWord) {
    return bot
      ? pickStableBotName(seat?.id || "bot", exclude)
      : `Player ${String(seat?.seatIndex ?? 0).padStart(2, "0")}`;
  }

  return withoutBotWord;
}

export function withDisplayNames(seats = []) {
  const used = [];
  const byId = new Map();

  const nextSeats = seats.map((seat) => {
    const name = resolvePlayerDisplayName(seat, used);
    if (name) {
      used.push(name);
    }
    const next = { ...seat, name };
    byId.set(seat.id, name);
    return next;
  });

  return { seats: nextSeats, nameById: byId };
}

export function rewritePlayerNamesInText(text, seats = [], nameById = null) {
  if (!text) {
    return text;
  }

  let next = String(text);
  const mapping = nameById || withDisplayNames(seats).nameById;

  seats.forEach((seat) => {
    const from = String(seat?.name || "").trim();
    const to = mapping.get(seat.id);
    if (!from || !to || from === to) {
      return;
    }
    if (next.includes(from)) {
      next = next.split(from).join(to);
    }
  });

  return stripBotWord(next);
}
