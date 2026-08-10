const crypto = require("crypto");
const config = require("../config/env");

const sessions = new Map();

function createToken() {
  return crypto.randomBytes(32).toString("hex");
}

function getBearerToken(request) {
  const header = request.headers.authorization || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : "";
}

function requireAdmin(request, response, next) {
  const token = getBearerToken(request);
  const session = sessions.get(token);
  if (!token || !session) {
    return response.status(401).json({
      error: { code: "unauthorized", message: "Admin authentication required." },
    });
  }
  if (session.expiresAt < Date.now()) {
    sessions.delete(token);
    return response.status(401).json({
      error: { code: "unauthorized", message: "Admin session expired." },
    });
  }
  request.admin = session.admin;
  request.adminToken = token;
  return next();
}

function optionalAdmin(request, _response, next) {
  const token = getBearerToken(request);
  const session = sessions.get(token);
  if (token && session && session.expiresAt >= Date.now()) {
    request.admin = session.admin;
    request.adminToken = token;
  }
  next();
}

function login(email, password) {
  const normalizedEmail = String(email || "").trim().toLowerCase();
  const normalizedPassword = String(password || "");

  if (
    normalizedEmail !== config.adminEmail.toLowerCase() ||
    normalizedPassword !== config.adminPassword
  ) {
    const error = new Error("Invalid email or password.");
    error.statusCode = 401;
    throw error;
  }

  const token = createToken();
  const admin = {
    email: config.adminEmail,
    displayName: config.adminDisplayName,
  };
  sessions.set(token, {
    admin,
    expiresAt: Date.now() + config.adminSessionTtlMs,
  });

  return { token, admin };
}

function logout(token) {
  if (token) {
    sessions.delete(token);
  }
}

function getSession(token) {
  const session = sessions.get(token);
  if (!session || session.expiresAt < Date.now()) {
    if (token) {
      sessions.delete(token);
    }
    return null;
  }
  return { admin: session.admin };
}

module.exports = {
  requireAdmin,
  optionalAdmin,
  login,
  logout,
  getSession,
  getBearerToken,
};
