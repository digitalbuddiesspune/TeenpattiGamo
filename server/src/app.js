import cors from "cors";
import express from "express";
import config from "./config/env.js";
import { connectMongo, getMongoClient } from "./config/mongo.js";
import teenPattiRoundsRouter from "./routes/teenPattiRounds.js";
import teenPattiRoundDetailRouter from "./routes/teenPattiRoundDetail.js";
import teenPattiEarningsSummaryRouter from "./routes/teenPattiEarningsSummary.js";
import adminAuthRouter from "./routes/adminAuth.js";
import adminProfitLossRouter from "./routes/adminProfitLoss.js";

const app = express();

app.use(cors({ origin: config.corsOrigin }));
app.use(express.json());

app.get("/health", async (_request, response) => {
  const health = {
    status: "ok",
    api: "ok",
    mongo: "unknown",
  };

  try {
    await connectMongo();
    await getMongoClient().db("admin").command({ ping: 1 });
    health.mongo = "ok";
  } catch (error) {
    health.status = "degraded";
    health.mongo = "error";
    health.mongoError = error.message;
  }

  response.status(health.status === "ok" ? 200 : 503).json(health);
});

app.use("/api/teen-patti", teenPattiRoundsRouter);
app.use("/api/teen-patti", teenPattiRoundDetailRouter);
app.use("/api/teen-patti", teenPattiEarningsSummaryRouter);

app.use("/api/v1/admin/auth", adminAuthRouter);
app.use("/api/v1/admin/profit-loss", adminProfitLossRouter);

app.use((request, response) => {
  response.status(404).json({
    error: {
      code: "not_found",
      message: `Route not found: ${request.method} ${request.originalUrl}`,
    },
  });
});

app.use((error, _request, response, _next) => {
  const statusCode = error.statusCode || 500;
  response.status(statusCode).json({
    error: {
      code: statusCode === 400 ? "bad_request" : statusCode === 401 ? "unauthorized" : statusCode === 404 ? "not_found" : "internal_server_error",
      message: error.message || "Unexpected server error.",
    },
  });
});

export default app;
