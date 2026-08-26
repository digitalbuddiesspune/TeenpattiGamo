import "./setup-env.js";
import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";
import express from "express";
import {
  getTeenPattiEarningsSummary,
  normalizeParams,
} from "../src/services/earningsSummaryService.js";
import { createEarningsSummaryRouter } from "../src/routes/teenPattiEarningsSummary.js";

const rounds = [
  {
    _id: "round-1",
    settledAt: "2026-07-01T10:00:00.000Z",
    actualCasinoIncomeTotal: -710,
    casinoCommissionTotal: 290,
    realPlayerContributionTotal: 1000,
    botContributionTotal: 1000,
    payout: 1710,
    actualBootCommission: 50,
    actualWinCommission: 95,
    dealerTip: 0,
  },
  {
    _id: "round-2",
    settledAt: "2026-07-10T12:00:00.000Z",
    actualCasinoIncomeTotal: 6000,
    casinoCommissionTotal: 900,
    realPlayerContributionTotal: 6000,
    botContributionTotal: 1000,
    payout: 0,
    actualBootCommission: 100,
    actualWinCommission: 0,
    dealerTip: 0,
  },
  {
    _id: "round-3",
    settledAt: "2026-07-21T12:00:00.000Z",
    actualCasinoIncomeTotal: 250,
    casinoCommissionTotal: 250,
    realPlayerContributionTotal: 2000,
    botContributionTotal: 0,
    payout: 1750,
    actualBootCommission: 100,
    actualWinCommission: 150,
    dealerTip: 25,
  },
  {
    _id: "pending-round",
    settledAt: null,
    actualCasinoIncomeTotal: 9999,
    casinoCommissionTotal: 9999,
  },
];

const walletTransactions = [
  {
    roundId: "round-1",
    status: "succeeded",
    platformOperatorId: "operator-a",
  },
  {
    roundId: "round-2",
    status: "applied",
    requestPayload: { operator_id: "operator-b" },
  },
  {
    roundId: "round-3",
    status: "succeeded",
    requestPayload: { operatorId: "operator-c" },
  },
  {
    roundId: "round-3",
    status: "failed",
    platformOperatorId: "operator-b",
  },
];

function matchesOperator(transaction, operatorId) {
  return (
    transaction.platformOperatorId === operatorId ||
    transaction.requestPayload?.operator_id === operatorId ||
    transaction.requestPayload?.operatorId === operatorId
  );
}

function matchesRound(document, match) {
  if (match._id && !match._id.$in.includes(document._id)) {
    return false;
  }

  const settledAt = document.settledAt;
  if (match.settledAt?.$ne === null && settledAt === null) {
    return false;
  }
  if (match.settledAt?.$gte && settledAt < match.settledAt.$gte) {
    return false;
  }
  if (match.settledAt?.$lte && settledAt > match.settledAt.$lte) {
    return false;
  }

  return true;
}

function sum(rows, field) {
  return rows.reduce((total, row) => total + Number(row[field] || 0), 0);
}

function fakeDatabase({ roundRows = rounds, transactionRows = walletTransactions } = {}) {
  return {
    collection(name) {
      if (name === "wallet_transactions") {
        return {
          distinct: async (fieldName, filter) => {
            assert.equal(fieldName, "roundId");
            const operatorId = filter.$or[0].platformOperatorId;
            return [
              ...new Set(
                transactionRows
                  .filter((transaction) => filter.status.$in.includes(transaction.status))
                  .filter((transaction) => matchesOperator(transaction, operatorId))
                  .map((transaction) => transaction.roundId),
              ),
            ];
          },
        };
      }

      return {
        aggregate: (pipeline) => {
          const match = pipeline[0].$match;
          const filtered = roundRows.filter((document) => matchesRound(document, match));
          const result =
            filtered.length === 0
              ? []
              : [
                  {
                    totalEarnings: sum(filtered, "actualCasinoIncomeTotal"),
                    roundCount: filtered.length,
                    realPlayerContributionTotal: sum(filtered, "realPlayerContributionTotal"),
                    botContributionTotal: sum(filtered, "botContributionTotal"),
                    payoutTotal: sum(filtered, "payout"),
                    actualBootCommissionTotal: sum(filtered, "actualBootCommission"),
                    actualWinCommissionTotal: sum(filtered, "actualWinCommission"),
                    dealerTipTotal: sum(filtered, "dealerTip"),
                  },
                ];
          return { toArray: async () => result };
        },
      };
    },
  };
}

async function listen(router) {
  const app = express();
  app.use("/api/teen-patti", router);
  app.use((error, _request, response, _next) => {
    response.status(error.statusCode || 500).json({
      error: {
        code: error.statusCode === 400 ? "bad_request" : "internal_server_error",
        message: error.message,
      },
    });
  });
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return server;
}

test("earnings summary uses actual casino income instead of gross commission", async () => {
  const payload = await getTeenPattiEarningsSummary({}, fakeDatabase());

  assert.equal(payload.data.totalEarnings, 5540);
  assert.equal(payload.data.roundCount, 3);
  assert.equal(payload.data.realPlayerContributionTotal, 9000);
  assert.equal(payload.data.botContributionTotal, 2000);
  assert.equal(payload.data.payoutTotal, 3460);
  assert.equal(payload.data.actualBootCommissionTotal, 250);
  assert.equal(payload.data.actualWinCommissionTotal, 245);
  assert.equal(payload.data.dealerTipTotal, 25);
});

test("earnings summary filters rounds by successful or applied operator wallet transactions", async () => {
  const payload = await getTeenPattiEarningsSummary({ operator_id: "operator-b" }, fakeDatabase());

  assert.equal(payload.data.totalEarnings, 6000);
  assert.equal(payload.data.roundCount, 1);
  assert.equal(payload.data.filters.operatorId, "operator-b");
});

test("earnings summary supports camelCase operator id in wallet request payload", async () => {
  const payload = await getTeenPattiEarningsSummary({ operator_id: "operator-c" }, fakeDatabase());

  assert.equal(payload.data.totalEarnings, 250);
  assert.equal(payload.data.roundCount, 1);
});

test("earnings summary applies inclusive date filters", async () => {
  const payload = await getTeenPattiEarningsSummary(
    { from: "2026-07-01", to: "2026-07-10" },
    fakeDatabase(),
  );

  assert.equal(payload.data.totalEarnings, 5290);
  assert.equal(payload.data.roundCount, 2);
  assert.equal(payload.data.filters.from, "2026-07-01T00:00:00.000Z");
  assert.equal(payload.data.filters.to, "2026-07-10T23:59:59.999Z");
});

test("earnings summary rejects invalid date filters", () => {
  assert.throws(() => normalizeParams({ from: "not-a-date" }), /from must be a valid ISO timestamp/);
  assert.throws(() => normalizeParams({ to: "2026-02-31" }), /to must be a valid ISO timestamp/);
  assert.throws(() => normalizeParams({ from: "2026-07-20", to: "2026-07-01" }), /from must be earlier/);
});

test("earnings summary returns zero totals when no rounds match", async () => {
  const payload = await getTeenPattiEarningsSummary({ operator_id: "unknown" }, fakeDatabase());

  assert.equal(payload.data.totalEarnings, 0);
  assert.equal(payload.data.roundCount, 0);
});

test("earnings summary route returns JSON and JSON validation errors", async (context) => {
  const router = createEarningsSummaryRouter({
    getTeenPattiEarningsSummary: async (query) => getTeenPattiEarningsSummary(query, fakeDatabase()),
  });
  const server = await listen(router);
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}/api/teen-patti/earnings-summary`;

  const success = await fetch(`${base}?operator_id=operator-a`);
  assert.equal(success.status, 200);
  assert.match(success.headers.get("content-type"), /^application\/json/);
  assert.equal((await success.json()).data.totalEarnings, -710);

  const invalid = await fetch(`${base}?from=nope`);
  assert.equal(invalid.status, 400);
  assert.deepEqual(await invalid.json(), {
    error: {
      code: "bad_request",
      message: "from must be a valid ISO timestamp or YYYY-MM-DD date.",
    },
  });
});
