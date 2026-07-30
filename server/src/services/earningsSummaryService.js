const { connectMongo } = require("../config/mongo");

const ROUND_COLLECTION = "round_history";
const WALLET_COLLECTION = "wallet_transactions";

function badRequest(message) {
  const error = new Error(message);
  error.statusCode = 400;
  throw error;
}

function parseDateParam(value, paramName, endOfDay) {
  if (value === undefined) {
    return null;
  }

  const normalized = String(value).trim();
  if (!normalized) {
    badRequest(`${paramName} must be a valid ISO timestamp or YYYY-MM-DD date.`);
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

function normalizeParams(query) {
  const filters = {
    operatorId: String(query.operator_id || "").trim() || null,
    from: parseDateParam(query.from, "from", false),
    to: parseDateParam(query.to, "to", true),
  };

  if (filters.from && filters.to && filters.from > filters.to) {
    badRequest("from must be earlier than or equal to to.");
  }

  return filters;
}

function zeroSummary(filters) {
  return {
    data: {
      currency: "INR",
      totalEarnings: 0,
      roundCount: 0,
      realPlayerContributionTotal: 0,
      botContributionTotal: 0,
      payoutTotal: 0,
      actualBootCommissionTotal: 0,
      actualWinCommissionTotal: 0,
      dealerTipTotal: 0,
      filters,
    },
  };
}

function sumField(fieldName) {
  return { $sum: { $ifNull: [`$${fieldName}`, 0] } };
}

async function roundIdsForOperator(db, operatorId) {
  return db.collection(WALLET_COLLECTION).distinct("roundId", {
    status: { $in: ["succeeded", "applied"] },
    $or: [
      { platformOperatorId: operatorId },
      { "requestPayload.operator_id": operatorId },
      { "requestPayload.operatorId": operatorId },
    ],
  });
}

async function getTeenPattiEarningsSummary(query, database) {
  const filters = normalizeParams(query);
  const db = database || (await connectMongo());
  const match = {
    settledAt: { $ne: null },
  };

  if (filters.from || filters.to) {
    match.settledAt = { ...match.settledAt };
    if (filters.from) {
      match.settledAt.$gte = filters.from;
    }
    if (filters.to) {
      match.settledAt.$lte = filters.to;
    }
  }

  if (filters.operatorId) {
    const roundIds = await roundIdsForOperator(db, filters.operatorId);
    if (roundIds.length === 0) {
      return zeroSummary(filters);
    }
    match._id = { $in: roundIds };
  }

  const [summary] = await db
    .collection(ROUND_COLLECTION)
    .aggregate([
      { $match: match },
      {
        $group: {
          _id: null,
          totalEarnings: sumField("actualCasinoIncomeTotal"),
          roundCount: { $sum: 1 },
          realPlayerContributionTotal: sumField("realPlayerContributionTotal"),
          botContributionTotal: sumField("botContributionTotal"),
          payoutTotal: sumField("payout"),
          actualBootCommissionTotal: sumField("actualBootCommission"),
          actualWinCommissionTotal: sumField("actualWinCommission"),
          dealerTipTotal: sumField("dealerTip"),
        },
      },
      { $project: { _id: 0 } },
    ])
    .toArray();

  if (!summary) {
    return zeroSummary(filters);
  }

  return {
    data: {
      currency: "INR",
      totalEarnings: Number(summary.totalEarnings || 0),
      roundCount: Number(summary.roundCount || 0),
      realPlayerContributionTotal: Number(summary.realPlayerContributionTotal || 0),
      botContributionTotal: Number(summary.botContributionTotal || 0),
      payoutTotal: Number(summary.payoutTotal || 0),
      actualBootCommissionTotal: Number(summary.actualBootCommissionTotal || 0),
      actualWinCommissionTotal: Number(summary.actualWinCommissionTotal || 0),
      dealerTipTotal: Number(summary.dealerTipTotal || 0),
      filters,
    },
  };
}

module.exports = {
  getTeenPattiEarningsSummary,
  normalizeParams,
};
