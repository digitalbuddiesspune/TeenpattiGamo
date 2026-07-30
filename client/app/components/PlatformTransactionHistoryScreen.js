"use client";

import Image from "next/image";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { fetchPlatformRoundHistory } from "../lib/api";
import { getPlatformLaunchContext, withLaunchQuery } from "../lib/platformLaunch";

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
  return (value || 0).toLocaleString("en-IN");
}

function Metric({ label, value, tone = "default" }) {
  const toneClass = tone === "accent"
    ? "text-[#ffe6a0]"
    : tone === "win"
      ? "text-[#abfff5]"
      : "text-white/84";

  return (
    <div className="rounded-[14px] border border-white/8 bg-[rgba(255,255,255,0.03)] px-3 py-2">
      <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-white/42">{label}</span>
      <span className={`mt-1 block text-[11px] font-semibold ${toneClass}`}>{value}</span>
    </div>
  );
}

export default function PlatformTransactionHistoryScreen() {
  const router = useRouter();
  const scrollContainerRef = useRef(null);
  const launchContextRef = useRef(null);
  const loadingMoreRef = useRef(false);
  const [state, setState] = useState({
    loading: true,
    loadingMore: false,
    error: "",
    items: [],
    nextOffset: null,
    hasMore: false,
  });

  useEffect(() => {
    const launchContext = getPlatformLaunchContext();
    if (!launchContext) {
      router.replace("/");
      return undefined;
    }
    launchContextRef.current = launchContext;

    let cancelled = false;
    const load = async () => {
      setState((current) => ({
        ...current,
        loading: true,
        error: "",
      }));

      try {
        const response = await fetchPlatformRoundHistory(launchContext.token, launchContext.gameId, 0, 20);
        if (!cancelled) {
          setState({
            loading: false,
            loadingMore: false,
            error: "",
            items: response.items || [],
            nextOffset: response.nextOffset ?? null,
            hasMore: Boolean(response.hasMore),
          });
        }
      } catch (error) {
        if (!cancelled) {
          setState({
            loading: false,
            loadingMore: false,
            error: error?.message || "Unable to load round history.",
            items: [],
            nextOffset: null,
            hasMore: false,
          });
        }
      }
    };

    void load();
    return () => {
      cancelled = true;
    };
  }, [router]);

  useEffect(() => {
    if (state.loading || !state.hasMore) {
      return undefined;
    }

    const handleScroll = async () => {
      if (loadingMoreRef.current) {
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

      const launchContext = launchContextRef.current;
      if (!launchContext || state.nextOffset == null) {
        return;
      }

      loadingMoreRef.current = true;
      setState((current) => ({
        ...current,
        loadingMore: true,
      }));

      try {
        const response = await fetchPlatformRoundHistory(
          launchContext.token,
          launchContext.gameId,
          state.nextOffset,
          20,
        );
        setState((current) => ({
          ...current,
          loadingMore: false,
          items: current.items.concat(response.items || []),
          nextOffset: response.nextOffset ?? null,
          hasMore: Boolean(response.hasMore),
        }));
      } catch (error) {
        setState((current) => ({
          ...current,
          loadingMore: false,
          error: error?.message || "Unable to load more round history.",
        }));
      } finally {
        loadingMoreRef.current = false;
      }
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
  }, [state.hasMore, state.loading, state.nextOffset]);

  return (
    <main className="casino-page casino-page-menu min-h-screen overflow-hidden bg-[linear-gradient(180deg,#041213,#010607_76%)]">
      <div className="relative flex min-h-screen overflow-hidden">
        <div className="pointer-events-none absolute inset-0 bg-[linear-gradient(180deg,rgba(0,15,16,0.08),rgba(0,6,8,0.26)_68%,rgba(0,0,0,0.88))]" aria-hidden="true" />

        <div ref={scrollContainerRef} className="app-frame app-frame-surface relative h-dvh overflow-y-auto">
          <header className="sticky top-0 z-[5] h-[138px] pt-[max(16px,env(safe-area-inset-top))]">
            <Image
              src="/newAssets/homeTopBg.png"
              alt=""
              fill
              priority
              sizes="(max-width: 640px) 100vw, 500px"
              className="object-cover object-top"
              aria-hidden="true"
            />
            <div className="relative z-[1] flex items-start justify-between px-4 pt-2">
              <button
                type="button"
                onClick={() => router.push(withLaunchQuery("/public"))}
                className="grid h-9 w-9 place-items-center rounded-full bg-[rgba(4,20,20,0.24)] text-[#f3dfab]"
                aria-label="Back to home"
              >
                <svg viewBox="0 0 20 20" aria-hidden="true" className="h-5 w-5">
                  <path d="M11.8 4.6 6.4 10l5.4 5.4" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </button>

              <div className="rounded-full border border-[#43d8cc]/18 bg-[rgba(8,28,31,0.74)] px-3 py-1 text-[9px] font-black uppercase tracking-[0.16em] text-[#abfff5]">
                Round History
              </div>
            </div>
          </header>

          <section className="relative z-[1] px-[14px] pb-[max(24px,env(safe-area-inset-bottom))] pt-4">
            <div className="rounded-[28px] border border-[#ffffff14] bg-[linear-gradient(180deg,rgba(7,44,48,0.92),rgba(3,16,19,0.97))] p-5 shadow-[0_24px_44px_rgba(0,0,0,0.36)] backdrop-blur-[10px]">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <h1 className="text-[1.85rem] font-black leading-[0.96] tracking-[-0.02em] text-white">
                    History
                  </h1>
                  <p className="mt-3 max-w-[18rem] text-[0.82rem] leading-[1.5] text-white/66">
                    Review each completed round with your win or loss amount, contribution, payout, and dealer tip.
                  </p>
                </div>
                <div className="rounded-[18px] border border-[#ffffff14] bg-[rgba(255,255,255,0.04)] px-3 py-2 text-right">
                  <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-white/42">Rounds</span>
                  <strong className="mt-1 block text-[1rem] font-black text-[#ffe6a0]">{state.items.length}</strong>
                </div>
              </div>

              {state.loading ? (
                <div className="mt-5 rounded-[22px] border border-white/8 bg-[rgba(3,18,21,0.52)] p-4">
                  <div className="flex items-center justify-between gap-3 text-[10px] font-black uppercase tracking-[0.14em] text-white/54">
                    <span>Syncing history</span>
                    <span className="text-[#abfff5]">Loading</span>
                  </div>
                  <div className="mt-3 h-[3px] w-full overflow-hidden rounded-full bg-white/8">
                    <div className="h-full w-[44%] rounded-full bg-[linear-gradient(90deg,#2ac7bf_0%,#78efe5_48%,#efc24e_100%)] shadow-[0_0_18px_rgba(67,216,204,0.3)]" style={{ animation: "pulse 1.8s ease-in-out infinite" }} />
                  </div>
                </div>
              ) : null}

              {state.error ? (
                <div className="mt-5 rounded-[18px] border border-[#ffb4b4]/24 bg-[rgba(71,19,19,0.72)] px-4 py-3 text-[0.82rem] text-[#ffe5dd]">
                  {state.error}
                </div>
              ) : null}

              {!state.loading && !state.error && !state.items.length ? (
                <div className="mt-5 rounded-[22px] border border-white/8 bg-[rgba(3,18,21,0.52)] px-4 py-5 text-[0.82rem] leading-[1.5] text-white/66">
                  No completed rounds have been recorded for this platform session yet.
                </div>
              ) : null}

              {!state.loading && !state.error && state.items.length ? (
                <div className="mt-5 grid gap-3">
                  {state.items.map((item) => {
                    const playerWon = item.outcome === "win";
                    return (
                      <article
                        key={item.roundId}
                        className="rounded-[22px] border border-white/10 bg-[linear-gradient(180deg,rgba(8,34,37,0.92),rgba(4,18,21,0.96))] p-4 shadow-[0_18px_34px_rgba(0,0,0,0.24)]"
                      >
                        <div className="flex items-start justify-between gap-3">
                          <div>
                            <span className="block text-[9px] font-black uppercase tracking-[0.14em] text-white/42">
                              {playerWon ? "You Won" : "You Lost"}
                            </span>
                            <strong className={`mt-1 block text-[1.2rem] font-black ${playerWon ? "text-[#abfff5]" : "text-[#ffe6a0]"}`}>
                              {formatAmount(item.resultAmount)}
                            </strong>
                          </div>
                          <div className={`rounded-full border px-2.5 py-1 text-[10px] font-black uppercase tracking-[0.12em] ${
                            playerWon
                              ? "border-[#43d8cc]/24 bg-[#0c3f41] text-[#abfff5]"
                              : "border-[#ffcf99]/24 bg-[#4b3112] text-[#ffe6a0]"
                          }`}>
                            {item.outcome}
                          </div>
                        </div>

                        <p className="mt-3 text-[0.8rem] leading-[1.45] text-white/70">
                          {item.reason || "Completed round"}
                        </p>

                        <div className="mt-3 grid grid-cols-2 gap-2">
                          <Metric label="Round ID" value={item.roundId} />
                          <Metric label="Settled" value={formatTimestamp(item.settledAt)} />
                          <Metric label="Your Contribution" value={formatAmount(item.yourContribution)} />
                          <Metric label="Payout" value={formatAmount(item.payout)} tone={playerWon ? "win" : "default"} />
                          <Metric label="Pot" value={formatAmount(item.potAmount)} />
                          <Metric label="Winning Hand" value={item.winningHand || "N/A"} />
                          <Metric label="Boot Commission" value={formatAmount(item.bootCommission)} />
                        </div>
                      </article>
                    );
                  })}
                </div>
              ) : null}

              {state.loadingMore ? (
                <div className="mt-5 rounded-[18px] border border-white/8 bg-[rgba(3,18,21,0.52)] px-4 py-3 text-center text-[0.78rem] font-semibold text-white/66">
                  Loading more rounds...
                </div>
              ) : null}

              {!state.loading && !state.loadingMore && !state.hasMore && state.items.length ? (
                <div className="mt-5 rounded-[18px] border border-white/8 bg-[rgba(3,18,21,0.52)] px-4 py-3 text-center text-[0.78rem] font-semibold text-white/50">
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
