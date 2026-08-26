import { connectMongo } from "../config/mongo.js";

const ROUND_COLLECTION = "round_history";
const WALLET_COLLECTION = "wallet_transactions";

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  throw error;
}

function normalizeParams(query) {
  const values = {
    userId: String(query.user_id || "").trim(),
    operatorId: String(query.operator_id || "").trim(),
    lobbyId: String(query.lobby_id || "").trim(),
  };

  if (!values.userId || !values.operatorId || !values.lobbyId) {
    badRequest("Teen Patti requires user_id, operator_id, and lobby_id.");
  }
  return values;
}

async function findLatestRound(query, database) {
  const { userId, operatorId, lobbyId } = normalizeParams(query);
  const db = database || (await connectMongo());
  const walletFilter = {
    platformUserId: userId,
    status: { $in: ["succeeded", "applied"] },
    $or: [
      { platformOperatorId: operatorId },
      { "requestPayload.operator_id": operatorId },
      { "requestPayload.operatorId": operatorId },
    ],
  };
  const playerIds = await db.collection(WALLET_COLLECTION).distinct("playerId", walletFilter);

  if (playerIds.length === 0) {
    const error = new Error("No Teen Patti player was found for this user and operator.");
    error.statusCode = 404;
    throw error;
  }

  const round = await db.collection(ROUND_COLLECTION).findOne(
    {
      aggregateId: lobbyId,
      "participants.id": { $in: playerIds },
      settledAt: { $ne: null },
    },
    { sort: { settledAt: -1, _id: -1 } },
  );

  if (!round) {
    const error = new Error("No completed Teen Patti round was found for this user and lobby.");
    error.statusCode = 404;
    throw error;
  }

  return {
    round,
    viewerPlayerIds: playerIds,
  };
}

export { findLatestRound, normalizeParams };
