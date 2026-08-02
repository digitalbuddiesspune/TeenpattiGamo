"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

export default function TransactionsIndexPage() {
  const router = useRouter();

  useEffect(() => {
    const search = typeof window !== "undefined" ? window.location.search : "";
    router.replace(`/transactions/history${search}`);
  }, [router]);

  return (
    <main className="casino-page casino-page-menu flex min-h-screen items-center justify-center bg-[linear-gradient(180deg,#041213,#010607_76%)] text-white/70">
      <p className="text-sm font-semibold tracking-[0.08em]">Opening transaction history…</p>
    </main>
  );
}
