import "./setup-env.js";
import assert from "node:assert/strict";
import test from "node:test";
import {
  buildSearchClause,
  parseListFilters,
} from "../src/services/adminProfitLossService.js";
import { login, getSession, logout } from "../src/services/adminAuthService.js";

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
  assert.equal(filters.search, "");
  assert.equal(filters.searchBy, "all");
});

test("admin list filters accept search by player and ids", () => {
  const filters = parseListFilters({
    search: "Naveen",
    searchBy: "playerName",
  });

  assert.equal(filters.search, "Naveen");
  assert.equal(filters.searchBy, "playerName");

  const clause = buildSearchClause("abc-123", "roundId");
  assert.ok(clause.$or.some((item) => item._id === "abc-123"));
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
