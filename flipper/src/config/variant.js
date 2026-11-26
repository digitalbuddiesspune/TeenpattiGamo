export const GAME_VARIANT = {
  id: "flipper",
  label: "Flipper",
  title: "Teen Patti Flipper",
  tagline: "4-Card Dynamic Joker Table",
  summary: "4 cards dealt; public and folded reserve cards add joker ranks.",
  rulesIntro:
    "In Flipper Teen Patti, every player receives 4 cards including a special blue Flipper card, while public and folded reserve cards dynamically unlock joker ranks.",
  rules: [
    {
      title: "4 Cards Dealt",
      description:
        "Each player is dealt 3 main cards plus a distinctive blue Flipper card.",
    },
    {
      title: "Dynamic Joker Ranks",
      description:
        "Public cards and reserve cards from folded players add active joker ranks for everyone still in the round.",
    },
    {
      title: "Best 3-Card Combination",
      description:
        "Hands are evaluated using active jokers and your strongest combination during sideshows and showdowns.",
    },
  ],
};

export const VARIANT_OPTIONS = [GAME_VARIANT];
export const DEFAULT_VARIANT_ID = GAME_VARIANT.id;

export function isSupportedVariant(variantId) {
  return String(variantId || "").trim().toLowerCase() === GAME_VARIANT.id;
}
