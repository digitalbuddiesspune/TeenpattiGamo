const assert = require("node:assert/strict");
const test = require("node:test");
process.env.MONGODB_URI ||= "mongodb://127.0.0.1:27017/teen_patti_test";

const { parseListFilters } = require("../src/services/adminProfitLossService");
const { login, getSession, logout } = require("../src/services/adminAuthService");

test("admin list filters accept Teen Patti variants", () => {
  const filters = parseListFilters({
    page: "2",
    limit: "10",
    variant: "classic",
    operatorId: "op-1",
    dateFrom: "2026-08-01",
    dateTo: "2026-08-10",
  });

  assert.equal(filters.page, 2);
  assert.equal(filters.limit, 10);
  assert.equal(filters.variantFilter, "classic");
  assert.equal(filters.operatorId, "op-1");
  assert.equal(filters.dateFrom, "2026-08-01T00:00:00.000Z");
  assert.equal(filters.dateTo, "2026-08-10T23:59:59.999Z");
});

test("admin auth login and session round-trip", () => {
  const result = login("admin@gmail.com", "admin123");
  assert.ok(result.token);
  assert.equal(result.admin.email, "admin@gmail.com");

  const session = getSession(result.token);
  assert.equal(session.admin.email, "admin@gmail.com");

  logout(result.token);
  assert.equal(getSession(result.token), null);
});

test("admin auth rejects bad password", () => {
  assert.throws(() => login("admin@gmail.com", "wrong"), /Invalid email or password/);
});
