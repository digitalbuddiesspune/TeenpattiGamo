import { useEffect, useState } from "react";
import { GamesTable } from "../components/GamesTable";
import { SummaryCards } from "../components/SummaryCards";
import { UsersTable } from "../components/UsersTable";
import {
  DATE_PRESETS,
  detectActiveDatePreset,
  resolveDatePresetRange,
} from "../utils/dates";

const SECTIONS = [
  { id: "games", label: "Games" },
  { id: "users", label: "Users" },
];

const VARIANT_FILTERS = [
  { id: "all", label: "All" },
  { id: "classic", label: "Classic" },
  { id: "ak47", label: "AK47" },
  { id: "muflis", label: "Muflis" },
  { id: "flipper", label: "Flipper" },
  { id: "jhandu", label: "Jhandu" },
  { id: "public", label: "Public" },
  { id: "private", label: "Private" },
];

const SEARCH_BY_OPTIONS = [
  { id: "all", label: "All fields" },
  { id: "playerName", label: "Player name" },
  { id: "playerId", label: "Player ID" },
  { id: "gameId", label: "Game ID" },
  { id: "roundId", label: "Round ID" },
];

export function ProfitLossPage({
  section,
  onSectionChange,
  playerFilter,
  onPlayerFilterChange,
  operatorFilter,
  onOperatorFilterChange,
  dateFrom,
  dateTo,
  onDateFilterChange,
  searchQuery,
  searchBy,
  onSearchFilterChange,
  operators,
  summary,
  games,
  gamesPagination,
  onGamesPageChange,
  users,
  usersPagination,
  onUsersPageChange,
  onSelectGame,
  tableLoading = false,
}) {
  const operatorOptions = [
    { id: "all", label: "All Platforms" },
    ...(Array.isArray(operators)
      ? operators.map((item) => ({ id: item.operatorId, label: item.label }))
      : []),
  ];
  const activeDatePreset = detectActiveDatePreset(dateFrom, dateTo);
  const [draftSearch, setDraftSearch] = useState(searchQuery || "");
  const [draftSearchBy, setDraftSearchBy] = useState(searchBy || "all");

  useEffect(() => {
    setDraftSearch(searchQuery || "");
  }, [searchQuery]);

  useEffect(() => {
    setDraftSearchBy(searchBy || "all");
  }, [searchBy]);

  const handlePresetChange = (presetId) => {
    const range = resolveDatePresetRange(presetId);
    onDateFilterChange(range.from, range.to);
  };

  const handleDateFromChange = (event) => {
    const nextFrom = event.target.value;
    const nextTo = dateTo && nextFrom && nextFrom > dateTo ? nextFrom : dateTo;
    onDateFilterChange(nextFrom, nextTo);
  };

  const handleDateToChange = (event) => {
    const nextTo = event.target.value;
    const nextFrom = dateFrom && nextTo && dateFrom > nextTo ? nextTo : dateFrom;
    onDateFilterChange(nextFrom, nextTo);
  };

  const handleClearDates = () => {
    onDateFilterChange("", "");
  };

  const handleSearchSubmit = (event) => {
    event.preventDefault();
    onSearchFilterChange(draftSearch.trim(), draftSearchBy);
  };

  const handleClearSearch = () => {
    setDraftSearch("");
    setDraftSearchBy("all");
    onSearchFilterChange("", "all");
  };

  const searchPlaceholder =
    {
      all: "Search player name, player ID, game ID, or round ID…",
      playerName: "Search by player name…",
      playerId: "Search by player ID…",
      gameId: "Search by game / table ID…",
      roundId: "Search by round ID…",
    }[draftSearchBy] || "Search…";

  const resultCount =
    section === "users"
      ? Number(usersPagination?.totalItems ?? users?.length ?? 0)
      : Number(gamesPagination?.totalItems ?? games?.length ?? 0);

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h2 className="text-[1.75rem] font-extrabold tracking-tight text-[var(--color-ink)]">
          Profit & Loss
        </h2>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-[var(--color-muted)]">
          Real player contributions, casino commissions, winner payouts, and per-user profit/loss.
          Search by player, game, or round, and filter by variant, platform, or time range.
        </p>
      </div>

      <div className="flex flex-col gap-3">
        <form
          onSubmit={handleSearchSubmit}
          className="rounded-2xl bg-white p-3 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)] sm:p-4"
        >
          <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
            <label className="sr-only" htmlFor="admin-search-by">
              Search by
            </label>
            <select
              id="admin-search-by"
              value={draftSearchBy}
              onChange={(event) => setDraftSearchBy(event.target.value)}
              className="rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2.5 text-sm font-semibold text-[var(--color-ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/20 sm:w-[10.5rem]"
            >
              {SEARCH_BY_OPTIONS.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.label}
                </option>
              ))}
            </select>

            <label className="sr-only" htmlFor="admin-search-query">
              Search
            </label>
            <input
              id="admin-search-query"
              type="search"
              value={draftSearch}
              onChange={(event) => setDraftSearch(event.target.value)}
              placeholder={searchPlaceholder}
              className="min-w-0 flex-1 rounded-xl border border-[var(--color-line)] bg-white px-3 py-2.5 text-sm font-medium text-[var(--color-ink)] outline-none transition placeholder:text-[var(--color-muted)] focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/20"
            />

            <div className="flex gap-2">
              <button
                type="submit"
                disabled={tableLoading}
                className="rounded-xl bg-[var(--accent)] px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
              >
                {tableLoading ? "Searching…" : "Search"}
              </button>
              {(searchQuery || draftSearch) ? (
                <button
                  type="button"
                  onClick={handleClearSearch}
                  className="rounded-xl px-3 py-2.5 text-sm font-semibold text-[var(--color-muted)] ring-1 ring-[var(--color-line)] transition hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                >
                  Clear
                </button>
              ) : null}
            </div>
          </div>
          {searchQuery ? (
            <p className="mt-2 text-xs text-[var(--color-muted)]">
              Showing{" "}
              <span className="font-semibold text-[var(--color-ink)]">
                {tableLoading
                  ? "…"
                  : `${resultCount} ${resultCount === 1 ? "result" : "results"}`}
              </span>{" "}
              for{" "}
              <span className="font-semibold text-[var(--color-ink)]">
                {SEARCH_BY_OPTIONS.find((item) => item.id === searchBy)?.label || "All fields"}
              </span>
              :{" "}
              <span className="font-semibold text-[var(--color-ink)]">&ldquo;{searchQuery}&rdquo;</span>
            </p>
          ) : null}
        </form>

        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="inline-flex flex-wrap gap-1 rounded-xl bg-white p-1 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)]">
            {SECTIONS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => onSectionChange(item.id)}
                className={`rounded-lg px-4 py-2 text-sm font-semibold transition-all duration-200 ${
                  section === item.id
                    ? "bg-[var(--accent)] text-white shadow-sm"
                    : "text-[var(--color-muted)] hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>

          <div className="inline-flex flex-wrap gap-1 rounded-xl bg-white p-1 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)]">
            {VARIANT_FILTERS.map((item) => (
              <button
                key={String(item.id)}
                type="button"
                onClick={() => onPlayerFilterChange(item.id)}
                className={`rounded-lg px-3.5 py-2 text-sm font-semibold transition-all duration-200 ${
                  playerFilter === item.id
                    ? "bg-[var(--color-ink)] text-white shadow-sm"
                    : "text-[var(--color-muted)] hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {operatorOptions.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => onOperatorFilterChange(item.id)}
              className={`rounded-xl px-3.5 py-2 text-sm font-semibold transition-all duration-200 ring-1 ${
                operatorFilter === item.id
                  ? "bg-[var(--accent-soft)] text-[var(--accent)] ring-[var(--accent)]/30"
                  : "bg-white text-[var(--color-muted)] ring-[var(--color-line)] hover:text-[var(--color-ink)]"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        <div className="rounded-2xl bg-white p-3 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)] sm:p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-bold uppercase tracking-[0.12em] text-[var(--color-muted)]">
                Time
              </span>
              {DATE_PRESETS.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => handlePresetChange(item.id)}
                  className={`rounded-xl px-3.5 py-2 text-sm font-semibold transition-all duration-200 ring-1 ${
                    activeDatePreset === item.id
                      ? "bg-[var(--color-ink)] text-white ring-[var(--color-ink)]"
                      : "bg-[#f8faf9] text-[var(--color-muted)] ring-[var(--color-line)] hover:text-[var(--color-ink)]"
                  }`}
                >
                  {item.label}
                </button>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-2 lg:justify-end">
              <label className="flex items-center gap-2 text-sm">
                <span className="font-semibold text-[var(--color-ink)]">From</span>
                <input
                  type="date"
                  value={dateFrom}
                  onChange={handleDateFromChange}
                  className="min-w-[9.5rem] rounded-xl border border-[var(--color-line)] bg-white px-3 py-2 text-sm font-medium text-[var(--color-ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/20"
                />
              </label>

              <label className="flex items-center gap-2 text-sm">
                <span className="font-semibold text-[var(--color-ink)]">To</span>
                <input
                  type="date"
                  value={dateTo}
                  min={dateFrom || undefined}
                  onChange={handleDateToChange}
                  className="min-w-[9.5rem] rounded-xl border border-[var(--color-line)] bg-white px-3 py-2 text-sm font-medium text-[var(--color-ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/20"
                />
              </label>

              {(dateFrom || dateTo) ? (
                <button
                  type="button"
                  onClick={handleClearDates}
                  className="rounded-xl px-3 py-2 text-sm font-semibold text-[var(--color-muted)] ring-1 ring-[var(--color-line)] transition hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                >
                  Clear
                </button>
              ) : null}
            </div>
          </div>
        </div>
      </div>

      {summary ? <SummaryCards summary={summary} /> : null}

      {section === "games" ? (
        <GamesTable
          games={games}
          pagination={gamesPagination}
          onPageChange={onGamesPageChange}
          onSelectGame={onSelectGame}
          loading={tableLoading}
        />
      ) : null}

      {section === "users" ? (
        <UsersTable
          users={users}
          pagination={usersPagination}
          onPageChange={onUsersPageChange}
          currency={summary?.currency || "INR"}
          loading={tableLoading}
        />
      ) : null}
    </div>
  );
}
