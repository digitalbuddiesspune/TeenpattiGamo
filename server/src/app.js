const cors = require("cors");
const express = require("express");
const config = require("./config/env");
const { connectMongo, getMongoClient } = require("./config/mongo");
const teenPattiRoundsRouter = require("./routes/teenPattiRounds");
const teenPattiRoundDetailRouter = require("./routes/teenPattiRoundDetail");
const teenPattiEarningsSummaryRouter = require("./routes/teenPattiEarningsSummary");

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
      code: statusCode === 400 ? "bad_request" : "internal_server_error",
      message: error.message || "Unexpected server error.",
    },
  });
});

module.exports = app;
