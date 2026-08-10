const { connectMongo } = require("../config/mongo");

const ROUND_COLLECTION = "round_history";
const WALLET_COLLECTION = "wallet_transactions";
const CURRENCY = "INR";

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  throw error;
}

function integerParam(value, fallback) {
  if (value === undefined || value === null || value === "") {
    return fallback;
  }
  const normalized = String(value).trim();
  if (!/^\d+$/.test(normalized)) {
    return Number.NaN;
  }
  return Number.parseInt(normalized, 10);
}

function parseDateParam(value, paramName, endOfDay) {
  if (value === undefined || value === null || value === "") {
    return null;
  }

  const normalized = String(value).trim();
  if (!normalized) {
    return null;
  }

  const dateOnlyMatch = /^\d{4}-\d{2}-\d{2}$/.test(normalized);
  const date = new Date(dateOnlyMatch ? `${normalized}T00:00:00.000Z` : normalized);
  if (Number.isNaN(date.getTime())) {
    badRequest(`${paramName} must be a valid ISO timestamp or YYYY-MM-DD date.`);
  }
  if (dateOnlyMatch && date.toISOString().slice(0, 10) !== normalized) {
    badRequest(`${paramName} must be a valid ISO timestamp or YYYY-MM-DD date.`);
  }
  if (dateOnlyMatch && endOfDay) {
    date.setUTCHours(23, 59, 59, 999);
  }
  return date.toISOString();
}

function parseListFilters(query = {}) {
  const page = integerParam(query.page, 1);
  const limit = integerParam(query.limit ?? query.pageSize, 20);
  const operatorId = String(query.operatorId || query.operator_id || "").trim() || "all";
  const variant = String(query.variant || query.players || "").trim().toLowerCase();
  const dateFrom = parseDateParam(query.dateFrom || query.from, "dateFrom", false);
  const dateTo = parseDateParam(query.dateTo || query.to, "dateTo", true);

  if (!Number.isInteger(page) || page < 1) {
    badRequest("page must be an integer greater than or equal to 1.");
  }
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    badRequest("limit must be an integer between 1 and 100.");
  }
  if (dateFrom && dateTo && dateFrom > dateTo) {
    badRequest("dateFrom must be earlier than or equal to dateTo.");
  }

  const knownVariants = new Set(["classic", "ak47", "muflis", "flipper", "jhandu", "public", "private"]);
  const variantFilter = knownVariants.has(variant) ? variant : null;

  return { page, limit, operatorId, variantFilter, dateFrom, dateTo };
}

function operatorLabel(operatorId) {
  if (!operatorId || operatorId === "guest") {
    return "Direct / Guest";
  }
  return operatorId;
}

function modeLabel(document) {
  const variant = document.variantId || "classic";
  const type = document.aggregateType || "";
  if (type.includes("private")) {
    return `${variant} · private`;
  }
  if (type.includes("public")) {
    return `${variant} · public`;
  }
  return variant;
}

function entryFeeForRound(document) {
  const participants = Array.isArray(document.participants) ? document.participants : [];
  if (participants.length === 0) {
    return 0;
  }
  const bootTotal = Number(document.bootContributionTotal || 0);
  if (bootTotal > 0) {
    return Math.round(bootTotal / participants.length);
  }
  const contributions = participants.map((p) => Number(p.totalContributed || 0)).filter((n) => n > 0);
  if (contributions.length === 0) {
    return 0;
  }
  return Math.min(...contributions);
}

async function loadOperatorMaps(db, roundIds) {
  if (!roundIds.length) {
    return { roundOperators: new Map(), playerOperators: new Map() };
  }

  const txs = await db
    .collection(WALLET_COLLECTION)
    .find(
      {
        roundId: { $in: roundIds },
        status: { $in: ["succeeded", "applied"] },
      },
      {
        projection: {
          roundId: 1,
          playerId: 1,
          platformOperatorId: 1,
          "requestPayload.operator_id": 1,
          "requestPayload.operatorId": 1,
        },
      },
    )
    .toArray();

  const roundOperators = new Map();
  const playerOperators = new Map();

  for (const tx of txs) {
    const operatorId =
      tx.platformOperatorId ||
      tx.requestPayload?.operator_id ||
      tx.requestPayload?.operatorId ||
      "guest";
    if (tx.roundId) {
      if (!roundOperators.has(tx.roundId)) {
        roundOperators.set(tx.roundId, new Set());
      }
      roundOperators.get(tx.roundId).add(operatorId);
    }
    if (tx.playerId) {
      playerOperators.set(tx.playerId, operatorId);
    }
  }

  return { roundOperators, playerOperators };
}

async function roundIdsForOperator(db, operatorId) {
  if (!operatorId || operatorId === "all") {
    return null;
  }
  return db.collection(WALLET_COLLECTION).distinct("roundId", {
    status: { $in: ["succeeded", "applied"] },
    $or: [
      { platformOperatorId: operatorId },
      { "requestPayload.operator_id": operatorId },
      { "requestPayload.operatorId": operatorId },
    ],
  });
}

function buildRoundMatch(filters, operatorRoundIds) {
  const match = { settledAt: { $ne: null } };

  if (filters.dateFrom || filters.dateTo) {
    match.settledAt = { $ne: null };
    if (filters.dateFrom) {
      match.settledAt.$gte = filters.dateFrom;
    }
    if (filters.dateTo) {
      match.settledAt.$lte = filters.dateTo;
    }
  }

  if (filters.variantFilter === "public") {
    match.aggregateType = { $regex: /public/i };
  } else if (filters.variantFilter === "private") {
    match.aggregateType = { $regex: /private/i };
  } else if (filters.variantFilter) {
    match.variantId = filters.variantFilter;
  }

  if (operatorRoundIds) {
    if (operatorRoundIds.length === 0) {
      match._id = { $in: [] };
    } else {
      match._id = { $in: operatorRoundIds };
    }
  }

  return match;
}

function mapPlayer(participant, document, playerOperators) {
  const winnerId = document.winner?.id || "";
  const isWinner = participant.id === winnerId;
  const isBot = Boolean(participant.isBot);
  const betAmount = Number(participant.totalContributed || 0);
  const winAmount = isWinner ? Number(document.payout || 0) : 0;
  const realCount = (document.participants || []).filter((p) => !p.isBot).length || 1;
  const bootShare = isBot ? 0 : Math.round(Number(document.actualBootCommission || 0) / realCount);
  const winShare = isWinner && !isBot ? Number(document.actualWinCommission || 0) : 0;
  const platformFee = bootShare + winShare;
  const operatorId = isBot ? null : playerOperators.get(participant.id) || "guest";

  return {
    userId: participant.id || "",
    displayName: participant.name || participant.id || "Player",
    operatorId,
    operatorLabel: operatorLabel(operatorId),
    isBot,
    isAbandoned: Boolean(participant.packed),
    packed: Boolean(participant.packed),
    seen: Boolean(participant.seen),
    betAmount,
    platformFee,
    winAmount,
    profitLoss: winAmount - betAmount,
    cards: Array.isArray(participant.cards) ? participant.cards : [],
    publicCards: Array.isArray(participant.publicCards) ? participant.publicCards : [],
    reserveCards: Array.isArray(participant.reserveCards) ? participant.reserveCards : [],
    handLabel: participant.handLabel || null,
  };
}

function mapGame(document, roundOperators, playerOperators) {
  const participants = Array.isArray(document.participants) ? document.participants : [];
  const realPlayers = participants.filter((p) => !p.isBot);
  const botPlayers = participants.filter((p) => p.isBot);
  const roundId = String(document._id || document.id || "");
  const operators = Array.from(roundOperators.get(roundId) || []);
  if (operators.length === 0 && realPlayers.length > 0) {
    operators.push("guest");
  }

  const winnerParticipant = participants.find((p) => p.id === document.winner?.id);
  const winnerIsBot = Boolean(winnerParticipant?.isBot);
  const platformProfit = Number(document.actualCasinoIncomeTotal || 0);
  const totalRealIncome = Number(document.realPlayerContributionTotal || 0);
  const winnerPayout = winnerIsBot ? 0 : Number(document.payout || 0);

  return {
    roundId,
    roomCode: document.aggregateId || roundId.slice(-8),
    mode: modeLabel(document),
    variantId: document.variantId || null,
    aggregateType: document.aggregateType || null,
    aggregateId: document.aggregateId || null,
    currency: CURRENCY,
    playerCount: participants.length,
    realPlayerCount: realPlayers.length,
    botPlayerCount: botPlayers.length,
    entryFee: entryFeeForRound(document),
    totalRealIncome,
    displayPotAmount: Number(document.potAmount || 0),
    winnerPayout,
    platformProfit,
    completedAt: document.settledAt || null,
    startedAt: document.startedAt || null,
    operatorIds: operators,
    winner: document.winner
      ? {
          displayName: document.winner.name || document.winner.id || "Winner",
          userId: document.winner.id || "",
          isHouse: winnerIsBot || !document.winner.id,
          winningHand: document.winner.winningHand || null,
        }
      : null,
    players: participants.map((participant) => mapPlayer(participant, document, playerOperators)),
    actionLog: Array.isArray(document.actionLog) ? document.actionLog : [],
    actualBootCommission: Number(document.actualBootCommission || 0),
    actualWinCommission: Number(document.actualWinCommission || 0),
    dealerTip: Number(document.dealerTip || 0),
    reason: document.reason || null,
  };
}

function paginationMeta(page, limit, totalItems) {
  const totalPages = Math.max(1, Math.ceil(totalItems / limit));
  return {
    page,
    limit,
    totalItems,
    totalPages,
    hasPreviousPage: page > 1,
    hasNextPage: page < totalPages,
  };
}

async function listGames(query) {
  const filters = parseListFilters(query);
  const db = await connectMongo();
  const operatorRoundIds = await roundIdsForOperator(db, filters.operatorId);
  const match = buildRoundMatch(filters, operatorRoundIds);

  const collection = db.collection(ROUND_COLLECTION);
  const totalItems = await collection.countDocuments(match);
  const rows = await collection
    .find(match)
    .sort({ settledAt: -1, _id: -1 })
    .skip((filters.page - 1) * filters.limit)
    .limit(filters.limit)
    .toArray();

  const roundIds = rows.map((row) => String(row._id || row.id));
  const { roundOperators, playerOperators } = await loadOperatorMaps(db, roundIds);
  const data = rows.map((row) => mapGame(row, roundOperators, playerOperators));

  return {
    data,
    pagination: paginationMeta(filters.page, filters.limit, totalItems),
  };
}

async function getGameById(roundId) {
  const id = String(roundId || "").trim();
  if (!id) {
    badRequest("roundId is required.");
  }

  const db = await connectMongo();
  const document = await db.collection(ROUND_COLLECTION).findOne({
    $or: [{ _id: id }, { id }],
    settledAt: { $ne: null },
  });

  if (!document) {
    const error = new Error("Round not found.");
    error.statusCode = 404;
    throw error;
  }

  const { roundOperators, playerOperators } = await loadOperatorMaps(db, [id]);
  return mapGame(document, roundOperators, playerOperators);
}

async function deleteGame(roundId) {
  const id = String(roundId || "").trim();
  if (!id) {
    badRequest("roundId is required.");
  }

  const db = await connectMongo();
  const result = await db.collection(ROUND_COLLECTION).deleteOne({
    $or: [{ _id: id }, { id }],
  });

  if (result.deletedCount === 0) {
    const error = new Error("Round not found.");
    error.statusCode = 404;
    throw error;
  }

  return { deleted: true, roundId: id };
}

async function getSummary(query) {
  const filters = parseListFilters({ ...query, page: 1, limit: 20 });
  const db = await connectMongo();
  const operatorRoundIds = await roundIdsForOperator(db, filters.operatorId);
  const match = buildRoundMatch(filters, operatorRoundIds);

  const rounds = await db
    .collection(ROUND_COLLECTION)
    .find(match, {
      projection: {
        participants: 1,
        realPlayerContributionTotal: 1,
        actualCasinoIncomeTotal: 1,
        payout: 1,
        winner: 1,
        actualBootCommission: 1,
        actualWinCommission: 1,
      },
    })
    .toArray();

  const roundIds = rounds.map((row) => String(row._id || row.id));
  const { roundOperators, playerOperators } = await loadOperatorMaps(db, roundIds);

  let totalRealPlayers = 0;
  let totalBotPlayers = 0;
  let totalRealIncome = 0;
  let totalPlatformProfit = 0;
  let totalWinnerPayout = 0;
  let botMatches = 0;
  let botMatchWins = 0;
  let botMatchLosses = 0;

  const byOperator = new Map();

  function ensureOperator(operatorId) {
    if (!byOperator.has(operatorId)) {
      byOperator.set(operatorId, {
        operatorId,
        label: operatorLabel(operatorId),
        uniqueUsers: new Set(),
        totalGames: 0,
        totalSeats: 0,
        totalRealIncome: 0,
        totalPlatformProfit: 0,
      });
    }
    return byOperator.get(operatorId);
  }

  for (const round of rounds) {
    const participants = Array.isArray(round.participants) ? round.participants : [];
    const realPlayers = participants.filter((p) => !p.isBot);
    const botPlayers = participants.filter((p) => p.isBot);
    const roundId = String(round._id || round.id);
    const operators = Array.from(roundOperators.get(roundId) || []);
    if (operators.length === 0 && realPlayers.length > 0) {
      operators.push("guest");
    }

    const winner = participants.find((p) => p.id === round.winner?.id);
    const winnerIsBot = Boolean(winner?.isBot);
    const realIncome = Number(round.realPlayerContributionTotal || 0);
    const platformProfit = Number(round.actualCasinoIncomeTotal || 0);
    const payout = winnerIsBot ? 0 : Number(round.payout || 0);

    totalRealPlayers += realPlayers.length;
    totalBotPlayers += botPlayers.length;
    totalRealIncome += realIncome;
    totalPlatformProfit += platformProfit;
    totalWinnerPayout += payout;

    if (botPlayers.length > 0) {
      botMatches += 1;
      if (winnerIsBot || !round.winner?.id) {
        botMatchLosses += 1;
      } else {
        botMatchWins += 1;
      }
    }

    for (const operatorId of operators) {
      const bucket = ensureOperator(operatorId);
      bucket.totalGames += 1;
      bucket.totalSeats += realPlayers.length;
      bucket.totalRealIncome += realIncome;
      bucket.totalPlatformProfit += platformProfit;
      for (const player of realPlayers) {
        const playerOp = playerOperators.get(player.id) || operatorId;
        if (playerOp === operatorId || operators.length === 1) {
          bucket.uniqueUsers.add(player.id);
        }
      }
    }
  }

  return {
    currency: CURRENCY,
    totalGames: rounds.length,
    totalRealPlayers,
    totalBotPlayers,
    totalRealIncome,
    totalPlatformProfit,
    totalWinnerPayout,
    platformFeePerPlayer: null,
    botMatches,
    botMatchWins,
    botMatchLosses,
    byOperator: Array.from(byOperator.values())
      .map((item) => ({
        operatorId: item.operatorId,
        label: item.label,
        uniqueUsers: item.uniqueUsers.size,
        totalGames: item.totalGames,
        totalSeats: item.totalSeats,
        totalRealIncome: item.totalRealIncome,
        totalPlatformProfit: item.totalPlatformProfit,
      }))
      .sort((a, b) => b.totalPlatformProfit - a.totalPlatformProfit),
  };
}

async function listUsers(query) {
  const filters = parseListFilters(query);
  const db = await connectMongo();
  const operatorRoundIds = await roundIdsForOperator(db, filters.operatorId);
  const match = buildRoundMatch(filters, operatorRoundIds);

  const rounds = await db
    .collection(ROUND_COLLECTION)
    .find(match, {
      projection: {
        participants: 1,
        winner: 1,
        payout: 1,
        actualBootCommission: 1,
        actualWinCommission: 1,
      },
    })
    .toArray();

  const roundIds = rounds.map((row) => String(row._id || row.id));
  const { playerOperators } = await loadOperatorMaps(db, roundIds);
  const users = new Map();

  for (const round of rounds) {
    const participants = Array.isArray(round.participants) ? round.participants : [];
    const realPlayers = participants.filter((p) => !p.isBot);
    const realCount = realPlayers.length || 1;
    const bootShare = Math.round(Number(round.actualBootCommission || 0) / realCount);
    const winnerId = round.winner?.id || "";

    for (const participant of realPlayers) {
      const operatorId = playerOperators.get(participant.id) || "guest";
      if (filters.operatorId !== "all" && operatorId !== filters.operatorId) {
        continue;
      }

      const key = `${operatorId}::${participant.id}`;
      if (!users.has(key)) {
        users.set(key, {
          userId: participant.id,
          displayName: participant.name || participant.id,
          operatorId,
          operatorLabel: operatorLabel(operatorId),
          gamesPlayed: 0,
          wins: 0,
          losses: 0,
          totalBet: 0,
          totalWin: 0,
          totalPlatformFee: 0,
          profitLoss: 0,
        });
      }

      const row = users.get(key);
      const betAmount = Number(participant.totalContributed || 0);
      const isWinner = participant.id === winnerId;
      const winAmount = isWinner ? Number(round.payout || 0) : 0;
      const platformFee = bootShare + (isWinner ? Number(round.actualWinCommission || 0) : 0);

      row.gamesPlayed += 1;
      row.totalBet += betAmount;
      row.totalWin += winAmount;
      row.totalPlatformFee += platformFee;
      row.profitLoss += winAmount - betAmount;
      if (isWinner) {
        row.wins += 1;
      } else {
        row.losses += 1;
      }
      if (participant.name) {
        row.displayName = participant.name;
      }
    }
  }

  const allUsers = Array.from(users.values()).sort((a, b) => b.gamesPlayed - a.gamesPlayed);
  const totalItems = allUsers.length;
  const start = (filters.page - 1) * filters.limit;
  const data = allUsers.slice(start, start + filters.limit);

  return {
    data,
    pagination: paginationMeta(filters.page, filters.limit, totalItems),
  };
}

module.exports = {
  listGames,
  getGameById,
  deleteGame,
  getSummary,
  listUsers,
  parseListFilters,
};
