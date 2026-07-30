const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");
const express = require("express");
process.env.MONGODB_URI ||= "mongodb://127.0.0.1:27017/teen_patti_test";
const { findLatestRound, normalizeParams } = require("../src/services/roundDetailService");
const { renderRoundDetail } = require("../src/views/roundDetailHtml");
const { createRoundDetailRouter } = require("../src/routes/teenPattiRoundDetail");

const sampleRound = {
  _id: "round-2",
  aggregateId: "lobby-1",
  variantId: "classic",
  settledAt: "2026-06-29T12:00:00Z",
  participants: [
    {
      id: "player-1",
      name: "<Alpha>",
      cards: [{ id: "A-hearts", rank: "A", suit: "hearts" }],
      publicCards: [{ id: "A-hearts", rank: "A", suit: "hearts" }],
      handLabel: "High Card",
      totalContributed: 200,
    },
    { id: "bot-1", name: "Teen Patti Bot", isBot: true, cards: [{ id: "K-spades", rank: "K", suit: "spades" }], totalContributed: 200 },
  ],
  winner: { id: "player-1", name: "<Alpha>", winningHand: "High Card" },
  actionLog: [{ playerId: "player-1", actionType: "blind", amount: 100, note: "<bet>" }],
  potAmount: 400,
  dealerTip: 10,
  bootCommission: 20,
  winCommission: 30,
  casinoCommissionTotal: 50,
  payout: 340,
};

function fakeDatabase() {
  return {
    collection(name) {
      if (name === "wallet_transactions") {
        return { distinct: async () => ["player-1"] };
      }
      return { findOne: async () => sampleRound };
    },
  };
}

async function listen(router) {
  const app = express();
  app.use("/api/teen-patti", router);
  const server = http.createServer(app);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return server;
}

test("round lookup validates required Teen Patti identifiers", () => {
  assert.throws(() => normalizeParams({ user_id: "u" }), /operator_id/);
});

test("round lookup returns latest authorized lobby round", async () => {
  const detail = await findLatestRound(
    { user_id: "user-1", operator_id: "operator-1", lobby_id: "lobby-1" },
    fakeDatabase(),
  );
  assert.equal(detail.round._id, "round-2");
  assert.deepEqual(detail.viewerPlayerIds, ["player-1"]);
});

test("HTML renders hands, bets and financial details while escaping content", () => {
  const html = renderRoundDetail({ round: sampleRound, viewerPlayerIds: ["player-1"] });
  assert.match(html, /Teen Patti Financial Details/);
  assert.match(html, /Dealer tip/);
  assert.match(html, /blind/);
  assert.match(html, /A/);
  assert.doesNotMatch(html, /<Alpha>/);
  assert.match(html, /&lt;Alpha&gt;/);
  assert.equal((html.match(/<b>A<\/b>/g) || []).length, 1);
  assert.match(html, /PUBLIC/);
});

test("legacy Teen Patti rounds show an unavailable hand message", () => {
  const round = structuredClone(sampleRound);
  delete round.participants[0].cards;
  delete round.participants[0].publicCards;
  delete round.participants[0].reserveCards;
  const html = renderRoundDetail({ round, viewerPlayerIds: ["player-1"] });
  assert.match(html, /Hand unavailable/);
});

test("round-detail route always returns HTML without API-key authentication", async (context) => {
  const router = createRoundDetailRouter({
    findLatestRound: async () => ({ round: sampleRound, viewerPlayerIds: ["player-1"] }),
  });
  const server = await listen(router);
  context.after(() => new Promise((resolve) => server.close(resolve)));
  const base = `http://127.0.0.1:${server.address().port}/api/teen-patti/round-detail?user_id=u&operator_id=o&lobby_id=l`;

  const success = await fetch(base);
  assert.equal(success.status, 200);
  assert.match(success.headers.get("content-type"), /^text\/html/);
  assert.match(success.headers.get("cache-control"), /no-store/);
  assert.equal(success.headers.get("x-content-type-options"), "nosniff");
  assert.match(await success.text(), /round-2/);
});
