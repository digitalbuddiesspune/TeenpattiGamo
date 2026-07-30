# serverJavaNew

Kotlin/Spring Boot rewrite of the Teen Patti backend with Redis-backed realtime routing and reconnect handling.

## Documentation

Primary developer documentation:

- [Teen Patti Technical Document](../docs/TEEN_PATTI_TECHNICAL_DOCUMENT.md)
- [Teen Patti Functional Document](../docs/TEEN_PATTI_FUNCTIONAL_DOCUMENT.md)
- [Teen Patti Variant Rules](../docs/TEEN_PATTI_VARIANT_RULES.md)
- [Teen Patti Deployment Document](../docs/TEEN_PATTI_DEPLOYMENT_DOCUMENT.md)

This includes:

- client and backend architecture
- public and private-room variants: `classic`, `ak47`, `muflis`, `flipper`, and `jhandu`
- module layout
- Mongo and Redis responsibilities
- REST API
- WebSocket protocol
- provably fair flow
- environment variables
- local setup
- deployment
- scaling
- troubleshooting

Platform note:

- `PLATFORM_API_BASE` is still used for player profile and session lookups.
- wallet debit/credit writes now publish JSON messages to RabbitMQ using `PLATFORM_AMQP_URL`, `PLATFORM_AMQP_EXCHANGE`, and `PLATFORM_AMQP_ROUTING_KEY`.

## Module layout

Source is now organized feature-first under `src/main/kotlin/org/teenpatti/server`:

- `common`
  - shared exceptions, API helpers, ids, clocks, schedulers, token helpers
- `config`
  - environment loading, shared beans, Redis wiring, MVC config, health and exception handling
- `game`
  - shared round engine, runtime service, provably fair helpers, common round models
- `publictable`
  - public table manager, bot decision engine, public API DTOs/controllers, public state models
- `privateroom`
  - private room manager/runtime, room API DTOs/controllers, private room state models
- `infrastructure/persistence`
  - repository ports plus Mongo adapters in `mongo`
- `infrastructure/realtime`
  - Redis command bus, presence service, websocket handlers, realtime gateways

Tests are split under `src/test/kotlin/org/teenpatti/server` into:

- `publictable`
- `privateroom`
- `game`
- `config`
- `support`

## Local setup

1. Copy `.env.example` to `.env`.
2. Start Redis with Docker:

```bash
docker run --name teenpatti-redis -p 6379:6379 -d redis:7-alpine
```

3. Start MongoDB locally or point `MONGODB_URI` at your existing cluster.
4. Run the server:

```bash
./gradlew bootRun
```

## Redis quick checks

```bash
docker ps
docker exec -it teenpatti-redis redis-cli ping
```

Expected response:

```text
PONG
```

## Two-node local scaling test

Use a shared MongoDB and Redis, then run two server instances with different ports and node ids.

Terminal 1:

```bash
PORT=4100 APP_NODE_ID=node-a ./gradlew bootRun
```

Terminal 2:

```bash
PORT=4101 APP_NODE_ID=node-b ./gradlew bootRun
```

Point the client at either node with `NEXT_PUBLIC_API_BASE=http://localhost:4100/api` or `http://localhost:4101/api`.

## Variant Notes

The active public-table runtime exposes five variants:

- `classic`
- `ak47`
- `muflis`
- `flipper`
- `jhandu`

Private rooms use the same variant set. The host selects the variant and boot amount when creating the room, and can update those settings while the room is still in the lobby.

Alias notes used across product and QA documentation:

- `flipper` is implemented as a Folding Joker style variation
- `jhandu` corresponds to the commonly published `zhandu` rules family

Authoritative gameplay details live in [Teen Patti Variant Rules](../docs/TEEN_PATTI_VARIANT_RULES.md).
