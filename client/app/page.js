import { Suspense } from "react";
import GameClient from "./components/GameClient";
import PublicPageClient from "./public/PublicPageClient";

export default function Home() {
  return (
    <Suspense fallback={<GameClient view="public-menu" />}>
      <PublicPageClient />
    </Suspense>
  );
}
