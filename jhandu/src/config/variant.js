export const GAME_VARIANT = {
  id: "jhandu",
  label: "Jhandu",
  title: "Teen Patti Jhandu",
  tagline: "Progressive Shared Jokers",
  summary: "Shared jokers unlock by cycle; show and sideshow unlock later.",
  rulesIntro:
    "In Jhandu Teen Patti, shared community jokers on the table reveal cycle by cycle, changing hand strengths as the pot grows.",
  rules: [
    {
      title: "Shared Jokers by Cycle",
      description:
        "Shared joker cards are revealed progressively on the table after betting cycles complete.",
    },
    {
      title: "Delayed Show & Sideshow",
      description:
        "Show and sideshow actions unlock only after the required cycles and shared joker reveals have occurred.",
    },
    {
      title: "Evolving Strategy",
      description:
        "Every newly revealed shared joker can transform weak hands into Trails or Pure Sequences — time your bets carefully.",
    },
  ],
};

export const VARIANT_OPTIONS = [GAME_VARIANT];
export const DEFAULT_VARIANT_ID = GAME_VARIANT.id;

export function isSupportedVariant(variantId) {
  return String(variantId || "").trim().toLowerCase() === GAME_VARIANT.id;
}
