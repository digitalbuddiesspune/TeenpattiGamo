const SUIT_SYMBOLS = {
  hearts: "♥",
  diamonds: "♦",
  clubs: "♣",
  spades: "♠",
};

function isRedSuit(suit) {
  const normalized = String(suit || "").toLowerCase();
  return normalized === "hearts" || normalized === "diamonds";
}

function CardFace({ card, label }) {
  if (!card) {
    return null;
  }

  const suit = String(card.suit || "").toLowerCase();
  const red = isRedSuit(suit);

  return (
    <span
      className={`inline-flex min-w-[3.1rem] flex-col items-center justify-center rounded-lg border bg-white px-2 py-1.5 shadow-sm ${
        red ? "border-rose-200 text-rose-600" : "border-[var(--color-line)] text-[var(--color-ink)]"
      }`}
    >
      <b className="text-sm leading-none">{card.rank || "?"}</b>
      <span className="mt-0.5 text-xs leading-none">{SUIT_SYMBOLS[suit] || card.suit || "?"}</span>
      {label ? (
        <small className="mt-1 text-[9px] font-bold uppercase tracking-wide text-[var(--color-muted)]">
          {label}
        </small>
      ) : null}
    </span>
  );
}

function HandCards({ player }) {
  const cards = Array.isArray(player.cards) ? player.cards : [];
  const publicCards = Array.isArray(player.publicCards) ? player.publicCards : [];
  const reserveCards = Array.isArray(player.reserveCards) ? player.reserveCards : [];
  const publicIds = new Set(publicCards.map((card) => card?.id).filter(Boolean));
  const cardIds = new Set(cards.map((card) => card?.id).filter(Boolean));
  const extraPublic = publicCards.filter((card) => !cardIds.has(card?.id));
  const reserve = reserveCards.filter((card) => !cardIds.has(card?.id) && !publicIds.has(card?.id));

  if (cards.length === 0 && extraPublic.length === 0 && reserve.length === 0) {
    return <p className="text-sm text-[var(--color-muted)]">Hand unavailable for this round.</p>;
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-1.5">
        {cards.map((card) => (
          <CardFace key={card.id || `${card.rank}-${card.suit}`} card={card} label={publicIds.has(card.id) ? "Public" : ""} />
        ))}
        {extraPublic.map((card) => (
          <CardFace key={`public-${card.id || `${card.rank}-${card.suit}`}`} card={card} label="Public" />
        ))}
      </div>
      {reserve.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] font-bold uppercase tracking-wide text-[var(--color-muted)]">
            Reserve
          </span>
          {reserve.map((card) => (
            <CardFace key={`reserve-${card.id || `${card.rank}-${card.suit}`}`} card={card} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

function buildHowItEnded(game) {
  const details = [];
  const winnerName = game.winner?.displayName || "Unknown";
  const headline = game.winner?.isHouse
    ? `Platform kept the pot · ${winnerName}`
    : `Winner: ${winnerName}${game.winner?.winningHand ? ` (${game.winner.winningHand})` : ""}`;

  if (game.mode) {
    details.push(`Mode: ${game.mode}`);
  }
  if (game.reason) {
    details.push(`End reason: ${game.reason}`);
  }
  details.push(
    `Pot ${Number(game.displayPotAmount || 0).toLocaleString("en-IN")} · payout ${Number(game.winnerPayout || 0).toLocaleString("en-IN")} · platform ${Number(game.platformProfit || 0).toLocaleString("en-IN")}`,
  );

  const packed = (game.players || []).filter((player) => player.packed || player.isAbandoned);
  if (packed.length > 0) {
    details.push(`Packed / left: ${packed.map((player) => player.displayName).join(", ")}`);
  }

  return { headline, details };
}

export function TeenPattiRoundPreview({ game }) {
  const { headline, details } = buildHowItEnded(game);
  const players = game.players || [];
  const actions = Array.isArray(game.actionLog) ? game.actionLog.slice(-12) : [];
  const nameById = new Map(players.map((player) => [player.userId, player.displayName]));
  const sharedJokers = Array.isArray(game.sharedJokers) ? game.sharedJokers.filter(Boolean) : [];
  const wildcardRanks = Array.isArray(game.wildcardRanks) ? game.wildcardRanks : [];

  return (
    <div className="border-t border-[var(--color-line)] px-6 py-5">
      <h3 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
        How it ended
      </h3>

      <div className="mt-3 rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-4 py-3">
        <p className="text-sm font-semibold text-[var(--color-ink)]">{headline}</p>
        {details.length > 0 ? (
          <ul className="mt-2 space-y-1.5 text-sm leading-relaxed text-[var(--color-muted)]">
            {details.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        ) : null}
      </div>

      {sharedJokers.length > 0 || wildcardRanks.length > 0 ? (
        <div className="mt-5 rounded-xl border border-[#48d7d1]/35 bg-[linear-gradient(180deg,#f3fffd,#e8fbf8)] px-4 py-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h4 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#0f766e]">
              Shared Jokers
            </h4>
            {wildcardRanks.length > 0 ? (
              <p className="text-xs text-[var(--color-muted)]">
                Wild ranks:{" "}
                <span className="font-semibold text-[var(--color-ink)]">{wildcardRanks.join(", ")}</span>
              </p>
            ) : null}
          </div>
          {sharedJokers.length > 0 ? (
            <div className="mt-3 flex flex-wrap gap-1.5">
              {sharedJokers.map((card, index) => (
                <CardFace
                  key={card.id || `shared-joker-${index}`}
                  card={card}
                  label={`Joker ${index + 1}`}
                />
              ))}
            </div>
          ) : (
            <p className="mt-2 text-sm text-[var(--color-muted)]">
              Shared joker ranks active for this round.
            </p>
          )}
        </div>
      ) : null}

      <div className="mt-5 grid gap-4 lg:grid-cols-2">
        {players.map((player) => {
          const isWinner = game.winner?.userId && player.userId === game.winner.userId;
          return (
            <article
              key={player.userId}
              className={`rounded-xl border p-4 ${
                isWinner
                  ? "border-[var(--accent)]/40 bg-[var(--accent-soft)]/40"
                  : "border-[var(--color-line)] bg-white"
              }`}
            >
              <div className="mb-3 flex items-start justify-between gap-2">
                <div>
                  <p className="font-semibold text-[var(--color-ink)]">
                    {player.displayName}
                    {player.isBot ? (
                      <span className="ml-2 rounded-md bg-[#eef4ff] px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-[#2563eb]">
                        Bot
                      </span>
                    ) : null}
                  </p>
                  <p className="mt-0.5 text-xs text-[var(--color-muted)]">
                    {player.handLabel || (isWinner ? game.winner?.winningHand : null) || "Teen Patti hand"}
                    {player.packed ? " · packed" : ""}
                    {player.seen ? " · seen" : ""}
                  </p>
                </div>
                {isWinner ? (
                  <span className="rounded-md bg-[var(--accent)] px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-white">
                    Winner
                  </span>
                ) : null}
              </div>
              <HandCards player={player} />
              <p className="mt-3 text-xs text-[var(--color-muted)]">
                Contribution: {Number(player.betAmount || 0).toLocaleString("en-IN")}
              </p>
            </article>
          );
        })}
      </div>

      {actions.length > 0 ? (
        <div className="mt-5 overflow-hidden rounded-xl border border-[var(--color-line)]">
          <div className="border-b border-[var(--color-line)] bg-[#f4f7f5] px-3 py-2 text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
            Recent actions
          </div>
          <div className="max-h-48 overflow-y-auto">
            <table className="min-w-full text-sm">
              <tbody>
                {actions.map((action, index) => (
                  <tr key={action.id || `${action.playerId}-${index}`} className="border-b border-[var(--color-line)]/60 last:border-0">
                    <td className="px-3 py-2 font-medium text-[var(--color-ink)]">
                      {nameById.get(action.playerId) || action.playerId || "System"}
                      <div className="text-xs font-normal text-[var(--color-muted)]">{action.actionType || "event"}</div>
                    </td>
                    <td className="px-3 py-2 tabular-nums text-[var(--color-ink)]">
                      {Number(action.amount || 0).toLocaleString("en-IN")}
                    </td>
                    <td className="px-3 py-2 text-xs text-[var(--color-muted)]">
                      {action.note || ""}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </div>
  );
}
