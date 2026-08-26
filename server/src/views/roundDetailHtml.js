function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function amount(value) {
  return Number(value || 0).toLocaleString("en-IN");
}

function timestamp(value) {
  if (!value) return "Unavailable";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString("en-IN");
}

function cardMarkup(card, label = "") {
  const rank = escapeHtml(card?.rank || "?");
  const suit = String(card?.suit || "").toLowerCase();
  const symbols = { hearts: "♥", diamonds: "♦", clubs: "♣", spades: "♠" };
  const symbol = symbols[suit] || escapeHtml(card?.suit || "?");
  const red = suit === "hearts" || suit === "diamonds";
  return `<span class="card ${red ? "red" : ""}"><b>${rank}</b><span>${symbol}</span>${label ? `<small>${escapeHtml(label)}</small>` : ""}</span>`;
}

function handMarkup(participant) {
  const cards = Array.isArray(participant.cards) ? participant.cards : [];
  const publicCards = Array.isArray(participant.publicCards) ? participant.publicCards : [];
  const publicIds = new Set(publicCards.map((card) => card?.id).filter(Boolean));
  const cardIds = new Set(cards.map((card) => card?.id).filter(Boolean));
  const additionalPublic = publicCards.filter((card) => !cardIds.has(card?.id));
  const reserveCards = (Array.isArray(participant.reserveCards) ? participant.reserveCards : [])
    .filter((card) => !cardIds.has(card?.id) && !publicIds.has(card?.id));

  if (cards.length === 0 && additionalPublic.length === 0 && reserveCards.length === 0) {
    return '<span class="muted">Hand unavailable for this historical Teen Patti round.</span>';
  }
  const visible = [
    ...cards.map((card) => cardMarkup(card, publicIds.has(card?.id) ? "PUBLIC" : "")),
    ...additionalPublic.map((card) => cardMarkup(card, "PUBLIC")),
  ].join("");
  const reserve = reserveCards.length
    ? `<div class="reserve"><span>Reserve</span>${reserveCards.map((card) => cardMarkup(card)).join("")}</div>`
    : "";
  return `${visible}${reserve}`;
}

function metric(label, value) {
  return `<div class="metric"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`;
}

function renderRoundDetail({ round, viewerPlayerIds }) {
  const roundId = String(round._id || round.id || "");
  const participants = Array.isArray(round.participants) ? round.participants : [];
  const winnerId = round.winner?.id || "";
  const viewer = participants.find((player) => viewerPlayerIds.includes(player.id));
  const viewerWon = Boolean(viewer && viewer.id === winnerId);
  const actions = Array.isArray(round.actionLog) ? round.actionLog : [];

  const hands = participants.map((player) => `
    <article class="hand ${player.id === winnerId ? "winner" : ""}">
      <div class="hand-title">
        <div><strong>${escapeHtml(player.name || player.id || "Player")}</strong>${player.isBot ? '<span class="tag">BOT</span>' : ""}</div>
        ${player.id === winnerId ? '<span class="win-tag">WINNER</span>' : ""}
      </div>
      <div class="cards">${handMarkup(player)}</div>
      <div class="hand-foot">
        <span>${escapeHtml(player.handLabel || (player.id === winnerId ? round.winner?.winningHand : "") || "Teen Patti hand")}</span>
        <span>Contribution: ${amount(player.totalContributed)}</span>
      </div>
    </article>`).join("");

  const participantNames = new Map(participants.map((player) => [player.id, player.name || player.id]));
  const actionRows = actions.length
    ? actions.map((action) => `
      <tr>
        <td><strong>${escapeHtml(participantNames.get(action.playerId) || action.playerId || "Teen Patti")}</strong><small>${escapeHtml(action.actionType || "event")}</small></td>
        <td>${amount(action.amount)}</td>
        <td><span class="status ${action.playerId === winnerId ? "won" : ""}">${action.playerId === winnerId ? "WON" : "RECORDED"}</span></td>
        <td>${escapeHtml(timestamp(action.timestamp))}</td>
        <td>${escapeHtml(action.note || "")}</td>
      </tr>`).join("")
    : '<tr><td colspan="5" class="muted">No Teen Patti bet events were stored.</td></tr>';

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="robots" content="noindex,nofollow,noarchive">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:">
  <title>Teen Patti Round ${escapeHtml(roundId)}</title>
  <style>
    *{box-sizing:border-box}body{margin:0;background:#f4f7f9;color:#34475a;font:15px/1.45 Arial,system-ui,sans-serif}
    .topbar{position:sticky;top:0;z-index:5;display:flex;justify-content:space-between;align-items:center;background:#050505;color:#fff;padding:14px 24px;font-size:25px}.close{font-size:34px;font-weight:200;line-height:1}
    .wrap{max-width:1240px;margin:auto;padding:24px}.page-title{text-align:center;font-size:34px;margin:4px 0 24px;padding-bottom:18px;border-bottom:2px solid #e3e6e8}
    h2{margin:0;font-size:24px}.sub,.muted{color:#718096}.panel{background:#fff;border-radius:18px;padding:24px;margin:20px 0;box-shadow:0 9px 28px #20304014}
    .line{height:3px;background:#36a6d8;border-radius:3px;margin:12px 0 24px}.summary,.metrics,.hands{display:grid;gap:16px}.summary{grid-template-columns:repeat(auto-fit,minmax(190px,1fr));margin-bottom:18px}
    .metrics{grid-template-columns:repeat(auto-fit,minmax(165px,1fr))}.metric{border:1px solid #e3e8ec;border-radius:11px;padding:13px;background:#fff}.metric span{display:block;color:#758391;font-size:12px;text-transform:uppercase}.metric strong{font-size:18px}
    .outcome{padding:14px 18px;border-radius:12px;margin-bottom:18px;background:${viewerWon ? "#ebfbf1" : "#fff4f4"};border:1px solid ${viewerWon ? "#57be7d" : "#e89a9a"}}
    .hands{grid-template-columns:repeat(auto-fit,minmax(280px,1fr))}.hand{position:relative;border:2px solid #dfe4e8;border-radius:16px;padding:22px;min-height:250px;text-align:center;background:#fff}.hand.winner{border-color:#35b86d;background:#effcf4}
    .hand-title,.hand-foot{display:flex;justify-content:space-between;gap:8px;align-items:center}.hand-title>div{font-size:20px;text-transform:uppercase}.hand-foot{margin-top:18px;color:#52677b;font-size:13px}.tag,.win-tag{display:inline-block;margin-left:8px;padding:4px 10px;border-radius:999px;font-size:11px}.tag{background:#e5ebf1}.win-tag{background:#28b766;color:#fff}
    .cards{display:flex;justify-content:center;align-items:center;gap:12px;flex-wrap:wrap;margin-top:25px}.card{position:relative;width:68px;height:96px;border:1px solid #d2d8dd;border-radius:10px;background:#fff;box-shadow:0 5px 11px #0002;display:flex;flex-direction:column;align-items:center;justify-content:center;font-size:24px;color:#151c24}.card.red{color:#dc3232}.card small{position:absolute;top:-9px;padding:2px 5px;border-radius:6px;background:#36a6d8;color:#fff;font-size:7px;letter-spacing:.05em}
    .reserve{display:flex;align-items:center;gap:8px;width:100%;justify-content:center;margin-top:10px}.reserve>span{font-size:10px;text-transform:uppercase;color:#83909b}
    .table-wrap{overflow:auto}table{width:100%;border-collapse:separate;border-spacing:0 9px;min-width:820px}th,td{text-align:left;padding:14px 16px}th{color:#7a8792;font-weight:600;border-bottom:0}td{background:#fff;border-top:1px solid #e4e9ed;border-bottom:1px solid #e4e9ed}td:first-child{border-left:1px solid #e4e9ed;border-radius:10px 0 0 10px}td:last-child{border-right:1px solid #e4e9ed;border-radius:0 10px 10px 0}td strong,td small{display:block}.status{display:inline-block;border-radius:999px;background:#eef2f5;color:#687681;padding:4px 9px;font-size:11px;font-weight:700}.status.won{background:#e5faed;color:#199b4d}
    .finance th{background:#34566d;color:#fff}.finance td{background:#f9fbfc}
    @media(max-width:600px){.topbar{font-size:17px;padding:11px 14px}.close{font-size:27px}.wrap{padding:12px}.panel{padding:15px}.page-title{font-size:25px}.hand-foot{align-items:flex-start;flex-direction:column}}
  </style>
</head>
<body>
  <header class="topbar"><span>Teen Patti Round Detail</span><span class="close" aria-hidden="true">×</span></header>
  <main class="wrap">
    <h1 class="page-title">Teen Patti ${escapeHtml(round.variantId || "Classic")}</h1>
    <section class="panel">
      <h2>Round Information</h2><div class="line"></div>
      <div class="summary">
        ${metric("Round ID", roundId)}
        ${metric("Lobby ID", round.aggregateId || "")}
        ${metric("Started", timestamp(round.startedAt))}
        ${metric("Settled", timestamp(round.settledAt))}
      </div>
    </section>
    <section class="panel">
      <h2>Outcome</h2><div class="line"></div>
      <div class="outcome"><strong>${viewerWon ? "WIN" : "LOSS"}</strong> — Winner: ${escapeHtml(round.winner?.name || round.winner?.id || "Unavailable")} · Payout: ${amount(round.payout)}</div>
      <div class="hands">${hands || '<p class="muted">No Teen Patti hands were stored.</p>'}</div>
    </section>
    <section class="panel">
      <h2>Bet Placed</h2><div class="line"></div>
      <div class="table-wrap"><table><thead><tr><th>Player / Event</th><th>Amount</th><th>Status</th><th>Time</th><th>Details</th></tr></thead><tbody>${actionRows}</tbody></table></div>
    </section>
    <section class="panel">
      <h2>Teen Patti Financial Details</h2><div class="line"></div>
      <div class="metrics">
        ${metric("Pot amount", amount(round.potAmount))}
        ${metric("Boot contributions", amount(round.bootContributionTotal))}
        ${metric("Real-player contributions", amount(round.realPlayerContributionTotal))}
        ${metric("Bot contributions", amount(round.botContributionTotal))}
        ${metric("Boot commission", amount(round.bootCommission))}
        ${metric("Actual boot commission", amount(round.actualBootCommission))}
        ${metric("Win commission", amount(round.winCommission))}
        ${metric("Actual win commission", amount(round.actualWinCommission))}
        ${metric("House commission", amount(round.casinoCommissionTotal))}
        ${metric("Actual house income", amount(round.actualCasinoIncomeTotal))}
        ${metric("Dealer tip", amount(round.dealerTip))}
        ${metric("Winner before tip", amount(round.winnerReceivableBeforeTip))}
        ${metric("Final payout", amount(round.payout))}
      </div>
    </section>
  </main>
</body>
</html>`;
}

function renderError(statusCode, message) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Teen Patti Error</title><style>body{margin:0;background:#eef2f5;font:16px system-ui;color:#233548}.box{max-width:680px;margin:12vh auto;background:#fff;padding:30px;border-radius:16px;box-shadow:0 8px 30px #0001}h1{margin-top:0}</style></head><body><main class="box"><h1>Teen Patti request failed</h1><p>${escapeHtml(message)}</p><small>HTTP ${Number(statusCode)}</small></main></body></html>`;
}

export { escapeHtml, renderError, renderRoundDetail };
