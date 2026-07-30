export default function HistoryPanel({ history }) {
  return (
    <section className="history-panel">
      <div className="panel-heading compact-panel-heading">
        <div>
          <p className="eyebrow">Recent Rounds</p>
          <h2>History</h2>
        </div>
      </div>

      {history?.length ? (
        <div className="history-grid">
          {history.map((item) => (
            <article className={`history-card ${item.outcome}`} key={item.id}>
              <div className="history-top">
                <span>#{item.roundId.slice(0, 8)}</span>
                <span>{new Date(item.timestamp).toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit" })}</span>
              </div>
              <strong>{item.outcome === "win" ? "You won" : "You lost"}</strong>
              <p>{item.winningHand}</p>
              <div className="history-stats">
                <span>Pot {item.pot.toLocaleString("en-IN")}</span>
                <span>Bet {item.userContribution.toLocaleString("en-IN")}</span>
                <span>Payout {item.payout.toLocaleString("en-IN")}</span>
              </div>
            </article>
          ))}
        </div>
      ) : (
        <div className="history-empty">No completed rounds yet. The first settled hand will appear here.</div>
      )}
    </section>
  );
}
