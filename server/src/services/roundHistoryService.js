import { connectMongo } from "../config/mongo.js";

const COLLECTION_NAME = "round_history";
const GAME_NAME = "teen-patti";

function integerQueryParam(value, fallback) {
  if (value === undefined) {
    return fallback;
  }

  const normalized = String(value).trim();
  if (!/^\d+$/.test(normalized)) {
    return Number.NaN;
  }

  return Number.parseInt(normalized, 10);
}

function parsePagination(query) {
  const page = integerQueryParam(query.page, 1);
  const pageSize = integerQueryParam(query.pageSize, 20);

  if (!Number.isInteger(page) || page < 1) {
    const error = new Error("page must be an integer greater than or equal to 1.");
    error.statusCode = 400;
    throw error;
  }

  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 100) {
    const error = new Error("pageSize must be an integer between 1 and 100.");
    error.statusCode = 400;
    throw error;
  }

  return { page, pageSize };
}

function mapParticipant(participant) {
  return {
    playerId: participant.id || "",
    name: participant.name || "",
    isBot: Boolean(participant.isBot),
    totalBetAmount: Number(participant.totalContributed || 0),
    packed: Boolean(participant.packed),
    seen: Boolean(participant.seen),
  };
}

function mapBet(action) {
  return {
    id: action.id || "",
    playerId: action.playerId || "",
    actionType: action.actionType || "",
    amount: Number(action.amount || 0),
    timestamp: action.timestamp || null,
    note: action.note || null,
  };
}

function mapRound(document) {
  return {
    roundId: String(document._id || document.id || ""),
    game: GAME_NAME,
    variantId: document.variantId || null,
    aggregateType: document.aggregateType || null,
    aggregateId: document.aggregateId || null,
    startedAt: document.startedAt || null,
    settledAt: document.settledAt || null,
    potAmount: Number(document.potAmount || 0),
    participants: Array.isArray(document.participants) ? document.participants.map(mapParticipant) : [],
    winner: document.winner
      ? {
          playerId: document.winner.id || "",
          name: document.winner.name || "",
          winningHand: document.winner.winningHand || null,
          wonAmount: Number(document.payout || 0),
          winnerReceivableBeforeTip: Number(document.winnerReceivableBeforeTip || 0),
          dealerTip: Number(document.dealerTip || 0),
        }
      : null,
    bets: Array.isArray(document.actionLog) ? document.actionLog.map(mapBet) : [],
  };
}

async function listTeenPattiRounds(query) {
  const { page, pageSize } = parsePagination(query);
  const db = await connectMongo();
  const skip = (page - 1) * pageSize;
  const rows = await db
    .collection(COLLECTION_NAME)
    .find({})
    .sort({ settledAt: -1, _id: -1 })
    .skip(skip)
    .limit(pageSize + 1)
    .toArray();

  const hasMore = rows.length > pageSize;
  const items = rows.slice(0, pageSize).map(mapRound);
  const payload = {
    data: items,
    pagination: {
      page,
      pageSize,
      count: items.length,
      hasMore,
      nextPage: hasMore ? page + 1 : null,
    },
  };

  return payload;
}

export { listTeenPattiRounds, parsePagination };
