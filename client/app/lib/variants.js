export const VARIANT_OPTIONS = [
  {
    id: "classic",
    label: "Classic",
    summary: "Standard Teen Patti rules and table flow.",
  },
  {
    id: "ak47",
    label: "AK47",
    summary: "A, K, 4, and 7 are jokers.",
  },
  {
    id: "muflis",
    label: "Muflis",
    summary: "Lowest hand wins.",
  },
  {
    id: "flipper",
    label: "Flipper",
    summary: "4 cards dealt; public and folded reserve cards add joker ranks.",
  },
  {
    id: "jhandu",
    label: "Jhandu",
    summary: "Shared jokers unlock by cycle; show and sideshow unlock later.",
  },
];

export const DEFAULT_VARIANT_ID = "classic";

export function isSupportedVariant(variantId) {
  return VARIANT_OPTIONS.some((variant) => variant.id === variantId);
}
