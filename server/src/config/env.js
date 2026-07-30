const dotenv = require("dotenv");
const path = require("path");

dotenv.config();
if (!process.env.MONGODB_URI) {
  dotenv.config({ path: path.resolve(__dirname, "../../../serverJavaNew/.env") });
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
};

if (!config.mongoUri) {
  throw new Error("MONGODB_URI is required.");
}

module.exports = config;
