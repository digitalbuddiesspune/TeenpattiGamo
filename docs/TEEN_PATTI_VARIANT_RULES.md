# Teen Patti Variant Rules

## 1. Purpose

This document lists the gameplay rules for each supported Teen Patti variant in this project.

It is intended as the canonical rules matrix for product, QA, and engineering.

Current scope:

- public variants: `classic`, `ak47`, `muflis`, `flipper`, `jhandu`
- private room variants: `classic`, `ak47`, `muflis`, `flipper`, `jhandu`

## 2. Shared Base Rules

Unless a variant says otherwise, these base rules apply:

- Each active player is dealt a 3-card Teen Patti hand.
- All players post the boot amount at round start.
- Turn order moves clockwise from the chosen opening player.
- Standard actions are `blind`, `chaal`, `raise`, `see`, `pack`, `sideshow`, and `show`.
- `show` is available only when two active players remain.
- Standard hand strength order is:
  1. Trail
  2. Pure Sequence
  3. Sequence
  4. Color
  5. Pair
  6. High Card
- `A-K-Q` and `A-2-3` sequence handling follows the project-wide Teen Patti rules already used in `classic`.
- When two `Color` hands have identical ranks, this project keeps them tied by rank only. Some public descriptions break that tie by suit order, but suit priority is not currently part of the server comparison logic.

## 3. Classic

Variant id: `classic`

Rules:

- Uses the shared base rules with no extra joker cards.
- No dealt card becomes wild automatically.
- Winner is determined by normal Teen Patti hand ranking.

Use this as the default reference ruleset for standard public play and for private rooms when the host does not choose another variant.

## 4. AK47

Variant id: `ak47`

Rules:

- Uses the shared base rules.
- Any card with rank `A`, `K`, `4`, or `7` acts as a wildcard.
- Wildcards can substitute for missing cards to make the best possible 3-card hand.
- Winner is still determined by normal Teen Patti hand ranking after wildcard substitution.

Examples:

- `A-9-9` can improve to trail `9-9-9`.
- `4-Q-K` can improve to a stronger sequence or pure sequence if suits/ranks allow.

## 5. Muflis

Variant id: `muflis`

Rules:

- Uses the shared base rules for dealing, turns, betting, `see`, `sideshow`, and `show`.
- There are no extra joker cards.
- Hand comparison is reversed: the weakest hand wins.
- Tie-breaks are also reversed: lower ranks beat higher ranks within the same hand category.

Implication of lowball ordering:

1. High Card is strongest for winning the round in Muflis.
2. Pair loses to High Card.
3. Color loses to Pair.
4. Sequence loses to Color.
5. Pure Sequence loses to Sequence.
6. Trail is the weakest result in Muflis.

Examples:

- A weak `2-4-7` High Card beats `A-A-K`.
- A low Pair beats a Color, but loses to a High Card.

## 6. Flipper

Variant id: `flipper`

Common public alias: `Folding Joker`

Implementation note:

- In this project, `Flipper` is implemented as a Folding Joker style variation.
- This naming is a product alias. The rule behavior is intentional and documented here.

Rules:

- Each active player is dealt 4 cards instead of 3.
- Only 3 cards are used as the player’s active hand for Teen Patti comparison.
- The 4th card is a reserve card and starts hidden.
- The 3rd dealt active card is exposed publicly for each player.
- Each exposed public card contributes its rank as a live wildcard rank for everyone at the table.
- If a player packs, that player’s hidden reserve card is flipped face-up.
- Once flipped, that reserve card also contributes its rank as a new live wildcard rank for everyone.
- All live wildcard ranks remain active for the rest of the round.
- Final showdown still compares only the player’s active 3-card hand, after wildcard substitution.

Examples:

- If exposed public cards include ranks `4` and `9`, then all `4`s and `9`s are wild.
- If a packed player flips a reserve `2`, then `2` also becomes wild for the rest of the round.

## 7. Jhandu

Variant id: `jhandu`

Common public alias: `Zhandu`

Rules:

- Each active player is dealt a normal 3-card hand.
- In addition, 3 shared joker cards are created for the round.
- These 3 shared joker cards start hidden.
- The round tracks betting cycles, not just individual turns.
- A cycle completes when action returns to the cycle’s starting player.

Joker reveal rules:

- After cycle 1, the first shared joker is revealed.
- After cycle 2, the second shared joker is revealed.
- After cycle 3, the third shared joker is revealed.
- Once revealed, a shared joker contributes its rank as a live wildcard rank for everyone.

Action lock rules:

- Cycle 1 is blind-only.
- During forced blind play, `see` is not allowed.
- `show` and `sideshow` stay locked until cycle 4.
- After cycle 4, `show` and `sideshow` unlock.
- Even after unlock, `show` and `sideshow` require all active players to be seen.

Special seen-player rule:

- If exactly one active player remains unseen, that player may continue for one more blind turn.
- After that turn completes, the game automatically marks that player as seen.

Sideshow rule:

- In `jhandu`, sideshow is mandatory-accept.
- The target player cannot deny the request.
- A sideshow timeout resolves the same as accept.

Practical effect:

- Early round play is constrained and information unlocks in stages.
- The table gains joker power gradually as each shared joker rank is revealed.
- Late-round `show` and `sideshow` happen only after enough round progression and full visibility.

## 8. Variant Summary Table

| Variant | Extra jokers | Hand count | Main twist |
| --- | --- | --- | --- |
| `classic` | None | 3 | Standard Teen Patti |
| `ak47` | `A`, `K`, `4`, `7` are wild | 3 | Fixed wildcard ranks |
| `muflis` | None | 3 | Lowest hand wins |
| `flipper` | Public and flipped ranks become wild | 4 dealt, 3 active | Folding Joker style live wildcard growth |
| `jhandu` | 3 shared jokers revealed over cycles | 3 | Progressive joker unlock with delayed `show` and `sideshow` |

## 9. Sources And Notes

Research basis used for implementation:

- `Classic` Teen Patti ranking and `AKQ` / `A23` ordering: [Wikipedia](https://en.wikipedia.org/wiki/Teen_patti)
- `AK47` and Folding Joker-style wildcard behavior: [Teen Patti Cash variations](https://teenpatticash.com/variations/)
- `Muflis`: [Teen Patti Cash variations](https://teenpatticash.com/variations/) and [TeenPatti Royale helpdesk](https://teenpatti-helpdesk.dynamicnext.com/support/solutions/articles/4000206860-variations)
- `Jhandu` / `Zhandu`: [Flickonclick variations](https://www.flickonclick.com/new-teen-patti-variations/) and [BuyFullCode Zhandu description](https://buyfullcode.com/teenpatti-new-variations)
- `Flipper`: implemented using the closest verifiable Folding Joker style ruleset, exposed in-product as `Flipper`.

This document reflects the current project implementation and is the source of truth for this repo even where public naming varies by app or casino.
