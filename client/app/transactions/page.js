import { redirect } from "next/navigation";

export default async function TransactionsIndexPage({ searchParams }) {
  const params = await searchParams;
  const query = new URLSearchParams();
  if (params?.id) {
    query.set("id", String(params.id));
  }
  if (params?.game_id) {
    query.set("game_id", String(params.game_id));
  }
  const suffix = query.toString();
  redirect(suffix ? `/transactions/history?${suffix}` : "/transactions/history");
}
