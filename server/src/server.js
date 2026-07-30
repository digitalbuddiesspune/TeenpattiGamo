const app = require("./app");
const config = require("./config/env");
const { closeMongo, connectMongo } = require("./config/mongo");

let server;

async function start() {
  await connectMongo();

  server = app.listen(config.port, () => {
    console.log(`Teen Patti round history API listening on port ${config.port}`);
  });
}

async function shutdown(signal) {
  console.log(`${signal} received. Shutting down...`);

  if (server) {
    await new Promise((resolve) => server.close(resolve));
  }

  await Promise.allSettled([closeMongo()]);
  process.exit(0);
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

start().catch(async (error) => {
  console.error("Failed to start Teen Patti round history API:", error);
  await Promise.allSettled([closeMongo()]);
  process.exit(1);
});
