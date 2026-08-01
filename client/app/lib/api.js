const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "http://localhost:4000/api";
const REQUEST_TIMEOUT_MS = 10000;

export class ApiError extends Error {
  constructor(message, code = "request_failed") {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

function buildApiUrl(path, variant = "classic") {
  const url = new URL(`${API_BASE}${path}`);
  if (variant) {
    url.searchParams.set("variant", variant);
  }
  return url.toString();
}

async function fetchJson(url, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(url, {
      ...options,
      signal: controller.signal,
      cache: "no-store"
    });
    const data = await response.json();

    if (!response.ok || data.status !== "ok") {
      throw new ApiError(data.message || "Request failed", data.code || "request_failed");
    }

    return data.data;
  } catch (error) {
    if (error?.name === "AbortError") {
      throw new ApiError("Request timed out. Please try again.", "request_timeout");
    }
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}

async function request(path, options = {}, variant = "classic") {
  return fetchJson(buildApiUrl(path, variant), {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });
}

export function getApiBase() {
  return API_BASE;
}

export function getSocketBase() {
  const apiUrl = new URL(API_BASE);
  apiUrl.pathname = apiUrl.pathname.replace(/\/api\/?$/, "") || "/";
  apiUrl.search = "";
  apiUrl.hash = "";
  return apiUrl.toString().replace(/\/$/, "");
}

export function getPublicTableWebSocketUrl() {
  const socketBase = new URL(getSocketBase());
  socketBase.protocol = socketBase.protocol === "https:" ? "wss:" : "ws:";
  socketBase.pathname = "/ws/public-tables";
  socketBase.search = "";
  socketBase.hash = "";
  return socketBase.toString();
}

export function fetchTable(variant) {
  return request("/table", {}, variant);
}

export function joinPublicTable(playerName, clientSeed, variant) {
  return request("/public/join", {
    method: "POST",
    body: JSON.stringify({
      playerName,
      clientSeed
    })
  }, variant);
}

export function fetchPublicLobbyConfig() {
  return request("/public/config", {}, null);
}

export function fetchPlatformProfile(token, gameId) {
  return request("/platform/profile", {
    method: "POST",
    body: JSON.stringify({
      token,
      gameId
    })
  }, null);
}

export function createPlatformPublicSession(token, gameId, clientSeed, variant) {
  return request("/platform/session", {
    method: "POST",
    body: JSON.stringify({
      token,
      gameId,
      clientSeed,
      variant
    })
  }, null);
}

export function fetchPlatformDebitTransactions(token, gameId, offset = 0, limit = 20) {
  return request("/platform/transactions/debit", {
    method: "POST",
    body: JSON.stringify({
      token,
      gameId,
      offset,
      limit
    })
  }, null);
}

export function fetchPlatformCreditTransactions(token, gameId, offset = 0, limit = 20) {
  return request("/platform/transactions/credit", {
    method: "POST",
    body: JSON.stringify({
      token,
      gameId,
      offset,
      limit
    })
  }, null);
}

export function fetchPlatformRoundHistory(token, gameId, offset = 0, limit = 20) {
  return request("/platform/history/rounds", {
    method: "POST",
    body: JSON.stringify({
      token,
      gameId,
      offset,
      limit
    })
  }, null);
}

export function fetchPublicSession(playerId, playerToken, variant) {
  const url = new URL(buildApiUrl("/public/session", variant));
  url.searchParams.set("playerId", playerId);
  url.searchParams.set("playerToken", playerToken);

  return fetchJson(url.toString(), {
    headers: {
      "Content-Type": "application/json"
    }
  });
}

export function performPublicAction(playerId, playerToken, actionType, payload = {}, variant) {
  return request("/public/action", {
    method: "POST",
    body: JSON.stringify({
      playerId,
      playerToken,
      actionType,
      payload
    })
  }, variant);
}

export function leavePublicTable(playerId, playerToken, variant) {
  return request("/public/leave", {
    method: "POST",
    body: JSON.stringify({
      playerId,
      playerToken
    })
  }, variant);
}

export function startRound(variant) {
  return request("/round/start", {
    method: "POST"
  }, variant);
}

export function performAction(actionType, payload = {}, variant) {
  return request("/action", {
    method: "POST",
    body: JSON.stringify({
      playerId: "user-1",
      actionType,
      payload
    })
  }, variant);
}

export function fetchHistory(variant) {
  return request("/history", {}, variant);
}

export function startAutoplay(settings, variant) {
  return request("/autoplay/start", {
    method: "POST",
    body: JSON.stringify(settings)
  }, variant);
}

export function stopAutoplay(variant) {
  return request("/autoplay/stop", {
    method: "POST"
  }, variant);
}

export function buildEventSourceUrl(variant) {
  return buildApiUrl("/events", variant);
}
