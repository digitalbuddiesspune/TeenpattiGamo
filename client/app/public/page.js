import { Suspense } from "react";
import GameClient from "../components/GameClient";
import PublicPageClient from "./PublicPageClient";

export default function PublicPage() {
  return (
    <Suspense fallback={<GameClient view="public-menu" />}>
      <PublicPageClient />
    </Suspense>
  );
}
