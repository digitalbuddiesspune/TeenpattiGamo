export const PLATFORM_PROFILE_STORAGE_KEY = "teen-patti-platform-profile";
export const PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY = "teen-patti-platform-launch-context";

/** Matches server PLATFORM_GAME_ID (Teen Patti on Double Roll = 2). */
export function resolvePlatformGameId(rawValue) {
  const parsed = Number(String(rawValue ?? "").trim());
  if (Number.isFinite(parsed) && parsed > 0) {
    return parsed;
  }

  const fallback = Number(process.env.NEXT_PUBLIC_PLATFORM_GAME_ID || "2");
  return Number.isFinite(fallback) && fallback > 0 ? fallback : 0;
}

function readStoredLaunchContext() {
  try {
    const raw = window.sessionStorage.getItem(PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const stored = JSON.parse(raw);
    if (!stored?.token || !Number.isFinite(stored?.gameId) || stored.gameId <= 0) {
      return null;
    }
    return stored;
  } catch {
    return null;
  }
}

function persistLaunchContext(context) {
  try {
    window.sessionStorage.setItem(PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY, JSON.stringify(context));
  } catch {}
  return context;
}

export function withLaunchQuery(path) {
  if (typeof window === "undefined") {
    return path;
  }

  const params = new URLSearchParams(window.location.search);
  const token = params.get("id")?.trim();
  if (!token) {
    return path;
  }

  const next = new URLSearchParams();
  next.set("id", token);
  const gameId = resolvePlatformGameId(params.get("game_id"));
  if (gameId > 0) {
    next.set("game_id", String(gameId));
  }
  const separator = path.includes("?") ? "&" : "?";
  return `${path}${separator}${next.toString()}`;
}

export function getPlatformLaunchContext() {
  if (typeof window === "undefined") {
    return null;
  }

  const params = new URLSearchParams(window.location.search);
  const token = params.get("id")?.trim() || "";
  const stored = readStoredLaunchContext();

  if (!token) {
    return stored;
  }

  const gameId = resolvePlatformGameId(params.get("game_id"));
  if (gameId > 0) {
    return persistLaunchContext({ token, gameId });
  }

  if (stored?.token === token && stored.gameId > 0) {
    return stored;
  }

  return null;
}
