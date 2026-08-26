import { clearAdminToken, getAdminToken, setAdminToken } from "./authStorage";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "/api/v1/admin/profit-loss";
const AUTH_API_BASE = import.meta.env.VITE_AUTH_API_BASE_URL || "/api/v1/admin/auth";

function authHeaders(extra = {}) {
  const token = getAdminToken();
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  };
}

async function parseError(response) {
  let message = `Request failed with status ${response.status}`;
  try {
    const body = await response.json();
    message = body?.error?.message || message;
  } catch {
    // keep default
  }
  const error = new Error(message);
  error.status = response.status;
  return error;
}

async function request(path, params = {}, options = {}) {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);

  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  }

  const response = await fetch(url, {
    ...options,
    headers: authHeaders(options.headers),
  });

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

async function authRequest(path, options = {}) {
  const url = new URL(`${AUTH_API_BASE}${path}`, window.location.origin);
  const response = await fetch(url, {
    ...options,
    headers: authHeaders({
      "Content-Type": "application/json",
      ...(options.headers || {}),
    }),
  });

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

function withFilters(params, variant, operatorId, dateFrom, dateTo, search = "", searchBy = "all") {
  const next = { ...params };

  if (variant && variant !== "all") {
    next.variant = variant;
  }

  if (operatorId && operatorId !== "all") {
    next.operatorId = operatorId;
  }

  if (dateFrom) {
    next.dateFrom = dateFrom;
  }

  if (dateTo) {
    next.dateTo = dateTo;
  }

  if (search) {
    next.search = search;
    if (searchBy && searchBy !== "all") {
      next.searchBy = searchBy;
    }
  }

  return next;
}

export function fetchSummary(
  variant = "all",
  operatorId = "all",
  dateFrom = "",
  dateTo = "",
  search = "",
  searchBy = "all",
) {
  return request("/summary", withFilters({}, variant, operatorId, dateFrom, dateTo, search, searchBy));
}

export function fetchGames(
  page = 1,
  limit = 20,
  variant = "all",
  operatorId = "all",
  dateFrom = "",
  dateTo = "",
  search = "",
  searchBy = "all",
) {
  return request(
    "/games",
    withFilters({ page, limit }, variant, operatorId, dateFrom, dateTo, search, searchBy),
  );
}

export function deleteGame(roundId) {
  return request(`/games/${encodeURIComponent(roundId)}`, {}, { method: "DELETE" });
}

export function fetchUsers(
  page = 1,
  limit = 20,
  variant = "all",
  operatorId = "all",
  dateFrom = "",
  dateTo = "",
  search = "",
  searchBy = "all",
) {
  return request(
    "/users",
    withFilters({ page, limit }, variant, operatorId, dateFrom, dateTo, search, searchBy),
  );
}

export async function loginAdmin({ email, password }) {
  const result = await authRequest("/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  setAdminToken(result.token);
  return result;
}

export async function logoutAdmin() {
  try {
    if (getAdminToken()) {
      await authRequest("/logout", { method: "POST" });
    }
  } finally {
    clearAdminToken();
  }
}

export async function fetchAdminSession() {
  if (!getAdminToken()) {
    return null;
  }

  try {
    return await authRequest("/me");
  } catch (error) {
    if (error.status === 401) {
      clearAdminToken();
      return null;
    }
    throw error;
  }
}
