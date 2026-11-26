export const GAME_VARIANT = {
  id: "muflis",
  label: "Muflis",
  title: "Teen Patti Muflis",
  tagline: "Lowball Inverted Rankings",
  summary: "Lowest hand wins.",
  rulesIntro:
    "In Muflis (Lowball) Teen Patti, hand rankings are completely reversed — the weakest classic hand becomes the winning hand.",
  rules: [
    {
      title: "Lowest Hand Wins",
      description:
        "An unpaired, unsuited low High Card beats Pairs, Colors, Sequences, and Trails.",
    },
    {
      title: "Inverted Hierarchy",
      description:
        "High Card (lowest) > Pair > Color > Sequence > Pure Sequence > Trail (weakest). 2-3-5 offsuit is the strongest hand.",
    },
    {
      title: "Strategic Betting",
      description:
        "Standard blind/seen betting, sideshows, and showdowns apply — watch out for high cards and pairs!",
    },
  ],
};

export const VARIANT_OPTIONS = [GAME_VARIANT];
export const DEFAULT_VARIANT_ID = GAME_VARIANT.id;

export function isSupportedVariant(variantId) {
  return String(variantId || "").trim().toLowerCase() === GAME_VARIANT.id;
}
