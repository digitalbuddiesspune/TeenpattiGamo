# Node API

This document covers the APIs exposed by the Node.js service in `server/`.

## Node.js Service

The Node.js service is a small Express API for Teen Patti round history,
earnings summaries, and round-detail viewing.

Default entrypoint:

```bash
cd server
npm install
npm start
```

Default port: `4200`

Base URL:

```text
http://localhost:4200
```

## Environment Variables

| Variable | Required | Default | Purpose |
| --- | --- | --- | --- |
| `PORT` | No | `4200` | HTTP port for the Node service. |
| `CORS_ORIGIN` | No | `*` | Allowed CORS origin. |
| `MONGODB_URI` | Yes | none | MongoDB connection string. |
| `MONGODB_DB_NAME` | No | `teen_patti_casino` | MongoDB database name. |

## API: Health Check

```http
GET /health
```

Checks the API process and MongoDB.

Success response:

```json
{
  "status": "ok",
  "api": "ok",
  "mongo": "ok"
}
```

If MongoDB is unavailable, the endpoint returns `503` with
`status: "degraded"` and an error message for the failed dependency.

## API: Paginated Teen Patti Rounds

```http
GET /api/teen-patti/rounds?page=1&pageSize=20
```

Returns completed Teen Patti round history from the `round_history` MongoDB
collection, sorted by latest `settledAt` first.

Query parameters:

| Parameter | Required | Default | Rules |
| --- | --- | --- | --- |
| `page` | No | `1` | Integer greater than or equal to `1`. |
| `pageSize` | No | `20` | Integer from `1` to `100`. |

Example response:

```json
{
  "data": [
    {
      "roundId": "round-id",
      "game": "teen-patti",
      "variantId": "classic",
      "aggregateType": "public_table",
      "aggregateId": "lobby-id",
      "startedAt": "2026-06-29T12:00:00Z",
      "settledAt": "2026-06-29T12:03:00Z",
      "potAmount": 4000,
      "participants": [
        {
          "playerId": "player-1",
          "name": "Player 1",
          "isBot": false,
          "totalBetAmount": 2000,
          "packed": false,
          "seen": true
        }
      ],
      "winner": {
        "playerId": "player-1",
        "name": "Player 1",
        "winningHand": "Trail",
        "wonAmount": 3600,
        "winnerReceivableBeforeTip": 3800,
        "dealerTip": 200
      },
      "bets": [
        {
          "id": "action-1",
          "playerId": "player-1",
          "actionType": "blind",
          "amount": 1000,
          "timestamp": "2026-06-29T12:01:00Z",
          "note": null
        }
      ]
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "count": 1,
    "hasMore": false,
    "nextPage": null
  }
}
```

Validation errors return JSON:

```json
{
  "error": {
    "code": "bad_request",
    "message": "pageSize must be an integer between 1 and 100."
  }
}
```

## API: Earnings Summary Excluding Bots

```http
GET /api/teen-patti/earnings-summary?operator_id=OPERATOR&from=2026-07-01&to=2026-07-20
```

Returns a JSON casino earnings summary from settled `round_history` rows. The
main `totalEarnings` value uses `actualCasinoIncomeTotal`, which is produced by
the game server from real-player money only and excludes bot transactions.

This endpoint does not require API-key authentication.

Query parameters:

| Parameter | Required | Rules |
| --- | --- | --- |
| `operator_id` | No | Limits the summary to rounds with `succeeded` or `applied` `wallet_transactions` for that operator. |
| `from` | No | Inclusive settled date lower bound. Accepts ISO timestamp or `YYYY-MM-DD`. |
| `to` | No | Inclusive settled date upper bound. Accepts ISO timestamp or `YYYY-MM-DD`. |

For date-only values, `from` starts at `00:00:00.000Z` and `to` ends at
`23:59:59.999Z`.

Operator filter behavior:

1. Reads distinct `roundId` values from `wallet_transactions`.
2. Only includes wallet rows with status `succeeded` or `applied`.
3. Matches the supplied operator against `platformOperatorId`,
   `requestPayload.operator_id`, or `requestPayload.operatorId`.
4. Aggregates only `round_history` documents whose `_id` is in those round ids.

Response fields:

| Field | Meaning |
| --- | --- |
| `currency` | Static display currency, currently `INR`. |
| `totalEarnings` | Sum of `actualCasinoIncomeTotal`; this is the bot-excluded earnings total. |
| `roundCount` | Count of settled rounds included in the summary. |
| `realPlayerContributionTotal` | Sum of real-player contributions across included rounds. |
| `botContributionTotal` | Sum of bot contributions, shown for audit comparison only. |
| `payoutTotal` | Sum of final payouts across included rounds. |
| `actualBootCommissionTotal` | Sum of boot commission calculated from real-player seats. |
| `actualWinCommissionTotal` | Sum of win commission calculated from real-player money. |
| `dealerTipTotal` | Sum of dealer tips across included rounds. |
| `filters` | Normalized `operatorId`, `from`, and `to` values used for the query. |

Example response:

```json
{
  "data": {
    "currency": "INR",
    "totalEarnings": 6000,
    "roundCount": 12,
    "realPlayerContributionTotal": 42000,
    "botContributionTotal": 9000,
    "payoutTotal": 36000,
    "actualBootCommissionTotal": 1200,
    "actualWinCommissionTotal": 900,
    "dealerTipTotal": 300,
    "filters": {
      "operatorId": "operator-1",
      "from": "2026-07-01T00:00:00.000Z",
      "to": "2026-07-20T23:59:59.999Z"
    }
  }
}
```

If no rounds match, the endpoint returns `200` with zero totals and
`roundCount: 0`.

Validation errors return JSON:

```json
{
  "error": {
    "code": "bad_request",
    "message": "from must be a valid ISO timestamp or YYYY-MM-DD date."
  }
}
```

## API: Round Detail HTML

```http
GET /api/teen-patti/round-detail?user_id=USER&operator_id=OPERATOR&lobby_id=LOBBY
```

Returns an HTML page for the latest completed round in the requested lobby that
belongs to the supplied platform user/operator relationship.

No `X-API-Key` header or `ROUND_DETAIL_API_KEY` environment variable is required
for this endpoint.

Query parameters:

| Parameter | Required | Purpose |
| --- | --- | --- |
| `user_id` | Yes | Platform user id. |
| `operator_id` | Yes | Platform operator id. |
| `lobby_id` | Yes | Public/private table aggregate id. |

Lookup behavior:

1. Finds matching `wallet_transactions` rows for the platform user and operator.
2. Reads the matching internal `playerId` values.
3. Finds the latest settled `round_history` row for `lobby_id` where one of
   those player ids participated.
4. Renders the round as `text/html`.

Security behavior:

- Always returns HTML for this route, including errors.
- Sends `Cache-Control: no-store`.
- Sends `X-Content-Type-Options: nosniff`.
- Sends `Referrer-Policy: no-referrer`.

Common status codes:

| Status | Meaning |
| --- | --- |
| `200` | Round detail found and rendered. |
| `400` | Missing `user_id`, `operator_id`, or `lobby_id`. |
| `404` | No matching player or completed lobby round was found. |

## Node API Files

| File | Responsibility |
| --- | --- |
| `src/app.js` | Express app, health route, routers, JSON error handling. |
| `src/server.js` | Startup and shutdown lifecycle. |
| `src/routes/teenPattiRounds.js` | `GET /api/teen-patti/rounds`. |
| `src/services/roundHistoryService.js` | Pagination, Mongo query, response mapping. |
| `src/routes/teenPattiEarningsSummary.js` | `GET /api/teen-patti/earnings-summary`. |
| `src/services/earningsSummaryService.js` | Mongo aggregation for real-money earnings totals. |
| `src/routes/teenPattiRoundDetail.js` | `GET /api/teen-patti/round-detail`, HTML headers. |
| `src/services/roundDetailService.js` | Authorized round lookup using wallet transactions and round history. |
| `src/views/roundDetailHtml.js` | HTML rendering for round detail and HTML error responses. |

## Node Tests

Run the Node tests:

```bash
cd server
npm test
```
