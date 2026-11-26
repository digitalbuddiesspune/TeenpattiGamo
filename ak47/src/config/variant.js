export const GAME_VARIANT = {
  id: "ak47",
  label: "AK47",
  title: "Teen Patti AK47",
  tagline: "Wildcard Joker Edition",
  summary: "A, K, 4, and 7 are jokers.",
  rulesIntro:
    "In AK47 Teen Patti, every Ace, King, 4, and 7 acts as a wildcard joker that can stand in for any card to make your strongest 3-card hand.",
  rules: [
    {
      title: "Four Permanent Joker Ranks",
      description:
        "All Aces (A), Kings (K), Fours (4), and Sevens (7) of any suit are automatic jokers.",
    },
    {
      title: "Wildcard Hand Evaluation",
      description:
        "Jokers automatically substitute for the best card possible to complete Trails, Pure Sequences, Sequences, Colors, or Pairs.",
    },
    {
      title: "Fast Casino Action",
      description:
        "Standard blind/seen betting, sideshows, and showdowns apply with high-powered hands.",
    },
  ],
};

export const VARIANT_OPTIONS = [GAME_VARIANT];
export const DEFAULT_VARIANT_ID = GAME_VARIANT.id;

export function isSupportedVariant(variantId) {
  return String(variantId || "").trim().toLowerCase() === GAME_VARIANT.id;
}
