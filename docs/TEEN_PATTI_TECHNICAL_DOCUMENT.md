# Teen Patti Technical Document

## 1. Purpose

This document is the engineering source of truth for the current Teen Patti implementation in this repository.

It covers:

- the `client` Next.js application
- the `serverJavaNew` Kotlin/Spring Boot backend
- the integration contract between them
- persistence, realtime, configuration, and local operations

This document is based on the checked-in code and summarizes payloads and state objects where they are large. It is not intended to reproduce every nested DTO field verbatim.

## 2. System Overview

The repository currently contains multiple historical backends, but the active technical stack described here is:

- `client`: Next.js 16 app-router frontend
- `serverJavaNew`: Spring Boot 4 + Kotlin backend

The live product supports:

- public tables
- private rooms
- `classic` variant
- `ak47` variant
- `muflis` variant
- `flipper` variant
- `jhandu` variant

High-level runtime model:

1. The client renders a lobby and table UI through a single game shell.
2. The client joins or restores a session through REST.
3. The client upgrades to a WebSocket connection for live table updates and commands.
4. The backend owns all gameplay state, round transitions, turn timing, settlement, reconnect handling, and persistence.
5. MongoDB stores durable aggregate state.
6. Redis coordinates realtime commands, presence, and cross-node event fan-out.

## 3. Repo Layout

Relevant paths:

- `docs/TEEN_PATTI_FUNCTIONAL_DOCUMENT.md`: product-facing functional behavior
- `docs/TEEN_PATTI_TECHNICAL_DOCUMENT.md`: engineering technical behavior
- `client/app`: Next.js app-router UI
- `client/app/components`: main gameplay UI components
- `client/app/hooks`: public/private gameplay state hooks
- `client/app/lib`: API and client utilities
- `serverJavaNew/src/main/kotlin/org/teenpatti/server`: backend source
- `serverJavaNew/src/main/resources/application.yml`: Spring runtime defaults
- `serverJavaNew/.env.example`: deployment-oriented backend env template

## 4. Client Architecture

### 4.1 Stack

The client is a Next.js 16 application using React 19.

Key packages:

- `next`
- `react`
- `react-dom`
- `socket.io-client` is listed as a dependency, but the current implementation uses native `WebSocket`

### 4.2 Entry Point And UI Shell

The app-router entry point is `client/app/page.js`, which renders `GameClient`.

`GameClient` is the top-level orchestration layer for:

- lobby selection
- switching between public-table and private-room flows
- mobile-orientation enforcement
- gameplay sound controls
- table-level status banners and screen states

Primary user modes inside `GameClient`:

- `classic`
- `ak47`
- `muflis`
- `flipper`
- `jhandu`
- `private`

Public variants share the same hook and backend contract, with the selected variant passed through to the API layer.

### 4.3 Public Table Client Flow

Public-table state is managed by `client/app/hooks/useTeenPattiGame.js`.

Responsibilities:

- create or restore a public session
- keep a per-window session in `sessionStorage`
- connect to `/ws/public-tables`
- authenticate the socket session
- receive live snapshots
- send player actions
- support reconnect with backoff
- handle session expiry/closure

Public session persistence:

- storage key format: `teen-patti-public-session:<variant>:<window-id>`
- storage medium: `sessionStorage`
- scope: browser tab/window via `window.name`

This design keeps public sessions isolated per browser window, which matters because public-table state is seat-specific and reconnect-aware.

### 4.4 Private Room Client Flow

Private-room state is managed by `client/app/hooks/usePrivateRoomGame.js`.

Responsibilities:

- create room sessions
- join room sessions
- restore existing room sessions
- connect to `/ws/private-rooms`
- authenticate and maintain room sync
- send room actions such as lobby configuration updates, round control, and between-round acceptance
- handle reconnect and room expiration

Private-room session persistence:

- storage key format: `teen-patti-private-room-session:<room-code>`
- storage medium: `localStorage`

Unlike public tables, private-room session state is stored across browser restarts, which is useful because room membership is tied to an invitation-style room code rather than a tab-scoped public seat.

### 4.5 API Utility Layer

`client/app/lib/api.js` centralizes:

- API base resolution via `NEXT_PUBLIC_API_BASE`
- request timeouts
- uniform JSON response handling
- WebSocket base URL derivation
- REST helper functions for public and private flows

Environment default:

- `NEXT_PUBLIC_API_BASE=http://localhost:4000/api` in code

Current local repo configuration:

- `client/.env` sets `NEXT_PUBLIC_API_BASE=http://localhost:4100/api`

WebSocket URL derivation:

- REST base `/api` is removed from the configured base URL
- protocol is converted from `http/https` to `ws/wss`
- resulting endpoints are:
  - `/ws/public-tables`
  - `/ws/private-rooms`

### 4.6 Client Seed Handling

`client/app/lib/clientSeed.js` generates a secure 32-byte random seed in hex using `crypto.getRandomValues`.

The client seed is supplied when:

- joining a public table
- creating a private room
- joining a private room

The backend uses these player-provided seeds as part of the provably fair round-deal input.

## 5. Server Architecture

### 5.1 Stack

`serverJavaNew` is a Spring Boot 4 application written in Kotlin.

Primary dependencies:

- Spring Web
- Spring WebSocket
- Spring AMQP
- Spring Data MongoDB
- Spring Data Redis
- Spring Validation
- Spring Actuator

Platform wallet integration:

- platform profile/session reads still use HTTP through `PLATFORM_API_BASE`
- wallet debits use HTTP through `PLATFORM_DEBIT_URL`
- wallet credits publish the existing balance payload shape to RabbitMQ
- when `PLATFORM_ENABLED=true`, required backend env vars include `PLATFORM_API_BASE`, `PLATFORM_DEBIT_URL`, `PLATFORM_AMQP_URL`, `PLATFORM_AMQP_EXCHANGE`, and either `PLATFORM_AMQP_ROUTING_KEY` or `PLATFORM_AMQP_QUEUE_NAME`

### 5.2 Backend Module Layout

Source is organized feature-first under `org.teenpatti.server`:

- `common`: shared helpers, error handling, ids, timing, token support
- `config`: environment binding, bean wiring, runtime config, health, MVC, Redis config
- `game`: shared game engine, round runtime, core models, provably fair helpers
- `publictable`: public table orchestration, models, bots, REST DTOs/controllers
- `privateroom`: private room orchestration, models, realtime gateway, REST DTOs/controllers
- `infrastructure/persistence`: repository ports and Mongo adapters
- `infrastructure/realtime`: Redis bus, presence service, WebSocket handlers

### 5.3 Application And Variant Bootstrap

`GameRuntimeConfig` builds the active game configurations.

Configured variants:

- `classic`
- `ak47`
- `muflis`
- `flipper`
- `jhandu`

Variant bootstrap behavior:

- each public variant creates a separate `GameConfig` instance
- public-table managers are created per variant
- private rooms default to `classic` but can be created with any configured variant
- private-room hosts can update variant and boot amount while the room is still in the lobby
- AK47 wildcard ranks are `A`, `K`, `4`, `7`
- Muflis uses lowball hand comparison
- Flipper uses `cardsPerSeat = 4` and `publicCardMode = third_card_rank_joker`
- Jhandu uses `sharedJokerMode = progressive_three` with forced blind opening and delayed showdown controls

### 5.4 Shared Round Runtime

`game/RoundTableService.kt` is the core gameplay runtime shared across public tables and private rooms.

It owns:

- round creation
- start countdown
- dealing transition
- active turn loop
- legal action processing
- forced pack behavior
- winner resolution
- dealer tip flow
- bankroll and pot updates
- round history persistence
- timer restoration after restart/reload

This service is authoritative for gameplay. The client only renders snapshots and sends commands.

### 5.5 Public Table Orchestration

`publictable/PublicTableManager.kt` wraps `RoundTableService` for public-table behavior.

Responsibilities:

- initialize and restore active public tables from persistence
- create public tables on demand
- issue public player sessions and tokens
- assign players to seated or waiting states
- route actions to the runtime
- support ready-for-next-round flow
- manage leave/disconnect semantics
- trigger reconnect-grace handling
- publish realtime updates

Public tables are continuous-play tables. Players may join mid-round and wait for the next round if the current round is already active.

Bots are a public-table concern and are not used in private rooms.

### 5.6 Private Room Orchestration

`privateroom/PrivateRooms.kt` implements the `PrivateRoom` runtime behavior, and `PrivateRoomManager` manages room lookup, authentication, and ownership.

Responsibilities:

- create rooms and host sessions
- join players by room code
- cap room size at 5 players
- maintain host ownership
- start rounds only when the host requests it
- move to between-round acceptance flow after completion
- reconnect/disconnect handling with forced-pack timeout
- close or expire rooms when appropriate

Private rooms are invitation-style and host-driven. They do not auto-fill with bots.

### 5.7 Persistence Layer

Repository ports are defined in `infrastructure/persistence/RepositoryPorts.kt`.

Durable storage responsibilities:

- `TableAggregateRepository`: public table aggregate snapshots and leases
- `PublicSessionRepository`: public player sessions
- `PrivateRoomRepository`: private room state and leases
- `RoundHistoryRepository`: round history entries

Mongo adapter implementations live under `infrastructure/persistence/mongo`.

The backend persists aggregate snapshots instead of treating the WebSocket layer as the source of truth.

### 5.8 Realtime And Multi-Node Coordination

Redis-backed realtime components live under `infrastructure/realtime`.

Key pieces:

- `RedisRealtimeBus`: command dispatch and event fan-out across nodes
- `RedisPresenceService`: reconnect/presence markers in Redis
- `PublicTableWebSocketHandler`: public table socket endpoint
- `PrivateRoomWebSocketHandler`: private room socket endpoint
- `WebSocketConfig`: WebSocket route registration and allowed origin enforcement

Multi-node design:

1. A WebSocket handler authenticates the player on the node that accepted the socket.
2. The handler sends commands through Redis.
3. The owning node executes the command against the in-memory manager/runtime.
4. The owning node publishes an aggregate event through Redis.
5. Listening socket handlers request a fresh snapshot and push it to connected clients.

Presence is tracked in Redis so disconnect timers can respect reconnect grace windows across nodes.

### 5.9 Provably Fair Support

`game/ProvablyFairSupport.kt` generates deterministic round deals.

Current characteristics:

- version: `pf_v1`
- algorithm label: `HMAC_SHA256_FISHER_YATES`
- server seed is generated server-side per round
- client seeds are included from participating players
- deal input includes variant, table id, round id, participant count, and player seed inputs
- deck shuffle and opening player selection use deterministic randomness derived from the HMAC seed

Stored provably fair state includes:

- version
- algorithm
- round id
- server seed hash
- server seed
- deck hash
- opening player index
- per-player client seed inputs

## 6. End-To-End Runtime Flow

### 6.1 Public Table Flow

1. The client selects one of `classic`, `ak47`, `muflis`, `flipper`, or `jhandu`.
2. The client calls `POST /api/public/join?variant=<variant>`.
3. The server creates or assigns a public-table session and returns session data including `playerId` and `playerToken`.
4. The client stores the session in `sessionStorage`.
5. The client opens `/ws/public-tables`.
6. The client sends `public_table:authenticate`.
7. The backend authenticates via Redis command routing and returns an initial snapshot.
8. During play, the client sends `public_table:action` or `public_table:leave`.
9. Backend state changes publish events, and clients receive updated snapshots.

### 6.2 Private Room Flow

1. The client creates a room with `POST /api/private-rooms` or joins one with `POST /api/private-rooms/join`.
2. The server returns the room session including `roomCode`, `playerId`, and `playerToken`.
3. The client stores the session in `localStorage`.
4. The client opens `/ws/private-rooms`.
5. The client sends `private_room:authenticate`.
6. The backend authenticates and returns `roomState`.
7. While the room is in the lobby, the host may send `private_room:update_config`.
8. The host may send `private_room:start_round`.
9. Players send `private_room:action` during active rounds.
10. After round completion, the room moves into a between-round acceptance flow using `private_room:next_round` and `private_room:accept_next_round`.

## 7. REST API Reference

All documented REST endpoints currently return the envelope shape:

```json
{
  "status": "ok",
  "data": {}
}
```

On failure, the backend returns an error envelope handled by `ApiError` on the client.

### 7.1 Public Table REST Endpoints

#### `POST /api/public/join`

Query params:

- `variant` optional, normalized by the backend; supported values in the current runtime are `classic`, `ak47`, `muflis`, `flipper`, and `jhandu`

Request body:

```json
{
  "playerName": "Player One",
  "clientSeed": "<hex-seed>"
}
```

Behavior:

- creates or assigns a public-table session
- seats the player immediately if allowed, otherwise marks them as waiting for the next round
- returns session data plus player-scoped table snapshot information

Key response fields are summarized and include:

- `playerId`
- `playerToken`
- `playerName`
- `playerStatus`
- `connected`
- `joinedAt`
- `lastSeenAt`
- `table`

#### `GET /api/public/session`

Query params:

- `variant`
- `playerId`
- `playerToken`

Behavior:

- validates an existing session
- marks the player connected
- returns the current player-scoped table snapshot

#### `POST /api/public/action`

Query params:

- `variant`

Request body:

```json
{
  "playerId": "<player-id>",
  "playerToken": "<player-token>",
  "actionType": "blind",
  "payload": {}
}
```

Representative `actionType` values:

- `see`
- `blind`
- `chaal`
- `raise`
- `pack`
- `sideshow`
- `show`
- `dealer_tip`
- `ready_next_round`

Behavior:

- validates session ownership
- applies the action through `PublicTableManager` and `RoundTableService`
- returns the updated player-scoped snapshot

#### `POST /api/public/leave`

Query params:

- `variant`

Request body:

```json
{
  "playerId": "<player-id>",
  "playerToken": "<player-token>"
}
```

Behavior:

- marks the session left
- updates seating/runtime state
- packs the player if they leave while actively participating in a round

### 7.2 Private Room REST Endpoints

#### `POST /api/private-rooms`

Request body:

```json
{
  "roomName": "Weekend Patti",
  "playerName": "Host",
  "clientSeed": "<hex-seed>",
  "variant": "classic",
  "bootAmount": 1000
}
```

Behavior:

- creates a private room
- creates the host player session
- defaults to `classic` and the configured boot amount if `variant` or `bootAmount` is omitted
- returns room session information for the host

#### `POST /api/private-rooms/join`

Request body:

```json
{
  "roomCode": "ABCDEF",
  "playerName": "Player Two",
  "clientSeed": "<hex-seed>"
}
```

Behavior:

- joins an existing room by code
- returns the player session and current room state

#### `GET /api/private-rooms/{roomCode}/session`

Query params:

- `playerId`
- `playerToken`

Behavior:

- validates the room session
- restores room state for reconnecting clients

#### `POST /api/private-rooms/{roomCode}/leave`

Request body:

```json
{
  "playerId": "<player-id>",
  "playerToken": "<player-token>"
}
```

Behavior:

- removes the player from the room
- repacks them if they leave during an active round
- can reassign host ownership if the host leaves

## 8. WebSocket Protocol Reference

The current implementation uses raw browser WebSockets, not Socket.IO, even though `socket.io-client` is listed as a package dependency.

Message pattern:

- client sends JSON messages with `type`, `requestId`, and `payload`
- server replies with `ack` or `error` messages for request/response flows
- server pushes `snapshot` messages for state synchronization

## 8.1 Public Table WebSocket

Endpoint:

- `/ws/public-tables`

Client-to-server message types:

- `public_table:authenticate`
- `public_table:action`
- `public_table:leave`

#### `public_table:authenticate`

Payload:

```json
{
  "variant": "classic",
  "playerId": "<player-id>",
  "playerToken": "<player-token>"
}
```

Behavior:

- validates the public-table session
- binds the socket to the table and player
- marks the player present in Redis
- returns an ack and pushes a snapshot

#### `public_table:action`

Payload:

```json
{
  "actionType": "blind",
  "payload": {}
}
```

Behavior:

- routes the action through Redis to the owning public table manager
- returns `public_table:ack`

#### `public_table:leave`

Payload:

- empty payload is acceptable because player identity is already bound to the authenticated socket session

Server-to-client message types:

- `public_table:ack`
- `public_table:error`
- `public_table:snapshot`
- `public_table:session_closed`

#### `public_table:ack`

Representative shape:

```json
{
  "type": "public_table:ack",
  "requestId": "<request-id>",
  "status": "ok",
  "data": {}
}
```

#### `public_table:error`

Representative shape:

```json
{
  "type": "public_table:error",
  "requestId": "<request-id>",
  "status": "error",
  "code": "request_failed",
  "message": "Request failed."
}
```

#### `public_table:snapshot`

Representative shape:

```json
{
  "type": "public_table:snapshot",
  "eventType": "action",
  "payload": {}
}
```

Snapshot payloads are large and player-scoped. They summarize current table state, round state, seating, player metadata, and history relevant to the authenticated player.

#### `public_table:session_closed`

Sent when the session can no longer be restored or validated. The client clears stored session state when this occurs.

## 8.2 Private Room WebSocket

Endpoint:

- `/ws/private-rooms`

Client-to-server message types:

- `private_room:authenticate`
- `private_room:action`
- `private_room:update_config`
- `private_room:start_round`
- `private_room:next_round`
- `private_room:accept_next_round`
- `private_room:leave`

#### `private_room:authenticate`

Payload:

```json
{
  "roomCode": "ABCDEF",
  "playerId": "<player-id>",
  "playerToken": "<player-token>"
}
```

Behavior:

- validates the room session
- binds the socket to the room and player
- marks the player present in Redis
- returns an ack and pushes the current `roomState`

#### `private_room:action`

Payload:

```json
{
  "actionType": "blind",
  "payload": {}
}
```

Routes gameplay actions for an already authenticated room player.

#### `private_room:update_config`

Payload:

```json
{
  "variant": "ak47",
  "bootAmount": 1000
}
```

Behavior:

- host-only command
- available only while the room is in `lobby`
- updates the private room variant and boot amount
- recreates the room runtime using the selected variant configuration

#### `private_room:start_round`

Payload:

- empty payload

Behavior:

- host-only command
- starts a new round if enough connected players are available

#### `private_room:next_round`

Payload:

- empty payload

Behavior:

- host-only command during `between_rounds`
- attempts to start the next round from currently accepted/connected players

#### `private_room:accept_next_round`

Payload:

- empty payload

Behavior:

- records that the current player accepts participation in the next round

#### `private_room:leave`

Payload:

- empty payload

Server-to-client message types:

- `private_room:ack`
- `private_room:error`
- `private_room:snapshot`

#### `private_room:ack`

Representative shape:

```json
{
  "type": "private_room:ack",
  "requestId": "<request-id>",
  "status": "ok",
  "data": {}
}
```

#### `private_room:error`

Representative shape:

```json
{
  "type": "private_room:error",
  "requestId": "<request-id>",
  "status": "error",
  "code": "request_failed",
  "message": "Request failed."
}
```

#### `private_room:snapshot`

Representative shape:

```json
{
  "type": "private_room:snapshot",
  "eventType": "player_reconnected",
  "payload": {}
}
```

Snapshot payloads are room-scoped and summarized through `roomState`, including:

- room metadata
- host player
- viewer player id and status
- room config
- admission message
- players
- next-round acceptance state
- current round
- room history

## 9. Persistence And Realtime Infrastructure

### 9.1 MongoDB

MongoDB is used for durable state:

- public table aggregate snapshots
- public player sessions
- private room snapshots
- round history

This allows the backend to restore active state during initialization and survive process restarts.

### 9.2 Redis

Redis is used for coordination rather than long-term durability.

Responsibilities:

- command routing between nodes
- aggregate event fan-out
- reconnect presence markers

Important runtime effect:

- a socket handler does not need to own the in-memory aggregate locally
- it can authenticate and then forward actions through Redis to whichever node currently owns the table or room lease

### 9.3 Lease Ownership

Public tables and private rooms use repository-level lease claims so a single node owns active mutation of a given aggregate at a time.

This prevents multiple nodes from mutating the same room or table concurrently while still allowing clients to connect through any node.

## 10. Configuration And Local Setup

### 10.1 Client Configuration

Client configuration is driven by `NEXT_PUBLIC_API_BASE`.

Current local file:

```env
NEXT_PUBLIC_API_BASE=http://localhost:4100/api
```

This value controls:

- REST request base
- derived WebSocket host

### 10.2 Backend Configuration

Runtime defaults are split across:

- `serverJavaNew/src/main/resources/application.yml`
- `serverJavaNew/.env.example`
- `serverJavaNew/src/main/kotlin/org/teenpatti/server/config/AppEnvironmentConfig.kt`

Important backend variables:

- `PORT`
- `CLIENT_ORIGIN`
- `MONGODB_URI`
- `MONGODB_DB_NAME`
- `REDIS_URL`
- `REDIS_KEY_PREFIX`
- `APP_NODE_ID`
- `WS_RECONNECT_GRACE_MS`
- `PRIVATE_ROOM_TTL_MS`
- `TABLE_ID`
- `BOOT_AMOUNT`
- `MAX_POT_AMOUNT`
- `MIN_STAKE`
- `MAX_STAKE`
- `MAX_ROUNDS_BEFORE_FORCED_SHOW`
- `PLAYER_COUNT`
- `MAX_PUBLIC_TABLE_BOTS`
- `CASINO_BOOT_COMMISSION_PERCENT`
- `CASINO_WIN_COMMISSION_PERCENT`
- `INITIAL_BALANCE`
- `TURN_DURATION_MS`
- `PLATFORM_ENABLED`
- `PLATFORM_API_BASE`
- `PLATFORM_DEBIT_URL`
- `PLATFORM_AMQP_URL`
- `PLATFORM_AMQP_EXCHANGE`
- `PLATFORM_AMQP_ROUTING_KEY`
- `PLATFORM_AMQP_QUEUE_NAME`
- `PLATFORM_GAME_ID`

Selected defaults from `AppEnvironmentConfig`:

- port: `4100`
- client origin: `http://localhost:3000`
- MongoDB URI: `mongodb://localhost:27017/teen_patti_casino`
- Redis URL: `redis://localhost:6379`
- reconnect grace: `15000ms`
- private room TTL: `604800000ms`
- player count: `5`

### 10.3 Spring Resource Defaults

`application.yml` also defines:

- Spring app name: `teen-patti-server`
- optional `.env` imports
- Actuator base path: `/management`

### 10.4 Local Run Workflow

Start MongoDB and Redis locally, then run the backend and frontend separately.

Backend:

```bash
cd serverJavaNew
cp .env.example .env
./gradlew bootRun
```

Frontend:

```bash
cd client
npm install
npm run dev
```

### 10.5 Multi-Node Local Testing

The backend is designed for multiple nodes sharing MongoDB and Redis.

Important pieces:

- each node needs a distinct `APP_NODE_ID`
- Redis presence keys use `REDIS_KEY_PREFIX`
- reconnect grace is enforced using `WS_RECONNECT_GRACE_MS`

Example:

```bash
PORT=4100 APP_NODE_ID=node-a ./gradlew bootRun
PORT=4101 APP_NODE_ID=node-b ./gradlew bootRun
```

Point the client at one node with `NEXT_PUBLIC_API_BASE`, then verify reconnect and snapshot behavior while both nodes share the same Redis and MongoDB services.

## 11. Troubleshooting And Developer Notes

### 11.1 Stale Or Missing Public Session

Symptoms:

- `public_table:session_closed`
- public table reload does not restore state

Checks:

- confirm `playerId` and `playerToken` still exist in browser storage
- confirm backend can reach MongoDB for session restore
- confirm variant matches the stored session

### 11.2 Private Room No Longer Available

Symptoms:

- client surfaces `private_room_unavailable`

Checks:

- room may have expired
- room may have been closed because all players left
- room code may be invalid or stale

### 11.3 WebSocket Auth Or Reconnect Issues

Checks:

- verify `CLIENT_ORIGIN` matches the frontend origin
- verify the frontend derives the correct `ws://` or `wss://` URL from `NEXT_PUBLIC_API_BASE`
- verify Redis is reachable
- verify `APP_NODE_ID` is unique per node in multi-node runs

### 11.4 Behavior Boundaries

Important system boundaries:

- gameplay authority is server-only
- the client must treat snapshots as source of truth
- public-table sessions are tab/window scoped
- private-room sessions are browser persistent and keyed by room code
- private rooms support the same configured variants as public tables, but only the host can update room settings and only while the room is in the lobby
- payload examples in this document are representative and not exhaustive

### 11.5 Historical Code In Repo

The repository still contains older backend directories, but this document describes the active `client` + `serverJavaNew` stack only.
