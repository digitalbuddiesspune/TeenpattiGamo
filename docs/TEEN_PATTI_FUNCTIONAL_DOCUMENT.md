# Teen Patti Functional Document

## 1. Purpose

This document explains how Teen Patti behaves from a player, product, and QA point of view. It is intentionally non-technical and focuses on gameplay, user flows, game modes, and expected outcomes.

This document reflects the current product behavior only.

## 2. Product Overview

Teen Patti offers two main ways to play:

- Public tables
- Private rooms

Both public tables and private rooms support five gameplay variants:

- `classic`
- `ak47`
- `muflis`
- `flipper`
- `jhandu`

Players begin from a lobby-style experience and move into a live table view where rounds are played continuously or under host control, depending on the mode.

## 3. Game Modes

### 3.1 Public Tables

Public tables are open games where players enter shared tables.

Functional behavior:

- A player can join a public game and be placed into the active table flow.
- If a round is already in progress, a joining player may wait for the next round instead of entering immediately.
- Empty seats may be filled so the table can keep moving.
- Public play is designed for continuous round flow.
- A player who briefly leaves and returns should be able to resume where allowed.

Public tables are intended to feel fast, always available, and continuously active.

### 3.2 Private Rooms

Private rooms are invite-style games for a selected group of players.

Functional behavior:

- One player creates the room and becomes the host.
- The host chooses the room variant and boot amount during room creation.
- The host can update the room variant and boot amount while the room is still in the lobby.
- Other players join using the room code.
- The room supports up to 5 players.
- The host controls when a round starts.
- If someone joins during an ongoing round, they may wait for the next round.
- Between rounds, the room uses a group confirmation flow before continuing.

Private rooms are intended for controlled play with friends or invited players.

### 3.3 Bots in Public Tables

Bots are used only in public tables.

Functional behavior:

- Bots help keep public tables active when there are not enough human players.
- Bots can fill open seats so rounds can start and continue more smoothly.
- Bots do not replace private-room players and are not part of private rooms.
- Bot participation is limited, so public tables do not become fully bot-driven unless allowed by current settings.

From a product point of view, bots exist to reduce waiting time and maintain game flow in public play.

## 4. Variants

Detailed per-variant rule definitions live in:

- [Teen Patti Variant Rules](./TEEN_PATTI_VARIANT_RULES.md)

### 4.1 Classic

`classic` follows standard Teen Patti hand behavior with no wildcard cards.

### 4.2 AK47

`ak47` is a supported variation.

In this mode:

- `A`
- `K`
- `4`
- `7`

act as wildcard ranks.

This changes hand strength and can create stronger outcomes than standard play.

### 4.3 Muflis

`muflis` is the lowball variation.

In this mode:

- standard Teen Patti dealing and action flow still apply
- the weakest normal hand wins instead of the strongest
- lower tiebreak ranks beat higher tiebreak ranks within the same hand category

### 4.4 Flipper

`flipper` is the product label for a Folding Joker style variation.

In this mode:

- each player is dealt 4 cards
- only 3 cards are used as the active Teen Patti hand
- the 3rd active card is exposed publicly and its rank becomes a live joker rank for everyone
- when a player packs, that player’s hidden reserve card is revealed and its rank also becomes a live joker rank

### 4.5 Jhandu

`jhandu` is the product label for the commonly described `zhandu` variation.

In this mode:

- 3 shared joker cards unlock over betting cycles
- cycle 1 is blind-only
- `show` and `sideshow` unlock only after later cycle progression
- all active players must be seen before `show` or `sideshow` can be used

## 5. Core Rules

### 5.1 Table Size

The game is built around a 5-player table.

Round expectations:

- At least 2 players are needed for a round.
- No more than 5 players can participate in one round.
- Every round begins with all participating players contributing the boot amount.

### 5.2 Hand Ranking

Hands are resolved in this order, from strongest to weakest:

1. Trail
2. Pure Sequence
3. Sequence
4. Color
5. Pair
6. High Card

The winner is always determined by the game.

### 5.3 Stake Behavior

The game distinguishes between blind play and seen play.

Functional expectations:

- Blind players continue with the lower effective stake level.
- Seen players play at a higher effective stake level.
- Raising increases the amount required for the next action.
- Seeing cards changes the player’s betting behavior for the rest of the round.

## 6. Round Flow

Each round moves through a clear sequence:

1. Round begins
2. Cards are dealt
3. Active play starts
4. The round is resolved
5. The next-round flow begins

### 6.1 Round Start

At the start of a round:

- all active participants are included
- the boot amount is collected
- cards are prepared and assigned
- a starting player is chosen

There is a visible pre-play moment before the action begins.

### 6.2 Dealing

After round start:

- cards are dealt to each participating player
- the table transitions into active play

This stage is presented as a visible dealing phase before decisions begin.

### 6.3 Active Play

During active play:

- one player acts at a time
- the turn moves around the table
- players continue until one player remains or a final comparison occurs

Gameplay is turn-based and cannot be skipped freely.

### 6.4 Round Completion

When a round ends:

- the winner is identified
- the winning hand is shown
- winnings and deductions are applied
- the table moves toward the next round

What happens next depends on the mode:

- Public tables continue through a readiness flow
- Private rooms move into a between-round confirmation flow

## 7. Player Actions

The main player actions are:

- Blind
- Chaal
- Raise
- See
- Pack
- Side Show
- Show
- Dealer Tip

### 7.1 Blind

Blind is the basic ongoing action for a player who has not seen cards.

Expected behavior:

- available only on the player’s turn
- keeps the player in the round

### 7.2 Chaal

Chaal is the ongoing action for a player who has seen cards.

Expected behavior:

- available only after cards have been seen
- follows the seen-player betting level

### 7.3 Raise

Raise increases the pressure on the table.

Expected behavior:

- available on the player’s turn
- increases the amount expected from following players

### 7.4 See

See allows a player to look at their own cards.

Expected behavior:

- available only before the player has already seen cards
- changes later stake behavior for that player

### 7.5 Pack

Pack removes a player from the current round.

Expected behavior:

- packed players do not return to the same round
- if only one player remains active, the round ends immediately

### 7.6 Side Show

Side Show is a conditional comparison action available only in certain active-round situations.

Functional expectations:

- it is not always available
- it depends on the situation in the round and the players involved
- it can remove one player from contention

### 7.7 Show

Show is the final comparison action when only two active players remain.

Expected behavior:

- it ends the round immediately
- the stronger hand wins

### 7.8 Dealer Tip

Dealer Tip is a post-round choice made by the winner.

Expected behavior:

- it happens after the winner is decided
- it reduces the final amount taken by the winner
- if no tip is given, the winner keeps the full eligible amount

## 8. Public Table Flow

### 8.1 Joining

When a player enters public play:

- they are admitted into a public table flow
- if space is available for immediate play, they are included directly
- if a round is already underway, they may wait for the next round

### 8.2 Waiting for Next Round

If a player arrives during an ongoing public round:

- they may remain in a waiting position
- they cannot affect the current round
- they join once the next eligible round begins

### 8.3 Continuous Table Movement

Public tables are designed to keep moving.

This means:

- rounds should continue regularly
- open spots should be filled where possible
- players who are ready for continued play should move smoothly into the next round

### 8.4 Leaving and Returning

Expected behavior:

- a player can leave public play
- a player who briefly disconnects may be able to return
- absence during active play can lead to loss of participation in that round

## 9. Private Room Flow

### 9.1 Creating a Room

When a room is created:

- the creator becomes the host
- the room is given a shareable code
- the room stays in the lobby until enough players are present

### 9.2 Joining a Room

When a player joins a room:

- they become visible to the room
- they can take part when the room is ready
- if they join during an active round, they may wait for the next one

### 9.3 Host Control

The host has special responsibilities.

Expected host privileges:

- start the round
- choose room settings during creation
- update room settings while the room is still in the lobby
- guide the room from lobby into play
- help move the room into the next round when the group is ready

### 9.4 Between Rounds

Private rooms do not simply auto-continue.

After a round finishes:

- the room pauses between rounds
- connected players confirm whether they want to continue
- once the group is ready, the next round starts

This makes private rooms feel deliberate and social rather than automatic.

### 9.5 Leaving a Room

Expected behavior:

- players can leave the room
- if the host leaves, host responsibility moves to another player
- if everyone leaves, the room eventually becomes unavailable

## 10. Reconnect and Absence Behavior

The game is designed to handle brief interruption.

Functional expectations:

- a player who briefly drops out may be able to return
- the game itself continues even if a player disconnects
- long enough absence can remove a player from active participation
- in live play, an absent player may be treated as folded out of the round

The intended experience is that the table should continue rather than pause for one disconnected player.

## 11. Fairness Model

The game includes a fairness system for round setup.

Product expectations:

- each round is prepared in a way that can be verified after the round
- the hidden part of the round setup stays hidden while the round is live
- the reveal happens only after the round is complete

For QA, the key behavior is timing:

- the hidden proof is not fully visible before the round ends
- the full reveal becomes available after the round ends

## 12. Limits and Product Constraints

Important product-level constraints:

- maximum table size is 5
- rounds require at least 2 participants
- the game uses a fixed boot structure
- there is a defined maximum stake range
- public tables allow a limited number of bots
- rounds follow a timed turn system
- winnings are affected by game deductions and optional dealer tip

These limits should remain consistent in the user experience.

## 13. Winnings and Deductions

At the end of a round:

- the winner is identified
- the total amount won is calculated
- game deductions are applied
- optional dealer tip is applied if selected
- the final credited amount is shown

QA should treat the displayed final result as the authoritative round outcome.

## 14. Important Edge Cases

The following behaviors are important for validation:

- joining a public table during active play
- joining a private room during active play
- trying to start a private room without enough players
- host leaving a private room
- player disconnecting during a live turn
- player returning before the round fully moves on
- pack reducing the round to one remaining player
- show ending the round with two players left
- side show appearing only in the right situations
- fairness reveal appearing only after round completion

## 15. QA Validation Checklist

### 15.1 Public Tables

- Join `classic` public play and confirm normal table flow.
- Join `ak47` public play and confirm wildcard-based variation.
- Join `muflis` public play and confirm that the weakest valid hand wins.
- Join `flipper` public play and confirm that public joker ranks grow from exposed cards and packed reserve cards.
- Join `jhandu` public play and confirm blind-only opening flow, progressive shared jokers, and delayed `show` / `sideshow`.
- Join during an active round and confirm waiting behavior.
- Join public play with low human participation and confirm that bots help maintain table flow where expected.
- Return after a brief interruption and confirm recovery where expected.
- Leave public play and confirm removal from ongoing participation.
- Complete a round and confirm the next-round flow behaves correctly.

### 15.2 Private Rooms

- Create a room and confirm host assignment.
- Create private rooms with each supported variant and confirm the selected ruleset is used.
- Update the private room variant and boot amount while in the lobby and confirm non-host players cannot update them.
- Join a room with multiple players and confirm lobby behavior.
- Start a round as host and confirm that non-host players do not control round start.
- Join during an active round and confirm delayed entry where applicable.
- Finish a round and confirm between-round confirmation behavior.
- Leave as host and confirm host transfer.
- Leave as the final player and confirm the room no longer remains available.

### 15.3 Gameplay

- Validate blind and seen play differences.
- Validate that seeing cards changes later decisions.
- Validate that raising changes pressure on following players.
- Validate that pack removes a player from the round.
- Validate that side show appears only in valid situations.
- Validate that show appears only when two active players remain.
- Validate that the displayed winner and hand result match the round outcome.

### 15.4 Fairness

- Confirm that each round has a hidden fairness setup during play.
- Confirm that the full fairness reveal appears only after the round is over.
- Confirm that `ak47` wildcard behavior changes hand outcomes as expected.
- Confirm that `muflis` reverses hand strength and tiebreak direction.
- Confirm that `flipper` reveals and preserves joker ranks as cards become public.
- Confirm that `jhandu` reveals shared jokers by cycle and only enables `show` / `sideshow` after the documented unlock conditions.

## 16. Game Environment Variables

The product behavior can be adjusted through a small set of game-related environment variables. These are the variables that directly affect table rules, round timing, player limits, bots, and the game economy.

### 16.1 Round Timing

- `WS_RECONNECT_GRACE_MS`
  Controls how long a disconnected player has to return before being treated as gone.
- `TURN_DURATION_MS`
  Controls how long each turn lasts.
- `MAX_ROUNDS_BEFORE_FORCED_SHOW`
  Limits how long a round can continue before a forced ending condition applies.

### 16.2 Table Rules

- `TABLE_ID`
  Sets the table identity used for the game setup.
- `BOOT_AMOUNT`
  Sets the boot amount collected from each participating player at round start.
- `MAX_POT_AMOUNT`
  Sets the upper limit for the pot.
- `MIN_STAKE`
  Sets the minimum stake level.
- `MAX_STAKE`
  Sets the maximum stake level.
- `PLAYER_COUNT`
  Sets the maximum number of players at the table.

### 16.3 Bots and Economy

- `MAX_PUBLIC_TABLE_BOTS`
  Sets the maximum number of bots allowed in public tables.
- `CASINO_BOOT_COMMISSION_PERCENT`
  Sets the commission taken from boot contribution.
- `CASINO_WIN_COMMISSION_PERCENT`
  Sets the commission taken from winnings.
