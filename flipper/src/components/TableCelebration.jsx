const CONFETTI_COLORS = {
  win: ["#ffd56a", "#ff7a59", "#7ef0e4", "#fff1b0", "#ff9f43", "#9bffb0"],
  raise: ["#8eb6ff", "#6dff9a", "#7ef0e4", "#c8ddff", "#43d8cc", "#fff1b0"],
  tip: ["#ffe888", "#ffd56a", "#fff1b0", "#ff9f43", "#f7d98c", "#ffe0a8"],
};

export default function TableCelebration({ title, subtitle = "", tone = "win" }) {
  const colors = CONFETTI_COLORS[tone] || CONFETTI_COLORS.win;

  return (
    <div
      className={`casino-table-scene__celebration casino-table-scene__celebration--${tone} pointer-events-none fixed inset-0 z-[55]`}
      aria-hidden="true"
    >
      <div className="casino-table-scene__celebration-veil" />
      <div className="casino-table-scene__celebration-burst" />
      <div className="casino-table-scene__celebration-banner">
        <strong>{title}</strong>
        {subtitle ? <span>{subtitle}</span> : null}
      </div>
      {Array.from({ length: 36 }, (_, index) => (
        <span
          key={`confetti-${tone}-${index}`}
          className="casino-table-scene__confetti"
          style={{
            left: `${4 + (index * 29) % 92}%`,
            animationDelay: `${(index % 10) * 110}ms`,
            animationDuration: `${2200 + (index % 6) * 280}ms`,
            background: colors[index % colors.length],
          }}
        />
      ))}
    </div>
  );
}
