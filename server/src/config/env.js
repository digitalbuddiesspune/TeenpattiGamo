import dotenv from "dotenv";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

dotenv.config();
if (!process.env.MONGODB_URI) {
  // Pull DB settings from the game backend env without adopting its PORT.
  const gameEnv = dotenv.config({
    path: path.resolve(__dirname, "../../../serverJavaNew/.env"),
    override: false,
  });
  const shared = gameEnv.parsed || {};
  process.env.MONGODB_URI ||= shared.MONGODB_URI;
  process.env.MONGODB_DB_NAME ||= shared.MONGODB_DB_NAME;
}

const toInteger = (value, fallback) => {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const config = {
  port: toInteger(process.env.PORT, 4200),
  corsOrigin: process.env.CORS_ORIGIN || "*",
  mongoUri: process.env.MONGODB_URI,
  mongoDbName: process.env.MONGODB_DB_NAME || "teen_patti_casino",
  adminEmail: process.env.ADMIN_EMAIL || "admin@gmail.com",
  adminPassword: process.env.ADMIN_PASSWORD || "admin123",
  adminDisplayName: process.env.ADMIN_DISPLAY_NAME || "Teen Patti Admin",
  adminSessionTtlMs: toInteger(process.env.ADMIN_SESSION_TTL_MS, 1000 * 60 * 60 * 12),
};

if (!config.mongoUri) {
  throw new Error("MONGODB_URI is required.");
}

export default config;
