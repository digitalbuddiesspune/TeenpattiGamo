"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchPlatformCreditTransactions,
  fetchPlatformDebitTransactions,
  fetchPlatformRoundHistory,
} from "../lib/api";
import { getPlatformLaunchContext, withLaunchQuery } from "../lib/platformLaunch";

const TABS = [
  {
    id: "rounds",
    label: "Rounds",
    hint: "Wins, losses, tips & commissions",
  },
  {
    id: "debit",
    label: "Debits",
    hint: "Boots, bets & stake charges",
  },
  {
    id: "credit",
    label: "Credits",
    hint: "Payouts & refunds",
  },
];

function formatTimestamp(value) {
  if (!value) {
    return "Pending";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(date);
}

function formatAmount(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) {
    return "0";
  }
  return amount.toLocaleString("en-IN");
}

function shortenId(value, size = 10) {
  if (!value) {
    return "—";
  }
  const text = String(value);
  if (text.length <= size + 3) {
    return text;
  }
  return `${text.slice(0, size)}…`;
}

function statusTone(status) {
  const normalized = String(status || "").toLowerCase();
  if (normalized === "succeeded" || normalized === "applied") {
    return "win";
  }
  if (normalized === "failed") {
    return "loss";
  }
  return "accent";
}

function Metric({ label, value, tone = "default" }) {
  const toneClass = tone === "accent"
    ? "text-[#ffe6a0]"
    : tone === "win"
      ? "text-[#9bffb0]"
      : tone === "loss"
        ? "text-[#ffc7b8]"
        : "text-white/84";

  return (
    <div className="rounded-[14px] border border-[#e8b53c]/16 bg-[rgba(90,16,24,0.28)] px-3 py-2">
      <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-[#ffe6a0]/55">{label}</span>
      <span className={`mt-1 block break-all text-[11px] font-semibold ${toneClass}`}>{value}</span>
    </div>
  );
}

function TabButton({ tab, active, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`min-w-0 flex-1 rounded-[16px] border px-3 py-2.5 text-left transition ${
        active
          ? "border-[#e8b53c]/40 bg-[linear-gradient(180deg,rgba(140,22,28,0.96),rgba(72,8,14,0.98))] shadow-[0_12px_24px_rgba(0,0,0,0.28)]"
          : "border-[#ffffff14] bg-[rgba(90,16,24,0.28)] hover:border-[#e8b53c]/28"
      }`}
    >
      <span className={`block text-[11px] font-black uppercase tracking-[0.12em] ${active ? "text-[#ffe6a0]" : "text-white/72"}`}>
        {tab.label}
      </span>
      <span className="mt-1 block text-[10px] leading-[1.35] text-white/48">{tab.hint}</span>
    </button>
  );
}

function RoundHistoryCard({ item }) {
  const playerWon = item.outcome === "win";

  return (
    <article className="rounded-[22px] border border-[#e8b53c]/22 bg-[linear-gradient(180deg,rgba(90,16,24,0.92),rgba(36,6,12,0.96))] p-4 shadow-[0_18px_34px_rgba(0,0,0,0.24)]">
      <div className="flex items-start justify-between gap-3">
        <div>
          <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-[#ffe6a0]/55">
            {playerWon ? "You Won" : "You Lost"}
          </span>
          <strong className={`mt-1 block text-[1.2rem] font-black ${playerWon ? "text-[#9bffb0]" : "text-[#ffe6a0]"}`}>
            {playerWon ? "+" : "−"}{formatAmount(item.resultAmount)}
          </strong>
        </div>
        <div className={`rounded-full border px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.12em] ${
          playerWon
            ? "border-[#9bffb0]/28 bg-[#1a3d24] text-[#9bffb0]"
            : "border-[#ffcf99]/24 bg-[#4b3112] text-[#ffe6a0]"
        }`}>
          {item.outcome}
        </div>
      </div>

      <p className="mt-3 text-[0.8rem] leading-[1.45] text-white/70">
        {item.reason || "Completed round"}
      </p>

      <div className="mt-3 grid grid-cols-2 gap-2">
        <Metric label="Round ID" value={shortenId(item.roundId, 14)} />
        <Metric label="Settled" value={formatTimestamp(item.settledAt)} />
        <Metric label="Your Contribution" value={formatAmount(item.yourContribution)} tone="accent" />
        <Metric label="Payout Credited" value={formatAmount(item.payout)} tone={playerWon ? "win" : "default"} />
        <Metric label="Table Pot" value={formatAmount(item.potAmount)} />
        <Metric label="Winning Hand" value={item.winningHand || "N/A"} />
        <Metric label="Dealer Tip" value={formatAmount(item.dealerTip)} />
        <Metric label="Before Tip" value={formatAmount(item.winnerReceivableBeforeTip)} />
        <Metric label="Boot Commission" value={formatAmount(item.bootCommission)} />
        <Metric label="Win Commission" value={formatAmount(item.winCommission)} />
        <Metric label="Casino Total" value={formatAmount(item.casinoCommissionTotal)} />
        <Metric label="Net Result" value={formatAmount(item.resultAmount)} tone={playerWon ? "win" : "loss"} />
      </div>
    </article>
  );
}

function WalletTransactionCard({ item, txnType }) {
  const isCredit = txnType === "credit";

  return (
    <article className="rounded-[22px] border border-[#e8b53c]/22 bg-[linear-gradient(180deg,rgba(90,16,24,0.92),rgba(36,6,12,0.96))] p-4 shadow-[0_18px_34px_rgba(0,0,0,0.24)]">
      <div className="flex items-start justify-between gap-3">
        <div>
          <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-[#ffe6a0]/55">
            {isCredit ? "Wallet Credit" : "Wallet Debit"}
          </span>
          <strong className={`mt-1 block text-[1.2rem] font-black ${isCredit ? "text-[#9bffb0]" : "text-[#ffe6a0]"}`}>
            {isCredit ? "+" : "−"}{formatAmount(item.amount)}
          </strong>
        </div>
        <div className={`rounded-full border px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.12em] ${
          statusTone(item.status) === "win"
            ? "border-[#9bffb0]/28 bg-[#1a3d24] text-[#9bffb0]"
            : statusTone(item.status) === "loss"
              ? "border-[#ff9f8a]/28 bg-[#4a1d1a] text-[#ffc7b8]"
              : "border-[#ffcf99]/24 bg-[#4b3112] text-[#ffe6a0]"
        }`}>
          {item.status || "pending"}
        </div>
      </div>

      <p className="mt-3 text-[0.8rem] leading-[1.45] text-white/70">
        {item.description || (isCredit ? "Platform credit" : "Platform debit")}
      </p>

      <div className="mt-3 grid grid-cols-2 gap-2">
        <Metric label="Txn ID" value={shortenId(item.txnId, 14)} />
        <Metric label="Round ID" value={shortenId(item.roundId, 14)} />
        <Metric label="Created" value={formatTimestamp(item.createdAt)} />
        <Metric label="Updated" value={formatTimestamp(item.updatedAt)} />
        <Metric label="Amount" value={formatAmount(item.amount)} tone={isCredit ? "win" : "accent"} />
        <Metric label="Status" value={item.status || "—"} tone={statusTone(item.status)} />
        <Metric label="Txn Ref" value={item.txnRefId ? shortenId(item.txnRefId, 16) : "—"} />
        <Metric label="Type" value={isCredit ? "Credit" : "Debit"} tone={isCredit ? "win" : "accent"} />
      </div>
    </article>
  );
}

function createEmptyState() {
  return {
    loading: true,
    loadingMore: false,
    error: "",
    items: [],
    nextOffset: null,
    hasMore: false,
  };
}

async function fetchTabPage(tabId, token, gameId, offset, limit = 20) {
  if (tabId === "debit") {
    return fetchPlatformDebitTransactions(token, gameId, offset, limit);
  }
  if (tabId === "credit") {
    return fetchPlatformCreditTransactions(token, gameId, offset, limit);
  }
  return fetchPlatformRoundHistory(token, gameId, offset, limit);
}

export default function PlatformTransactionHistoryScreen({ initialTab = "rounds" }) {
  const router = useRouter();
  const scrollContainerRef = useRef(null);
  const launchContextRef = useRef(null);
  const loadingMoreRef = useRef(false);
  const activeTabRef = useRef(initialTab);
  const [activeTab, setActiveTab] = useState(
    TABS.some((tab) => tab.id === initialTab) ? initialTab : "rounds",
  );
  const [authMissing, setAuthMissing] = useState(false);
  const [state, setState] = useState(createEmptyState);

  const loadTab = useCallback(async (tabId, { append = false, offset = 0 } = {}) => {
    const launchContext = launchContextRef.current;
    if (!launchContext) {
      return;
    }

    if (append) {
      loadingMoreRef.current = true;
      setState((current) => ({
        ...current,
        loadingMore: true,
        error: "",
      }));
    } else {
      setState({
        ...createEmptyState(),
        loading: true,
      });
    }

    try {
      const response = await fetchTabPage(tabId, launchContext.token, launchContext.gameId, offset, 20);
      setState((current) => ({
        loading: false,
        loadingMore: false,
        error: "",
        items: append ? current.items.concat(response.items || []) : (response.items || []),
        nextOffset: response.nextOffset ?? null,
        hasMore: Boolean(response.hasMore),
      }));
    } catch (error) {
      setState((current) => ({
        ...current,
        loading: false,
        loadingMore: false,
        error: error?.message || "Unable to load transaction history.",
        ...(append ? {} : { items: [], nextOffset: null, hasMore: false }),
      }));
    } finally {
      loadingMoreRef.current = false;
    }
  }, []);

  useEffect(() => {
    const launchContext = getPlatformLaunchContext();
    if (!launchContext) {
      setAuthMissing(true);
      setState({
        ...createEmptyState(),
        loading: false,
      });
      return undefined;
    }

    setAuthMissing(false);
    launchContextRef.current = launchContext;
    void loadTab(activeTabRef.current);
    return undefined;
  }, [loadTab]);

  useEffect(() => {
    activeTabRef.current = activeTab;
  }, [activeTab]);

  useEffect(() => {
    if (authMissing || state.loading || !state.hasMore) {
      return undefined;
    }

    const handleScroll = async () => {
      if (loadingMoreRef.current || state.nextOffset == null) {
        return;
      }

      const container = scrollContainerRef.current;
      if (!container) {
        return;
      }

      const distanceFromBottom = container.scrollHeight - (container.scrollTop + container.clientHeight);
      if (distanceFromBottom > 320) {
        return;
      }

      await loadTab(activeTabRef.current, {
        append: true,
        offset: state.nextOffset,
      });
    };

    const container = scrollContainerRef.current;
    const onScroll = () => {
      void handleScroll();
    };

    container?.addEventListener("scroll", onScroll, { passive: true });
    void handleScroll();

    return () => {
      container?.removeEventListener("scroll", onScroll);
    };
  }, [authMissing, loadTab, state.hasMore, state.loading, state.nextOffset]);

  const handleTabChange = (tabId) => {
    if (tabId === activeTab) {
      return;
    }
    setActiveTab(tabId);
    activeTabRef.current = tabId;
    if (scrollContainerRef.current) {
      scrollContainerRef.current.scrollTop = 0;
    }
    if (!authMissing) {
      void loadTab(tabId);
    }
  };

  const activeMeta = TABS.find((tab) => tab.id === activeTab) || TABS[0];
  const emptyCopy = activeTab === "debit"
    ? "No debit transactions have been recorded for this session yet."
    : activeTab === "credit"
      ? "No credit transactions have been recorded for this session yet."
      : "No completed rounds have been recorded for this platform session yet.";

  return (
    <main className="casino-page casino-page-menu min-h-screen overflow-hidden bg-[linear-gradient(180deg,#2a070c,#140306_55%,#0a0204_100%)]">
      <div className="relative flex min-h-screen overflow-hidden">
        <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_55%_at_50%_-10%,#8b1520_0%,transparent_58%),linear-gradient(180deg,rgba(42,7,12,0.2),rgba(10,2,4,0.72)_70%)]" aria-hidden="true" />

        <div ref={scrollContainerRef} className="app-frame app-frame-surface relative h-dvh overflow-y-auto">
          <section className="relative z-[1] px-[14px] pb-[max(24px,env(safe-area-inset-bottom))] pt-[max(16px,env(safe-area-inset-top))]">
            <div className="mb-3 flex items-center justify-between gap-3">
              <button
                type="button"
                onClick={() => router.push(withLaunchQuery("/public"))}
                className="grid h-9 w-9 place-items-center rounded-full border border-[#e8b53c]/35 bg-[rgba(90,16,24,0.55)] text-[#ffe6a0]"
                aria-label="Back to lobby"
              >
                <svg viewBox="0 0 20 20" aria-hidden="true" className="h-5 w-5">
                  <path d="M11.8 4.6 6.4 10l5.4 5.4" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>
              <div className="rounded-full border border-[#e8b53c]/35 bg-[rgba(90,16,24,0.82)] px-3 py-1 text-[9px] font-black uppercase tracking-[0.16em] text-[#ffe6a0]">
                Transaction History
              </div>
            </div>

            <div className="rounded-[28px] border border-[#e8b53c]/28 bg-[linear-gradient(180deg,rgba(125,16,25,0.94),rgba(61,7,13,0.98))] p-5 shadow-[0_24px_44px_rgba(0,0,0,0.36)] backdrop-blur-[10px]">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h1 className="text-[1.85rem] font-black leading-[0.96] tracking-[-0.02em] text-[#ffe9b0]">
                    History
                  </h1>
                  <p className="mt-3 max-w-[20rem] text-[0.82rem] leading-[1.5] text-white/66">
                    Track round outcomes, wallet debits for stakes, and credits for payouts or refunds.
                  </p>
                </div>
                <div className="rounded-[18px] border border-[#e8b53c]/28 bg-[rgba(12,2,5,0.35)] px-3 py-2 text-right">
                  <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-[#ffe6a0]/55">
                    {activeMeta.label}
                  </span>
                  <strong className="mt-1 block text-[1rem] font-black text-[#ffe6a0]">{state.items.length}</strong>
                </div>
              </div>

              <div className="mt-5 flex gap-2">
                {TABS.map((tab) => (
                  <TabButton
                    key={tab.id}
                    tab={tab}
                    active={activeTab === tab.id}
                    onClick={() => handleTabChange(tab.id)}
                  />
                ))}
              </div>

              {authMissing ? (
                <div className="mt-5 rounded-[22px] border border-[#ffcf99]/22 bg-[rgba(58,34,10,0.72)] px-4 py-5 text-[0.82rem] leading-[1.5] text-[#ffe6a0]">
                  Launch the game with a platform session (`id` and `game_id`) to load your transaction history.
                </div>
              ) : null}

              {!authMissing && state.loading ? (
                <div className="mt-5 rounded-[22px] border border-[#e8b53c]/18 bg-[rgba(12,2,5,0.45)] p-4">
                  <div className="flex items-center justify-between gap-3 text-[10px] font-black uppercase tracking-[0.14em] text-[#ffe6a0]/70">
                    <span>Syncing {activeMeta.label.toLowerCase()}</span>
                    <span className="text-[#ffe6a0]">Loading</span>
                  </div>
                  <div className="mt-3 h-[3px] w-full overflow-hidden rounded-full bg-white/8">
                    <div className="h-full w-[44%] rounded-full bg-[linear-gradient(90deg,#c61d1a_0%,#ff6b5a_48%,#efc24e_100%)] shadow-[0_0_18px_rgba(240,58,45,0.35)]" style={{ animation: "pulse 1.8s ease-in-out infinite" }} />
                  </div>
                </div>
              ) : null}

              {!authMissing && state.error ? (
                <div className="mt-5 rounded-[18px] border border-[#ffb4b4]/24 bg-[rgba(71,19,19,0.72)] px-4 py-3 text-[0.82rem] text-[#ffe5dd]">
                  {state.error}
                </div>
              ) : null}

              {!authMissing && !state.loading && !state.error && !state.items.length ? (
                <div className="mt-5 rounded-[22px] border border-[#e8b53c]/18 bg-[rgba(12,2,5,0.45)] px-4 py-5 text-[0.82rem] leading-[1.5] text-white/66">
                  {emptyCopy}
                </div>
              ) : null}

              {!authMissing && !state.loading && !state.error && state.items.length ? (
                <div className="mt-5 grid gap-3">
                  {activeTab === "rounds"
                    ? state.items.map((item) => (
                      <RoundHistoryCard key={item.roundId || item.settledAt} item={item} />
                    ))
                    : state.items.map((item) => (
                      <WalletTransactionCard
                        key={item.txnId || `${item.roundId}-${item.createdAt}`}
                        item={item}
                        txnType={activeTab}
                      />
                    ))}
                </div>
              ) : null}

              {state.loadingMore ? (
                <div className="mt-5 rounded-[18px] border border-[#e8b53c]/18 bg-[rgba(12,2,5,0.45)] px-4 py-3 text-center text-[0.78rem] font-semibold text-[#ffe6a0]/70">
                  Loading more {activeMeta.label.toLowerCase()}...
                </div>
              ) : null}

              {!authMissing && !state.loading && !state.loadingMore && !state.hasMore && state.items.length ? (
                <div className="mt-5 rounded-[18px] border border-[#e8b53c]/16 bg-[rgba(12,2,5,0.4)] px-4 py-3 text-center text-[0.78rem] font-semibold text-white/50">
                  You have reached the end of this history.
                </div>
              ) : null}
            </div>
          </section>
        </div>
      </div>
    </main>
  );
}
