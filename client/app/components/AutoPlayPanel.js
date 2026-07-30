export default function AutoPlayPanel({ autoplay, acting, onStart, onStop }) {
  return (
    <section className="autoplay-panel">
      <div className="panel-heading compact-panel-heading">
        <div>
          <p className="eyebrow">Auto Mode</p>
          <h2>Session</h2>
        </div>
        {autoplay?.active ? (
          <button className="mini-action danger" type="button" onClick={onStop} disabled={acting}>
            Stop Auto
          </button>
        ) : (
          <button
            className="mini-action"
            type="button"
            onClick={() =>
              onStart({
                roundsPlanned: 8,
                stopProfit: 200000,
                stopLoss: 100000,
                seeAfterTurns: 2
              })
            }
            disabled={acting}
          >
            Start Auto
          </button>
        )}
      </div>

      <div className="autoplay-metrics">
        <div className="metric">
          <span>Rounds</span>
          <strong>{autoplay ? `${autoplay.roundsCompleted}/${autoplay.roundsPlanned}` : "0/0"}</strong>
        </div>
        <div className="metric">
          <span>Profit</span>
          <strong>{autoplay?.summary?.profit?.toLocaleString("en-IN") || "0"}</strong>
        </div>
        <div className="metric">
          <span>Wins</span>
          <strong>{autoplay?.summary?.wins || 0}</strong>
        </div>
        <div className="metric">
          <span>Losses</span>
          <strong>{autoplay?.summary?.losses || 0}</strong>
        </div>
      </div>
    </section>
  );
}
