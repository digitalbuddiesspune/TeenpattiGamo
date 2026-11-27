export const PLATFORM_PROFILE_STORAGE_KEY = "teen-patti-platform-profile";
export const PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY = "teen-patti-platform-launch-context";

const DEFAULT_CURRENCY = "INR";
const DEFAULT_LANG = "en";

const LAUNCH_QUERY_PARAMS = [
  "token",
  "partner_code",
  "gid",
  "game_code",
  "username",
  "user_id",
  "currency",
  "lang",
  "extsess",
  "close_url",
];

/** Matches server PLATFORM_GAME_ID (Teen Patti = 2). */
export function resolvePlatformGameId(rawValue) {
  const parsed = Number(String(rawValue ?? "").trim());
  if (Number.isFinite(parsed) && parsed > 0) {
    return parsed;
  }

  const fallback = Number(process.env.NEXT_PUBLIC_PLATFORM_GAME_ID || "2");
  return Number.isFinite(fallback) && fallback > 0 ? fallback : 0;
}

export function normalizeCurrencyCode(rawValue) {
  const cleaned = String(rawValue ?? "").trim().toUpperCase();
  return /^[A-Z]{3}$/.test(cleaned) ? cleaned : DEFAULT_CURRENCY;
}

export function normalizeLanguageCode(rawValue) {
  const cleaned = String(rawValue ?? "").trim().toLowerCase();
  return /^[a-z]{2,8}(-[a-z0-9]{2,8})*$/.test(cleaned) ? cleaned : DEFAULT_LANG;
}

export function sanitizeCloseUrl(rawValue) {
  const trimmed = String(rawValue ?? "").trim();
  if (!trimmed) {
    return "";
  }
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") {
      return parsed.toString();
    }
  } catch {}
  return "";
}

export function resolveFormattingLocale(lang = DEFAULT_LANG, currency = DEFAULT_CURRENCY) {
  const normalizedLang = normalizeLanguageCode(lang);
  const normalizedCurrency = normalizeCurrencyCode(currency);
  if (normalizedLang === "en" && normalizedCurrency === "INR") {
    return "en-IN";
  }
  return normalizedLang;
}

export function formatCurrencyAmount(value, currency = DEFAULT_CURRENCY, lang = DEFAULT_LANG) {
  const amount = Number(value);
  const safeAmount = Number.isFinite(amount) ? amount : 0;
  const normalizedCurrency = normalizeCurrencyCode(currency);
  const locale = resolveFormattingLocale(lang, normalizedCurrency);
  const isWhole = Number.isInteger(safeAmount);

  try {
    return new Intl.NumberFormat(locale, {
      style: "currency",
      currency: normalizedCurrency,
      minimumFractionDigits: isWhole ? 0 : 2,
      maximumFractionDigits: isWhole ? 0 : 2,
    }).format(safeAmount);
  } catch {
    return `${normalizedCurrency} ${safeAmount.toLocaleString(locale)}`;
  }
}

export function formatLocalizedNumber(value, currency = DEFAULT_CURRENCY, lang = DEFAULT_LANG) {
  const amount = Number(value);
  const safeAmount = Number.isFinite(amount) ? amount : 0;
  const locale = resolveFormattingLocale(lang, currency);
  try {
    return safeAmount.toLocaleString(locale);
  } catch {
    return safeAmount.toLocaleString("en-IN");
  }
}

function normalizeStoredContext(raw) {
  if (!raw || typeof raw !== "object") {
    return null;
  }

  const token = String(raw.token || "").trim();
  if (!token) {
    return null;
  }

  const rawGid = String(raw.gid || raw.gameId || "").trim();
  const gameId = resolvePlatformGameId(rawGid);

  return {
    token,
    partnerCode: String(raw.partnerCode || "").trim().toUpperCase(),
    gid: rawGid || (gameId > 0 ? String(gameId) : ""),
    gameId,
    gameCode: String(raw.gameCode || "").trim(),
    username: String(raw.username || "").trim(),
    userId: String(raw.userId || "").trim(),
    currency: normalizeCurrencyCode(raw.currency),
    lang: normalizeLanguageCode(raw.lang),
    extsess: String(raw.extsess || "").trim(),
    closeUrl: sanitizeCloseUrl(raw.closeUrl),
  };
}

function readStoredLaunchContext() {
  try {
    const raw = window.sessionStorage.getItem(PLATFORM_LAUNCH_CONTEXT_STORAGE_KEY);
    if (!raw) {
      return null;
    }
    return normalizeStoredContext(JSON.parse(raw));
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

function parseLaunchContextFromSearch(search) {
  const params = new URLSearchParams(search || "");
  const token = (params.get("token") || params.get("id") || "").trim();
  if (!token) {
    return null;
  }

  const rawGid = (params.get("gid") || params.get("game_id") || "").trim();
  const gameId = resolvePlatformGameId(rawGid);

  return {
    token,
    partnerCode: (params.get("partner_code") || "").trim().toUpperCase(),
    gid: rawGid || (gameId > 0 ? String(gameId) : ""),
    gameId,
    gameCode: (params.get("game_code") || "").trim(),
    username: (params.get("username") || "").trim(),
    userId: (params.get("user_id") || "").trim(),
    currency: normalizeCurrencyCode(params.get("currency")),
    lang: normalizeLanguageCode(params.get("lang")),
    extsess: (params.get("extsess") || "").trim(),
    closeUrl: sanitizeCloseUrl(params.get("close_url")),
  };
}

export function withLaunchQuery(path) {
  if (typeof window === "undefined") {
    return path;
  }

  const currentParams = new URLSearchParams(window.location.search);
  const [pathname, existingQuery = ""] = String(path || "/").split("?");
  const targetParams = new URLSearchParams(existingQuery);

  const hasNewToken = Boolean(currentParams.get("token")?.trim());
  if (hasNewToken) {
    LAUNCH_QUERY_PARAMS.forEach((key) => {
      const value = currentParams.get(key)?.trim();
      if (value && !targetParams.has(key)) {
        targetParams.set(key, value);
      }
    });
  } else {
    const legacyToken = currentParams.get("id")?.trim();
    if (legacyToken) {
      if (!targetParams.has("id")) {
        targetParams.set("id", legacyToken);
      }
      const gameId = resolvePlatformGameId(currentParams.get("game_id"));
      if (gameId > 0 && !targetParams.has("game_id")) {
        targetParams.set("game_id", String(gameId));
      }
    } else {
      const stored = readStoredLaunchContext();
      if (stored?.token) {
        if (!targetParams.has("token")) targetParams.set("token", stored.token);
        if (stored.partnerCode && !targetParams.has("partner_code")) targetParams.set("partner_code", stored.partnerCode);
        if (stored.gid && !targetParams.has("gid")) targetParams.set("gid", stored.gid);
        if (stored.gameCode && !targetParams.has("game_code")) targetParams.set("game_code", stored.gameCode);
        if (stored.username && !targetParams.has("username")) targetParams.set("username", stored.username);
        if (stored.userId && !targetParams.has("user_id")) targetParams.set("user_id", stored.userId);
        if (stored.currency && !targetParams.has("currency")) targetParams.set("currency", stored.currency);
        if (stored.lang && !targetParams.has("lang")) targetParams.set("lang", stored.lang);
        if (stored.extsess && !targetParams.has("extsess")) targetParams.set("extsess", stored.extsess);
        if (stored.closeUrl && !targetParams.has("close_url")) targetParams.set("close_url", stored.closeUrl);
      }
    }
  }

  const queryString = targetParams.toString();
  return queryString ? `${pathname}?${queryString}` : pathname;
}

export function exitGame(closeUrl) {
  if (typeof window === "undefined") {
    return;
  }
  const safeUrl = sanitizeCloseUrl(closeUrl);
  if (!safeUrl) {
    window.close();
    return;
  }
  try {
    (window.top || window).location.href = safeUrl;
  } catch {
    window.location.href = safeUrl;
  }
}

export function isSessionExpiredError(errorOrMessage, errorCode = "") {
  const code = String(errorCode || errorOrMessage?.code || "").trim().toLowerCase();
  if (code === "platform_session_expired") {
    return true;
  }
  const message = String(
    typeof errorOrMessage === "string"
      ? errorOrMessage
      : errorOrMessage?.message || "",
  );
  return /\bERR-001[123]\b/i.test(message);
}

export function getPlatformLaunchContext() {
  if (typeof window === "undefined") {
    return null;
  }

  const fromUrl = parseLaunchContextFromSearch(window.location.search);
  const stored = readStoredLaunchContext();

  if (!fromUrl) {
    return stored;
  }

  if (stored?.token && stored.token !== fromUrl.token) {
    try {
      window.sessionStorage.removeItem(PLATFORM_PROFILE_STORAGE_KEY);
    } catch {}
  }

  return persistLaunchContext(fromUrl);
}
