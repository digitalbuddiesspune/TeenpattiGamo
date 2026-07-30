export const PLATFORM_PROFILE_STORAGE_KEY = "teen-patti-platform-profile";
export const PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY = "teen-patti-platform-launch-context";

export function withLaunchQuery(path) {
  if (typeof window === "undefined") {
    return path;
  }

  const params = new URLSearchParams(window.location.search);
  const token = params.get("id")?.trim();
  const gameId = params.get("game_id")?.trim();
  if (!token) {
    return path;
  }

  const next = new URLSearchParams();
  next.set("id", token);
  if (gameId) {
    next.set("game_id", gameId);
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
  const rawGameId = params.get("game_id")?.trim() || "";
  const gameId = Number(rawGameId);

  if (token && Number.isFinite(gameId) && gameId > 0) {
    const nextContext = { token, gameId };
    try {
      window.sessionStorage.setItem(PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY, JSON.stringify(nextContext));
    } catch {}
    return nextContext;
  }

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
