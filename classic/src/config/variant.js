export const GAME_VARIANT = {
  id: "classic",
  label: "Classic",
  title: "Teen Patti Classic",
  tagline: "Traditional 3-Card Casino Table",
  summary: "Standard Teen Patti rules and table flow.",
  rulesIntro:
    "Play traditional 3-card Teen Patti with blind and seen betting, sideshows, and showdowns.",
  rules: [
    {
      title: "3-Card Hand",
      description:
        "Every seated player is dealt 3 cards. Play Blind to keep stakes lower or tap See to view your hand.",
    },
    {
      title: "Standard Hand Rankings",
      description:
        "Trail (Three of a Kind) > Pure Sequence > Sequence > Color (Flush) > Pair > High Card.",
    },
    {
      title: "Sideshow & Show",
      description:
        "Seen players can request a Sideshow against the previous seen player, or call a Show when two players remain.",
    },
  ],
};

export const VARIANT_OPTIONS = [GAME_VARIANT];
export const DEFAULT_VARIANT_ID = GAME_VARIANT.id;

export function isSupportedVariant(variantId) {
  return String(variantId || "").trim().toLowerCase() === GAME_VARIANT.id;
}
