# Teen Patti Deployment Document

## 1. Purpose

This document describes how to deploy the active Teen Patti application in this repository.

Active deployable services:

- `client`: Next.js 16 frontend
- `serverJavaNew`: Kotlin/Spring Boot 4 backend
- MongoDB: durable game, room, session, wallet, and history persistence
- Redis: realtime coordination, presence, reconnect handling, and cross-node fan-out
- RabbitMQ or compatible AMQP broker: platform wallet credit publishing when platform mode is enabled


## 2. Runtime Architecture

The production deployment should expose two public entry points:

- Frontend web URL, for example `https://teenpatti.example.com`
- Backend API/WebSocket URL, for example `https://teenpatti-api.example.com`

The client calls the backend REST API under `/api` and opens native WebSocket connections at:

- `/ws/public-tables`
- `/ws/private-rooms`

The backend connects to MongoDB, Redis, and optionally the platform HTTP and AMQP services.

## 3. Prerequisites

Required infrastructure:

- Node.js version compatible with Next.js 16
- npm
- Java 25 toolchain for the Gradle build
- MongoDB database or MongoDB Atlas cluster
- Redis 7 compatible instance
- TLS-capable reverse proxy or managed hosting layer
- RabbitMQ or compatible AMQP broker if `PLATFORM_ENABLED=true`

Required network access:

- Browser to frontend over HTTPS
- Browser to backend HTTPS and WSS
- Backend to MongoDB
- Backend to Redis
- Backend to platform HTTP APIs when platform mode is enabled
- Backend to AMQP broker when platform mode is enabled

## 4. Backend Configuration

The backend reads Spring configuration from environment variables and optionally from `serverJavaNew/.env`.

Start from the checked-in template:

```bash
cd serverJavaNew
cp .env.example .env
```

For production, configure these variables in the hosting platform or process manager rather than relying only on a checked-out `.env` file.

### 4.1 Required Backend Environment

```env
SPRING_PROFILES_ACTIVE=production
PORT=4100
CLIENT_ORIGIN=https://teenpatti.example.com

MONGODB_URI=mongodb+srv://USERNAME:PASSWORD@cluster.example.mongodb.net/?appName=Cluster0
MONGODB_DB_NAME=teen_patti_casino_production

REDIS_URL=redis://:PASSWORD@redis.example.com:6379/0
REDIS_KEY_PREFIX=teen-patti-v2
APP_NODE_ID=teen-patti-api-1
```

Notes:

- `CLIENT_ORIGIN` must exactly match the public frontend origin used by browsers.
- Use a unique `APP_NODE_ID` for each backend instance. If omitted, the server generates one at startup.
- Use one shared `MONGODB_URI`, `MONGODB_DB_NAME`, `REDIS_URL`, and `REDIS_KEY_PREFIX` across all backend instances in the same environment.
- Use different database names or Redis key prefixes for staging and production.

### 4.2 Gameplay Environment

These values control the public table defaults and round timing:

```env
WS_RECONNECT_GRACE_MS=15000
PRIVATE_ROOM_TTL_MS=604800000
TABLE_ID=teen-patti-premium-v2
BOOT_AMOUNT=1000
MAX_POT_AMOUNT=320000
MIN_STAKE=1000
MAX_STAKE=64000
MAX_ROUNDS_BEFORE_FORCED_SHOW=18
PLAYER_COUNT=5
MAX_PUBLIC_TABLE_BOTS=2
CASINO_BOOT_COMMISSION_PERCENT=5
CASINO_WIN_COMMISSION_PERCENT=10
INITIAL_BALANCE=30000000
TURN_DURATION_MS=15000
```

Validation constraints enforced at startup:

- `MAX_PUBLIC_TABLE_BOTS` must be at least `1`.
- `MAX_PUBLIC_TABLE_BOTS` must be less than `PLAYER_COUNT`.

### 4.3 Platform Integration Environment

If the game is deployed with the external platform wallet/session integration, enable platform mode:

```env
PLATFORM_ENABLED=true
PLATFORM_API_BASE=https://sp.adminsportal.com/operator
PLATFORM_DEBIT_URL=https://sp.adminsportal.com/service/operator/user/balance/v2
PLATFORM_AMQP_URL=amqp://USERNAME:PASSWORD@rabbitmq.example.com:5672
PLATFORM_AMQP_EXCHANGE=/games/admin
PLATFORM_AMQP_ROUTING_KEY=games_cashout
PLATFORM_GAME_ID=2
PLATFORM_PUB_KEY=
PLATFORM_SECRET=
PLATFORM_LOGIN_CALLBACK_URL=
```

When `PLATFORM_ENABLED=true`, the backend requires:

- `PLATFORM_API_BASE`
- `PLATFORM_DEBIT_URL`
- `PLATFORM_AMQP_URL`
- `PLATFORM_AMQP_EXCHANGE`
- `PLATFORM_AMQP_ROUTING_KEY` or `PLATFORM_AMQP_QUEUE_NAME`

If platform mode is not needed, set:

```env
PLATFORM_ENABLED=false
```

## 5. Frontend Configuration

The production frontend is deployed as a static Next.js export. The frontend needs the public backend API base at build time:

```env
NEXT_PUBLIC_API_BASE=https://teenpatti-api.example.com/api
```

The client derives WebSocket URLs from this value:

- `https://.../api` becomes `wss://.../ws/public-tables`
- `https://.../api` becomes `wss://.../ws/private-rooms`

Do not point production frontend builds at localhost. If `NEXT_PUBLIC_API_BASE` is omitted, the code falls back to `http://localhost:4000/api`, which is only suitable for local development.

For single-EC2 deployments where Nginx serves the static frontend and proxies backend traffic on the same host, use the same public origin with the `/api` path:

```env
NEXT_PUBLIC_API_BASE=http://YOUR_EC2_PUBLIC_IP/api
```

The static export uses query-string routes for table entry points:

- Public tables: `/public?variant=classic`
- Private rooms: `/private?roomCode=ABCDEF`

Do not link production static pages to dynamic app-router paths such as `/public/classic` or `/private/ABCDEF`; those paths require server rendering or explicit static generation.

## 6. Build And Release

Run validation before packaging:

```bash
cd client
npm ci
npm run lint
npm run build
```

```bash
cd serverJavaNew
./gradlew test
./gradlew bootJar
```

The backend build produces a Spring Boot jar under:

```text
serverJavaNew/build/libs/
```

The client build produces a Next.js production build under:

```text
client/out/
```

For memory-constrained EC2 instances such as `t3.medium`, build the frontend on your local machine and upload only the static `out` directory:

```bash
export EC2_IP="YOUR_EC2_PUBLIC_IP"
export EC2_USER="ubuntu"
export PEM="$HOME/path/to/your-key.pem"
export PROJECT="$HOME/Desktop/craftProjects/gd/Teenpatti/TeenPattiMain"

cd "$PROJECT/client"
cat > .env.production <<EOF
NEXT_PUBLIC_API_BASE=http://$EC2_IP/api
EOF

npm ci
NODE_OPTIONS="--max-old-space-size=1024" npm run build

ssh -i "$PEM" "$EC2_USER@$EC2_IP" \
  "sudo mkdir -p /var/www/teenpatti && sudo chown -R ubuntu:ubuntu /var/www/teenpatti"

rsync -azP --delete \
  -e "ssh -i $PEM" \
  "$PROJECT/client/out/" \
  "$EC2_USER@$EC2_IP:/var/www/teenpatti/"
```

## 7. Starting Services

### 7.1 Backend

From the backend directory:

```bash
cd serverJavaNew
java -jar build/libs/serverJavaNew-0.0.1-SNAPSHOT.jar
```

For process managers such as systemd, PM2, Docker, or a managed app platform, set the environment variables from section 4 and run the same jar command.

Health check endpoint:

```text
GET /health
```

Expected healthy response shape:

```json
{
  "status": "ok",
  "timestamp": "2026-05-02T00:00:00Z",
  "database": "teen_patti_casino_production",
  "redis": "redis://...",
  "nodeId": "teen-patti-api-1"
}
```

### 7.2 Frontend

The production frontend is static and is served by Nginx from:

```text
/var/www/teenpatti
```

Do not run `next start` for the static-export deployment.

## 8. Reverse Proxy Requirements

Nginx should serve the frontend files and proxy backend traffic. The backend route must support normal HTTP requests and WebSocket upgrades.

Required backend paths:

- `/api/**`
- `/ws/public-tables`
- `/ws/private-rooms`
- `/management/**`
- `/health` if enabled in the backend profile

Reverse proxy requirements:

- Preserve `Host`, `X-Forwarded-Proto`, and `X-Forwarded-For` headers.
- Allow WebSocket upgrade headers.
- Use HTTPS for public traffic.
- Use WSS for browser WebSocket traffic.
- Do not cache API or WebSocket responses.

Example Nginx WebSocket settings:

```nginx
# Place in http {} context (e.g. /etc/nginx/nginx.conf), not inside server {}.
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $connection_upgrade;
proxy_set_header Host $host;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

If the browser shows `Unexpected response code: 400` and the API body is
`Can "Upgrade" only to "WebSocket".`, Nginx is proxying `/ws/` to Spring but
**not forwarding the Upgrade headers**. Fix the `/ws/` location, reload Nginx,
then retest.

Also set production `CLIENT_ORIGIN` to the exact frontend origin(s), for example:

```env
CLIENT_ORIGIN=https://doormart.shop,https://www.doormart.shop
```

A mismatched Origin returns `403` on the handshake.

Example single-EC2 Nginx site for static frontend plus backend proxy:

```nginx
server {
    listen 80;
    server_name _;

    root /var/www/teenpatti;
    index index.html;

    location /api/ {
        proxy_pass http://127.0.0.1:4100/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /management/ {
        proxy_pass http://127.0.0.1:4100/management/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:4100/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location / {
        try_files $uri $uri.html $uri/ /index.html;
    }
}
```

## 9. Multi-Instance Backend Deployment

The backend is designed to run more than one instance when all instances share MongoDB and Redis.

For each instance:

- Use the same `MONGODB_URI`.
- Use the same `MONGODB_DB_NAME`.
- Use the same `REDIS_URL`.
- Use the same `REDIS_KEY_PREFIX`.
- Use a different `APP_NODE_ID`.

Load balancing notes:

- WebSockets are stateful connections, so configure the load balancer for long-lived connections.
- Sticky sessions are helpful but Redis-backed routing is still used for cross-node realtime coordination.
- Rolling deploys should keep at least one old instance alive until new instances pass `/health`.

## 10. Deployment Checklist

Before deployment:

- Confirm MongoDB and Redis are reachable from the backend runtime network.
- Confirm `CLIENT_ORIGIN` exactly matches the frontend domain.
- Confirm `NEXT_PUBLIC_API_BASE` points to the public backend `/api` URL.
- Confirm `NEXT_PUBLIC_API_BASE` was written to `client/.env.production` before building the static export.
- Confirm platform variables are present when `PLATFORM_ENABLED=true`.
- Run `npm run lint`, `npm run build`, and `./gradlew test`.
- Build the backend jar with `./gradlew bootJar`.

After deployment:

- Open the frontend URL.
- Call backend `/health`.
- Create or join a public table for each supported variant: `classic`, `ak47`, `muflis`, `flipper`, and `jhandu`.
- Verify static routes load from query URLs such as `/public?variant=classic` and `/private?roomCode=ABCDEF`.
- Create a private room and join it from a second browser session.
- Verify WebSocket connections use `wss://` in browser developer tools.
- Verify MongoDB collections receive session, table, room, wallet, and history data.
- Verify Redis connections and key prefix usage.
- If platform mode is enabled, verify profile lookup, debit calls, and AMQP credit publishing.

## 11. Rollback

Keep the previous backend jar and frontend build artifact available for rollback.

Recommended rollback sequence:

1. Stop routing new traffic to the new backend instances.
2. Route traffic back to the previous backend version.
3. Roll back the frontend build if the deployed frontend requires backend behavior that is not present in the previous backend.
4. Confirm `/health`, public tables, private rooms, and platform settlement behavior.

Schema changes should be handled cautiously. This codebase currently relies on MongoDB document compatibility rather than a checked-in migration framework, so do not deploy incompatible persistence changes without a migration and rollback plan.

## 12. Troubleshooting

Common issues:

- CORS failures: verify `CLIENT_ORIGIN` exactly matches the browser origin.
- Client connects to localhost in production: verify `NEXT_PUBLIC_API_BASE` was set before `npm run build`.
- Public or private table entry appears stuck after static deployment: verify links use `/public?variant=...` and `/private?roomCode=...`, then rebuild and re-upload `client/out`.
- WebSocket fails after REST succeeds: verify reverse proxy upgrade headers and public `wss://` routing.
- Backend fails at startup: check required platform variables when `PLATFORM_ENABLED=true`.
- Public table startup fails: check `MAX_PUBLIC_TABLE_BOTS` is at least `1` and less than `PLAYER_COUNT`.
- Reconnect behavior is inconsistent across backend instances: verify all instances share the same Redis URL and key prefix.
- Staging and production data mix: verify distinct MongoDB database names and Redis key prefixes.
