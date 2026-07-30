# Teen Patti Round History Service

The Node service exposes the existing paginated JSON history API, a
single-round HTML view, and a JSON earnings summary.

## Earnings summary

```http
GET /api/teen-patti/earnings-summary?operator_id=OPERATOR&from=2026-07-01&to=2026-07-20
```

Returns casino earnings from settled rounds using `actualCasinoIncomeTotal`,
which excludes bot transactions. `operator_id`, `from`, and `to` are optional.
Date-only filters are treated as UTC day bounds.

Example:

```bash
curl "http://localhost:4200/api/teen-patti/earnings-summary"
```

The response is JSON and includes `totalEarnings`, `roundCount`,
real-player/bot contribution totals, payout total, actual commission totals,
dealer tip total, and the normalized filters that were applied. If no rounds
match, the API returns `200` with zero totals.

## HTML round detail

```http
GET /api/teen-patti/round-detail?user_id=USER&operator_id=OPERATOR&lobby_id=LOBBY
```

The endpoint selects the latest completed Teen Patti round in the lobby which
belongs to the supplied user/operator relationship and responds with
`text/html`. Validation, authorization, not-found, and server errors are also
rendered as HTML.

The route does not require API-key authentication. Existing rounds created
before hand snapshots were introduced display “Hand unavailable”; newly settled
rounds include all hand zones.
