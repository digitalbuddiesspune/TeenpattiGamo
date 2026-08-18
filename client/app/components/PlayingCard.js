const suitMap = {
  spades: "♠",
  hearts: "♥",
  diamonds: "♦",
  clubs: "♣"
};

export default function PlayingCard({ card, revealed, compact = false, className = "", style }) {
  const hidden = !revealed || card.hidden;
  const suitSymbol = suitMap[card.suit] || "♠";
  const sizeClasses = compact
    ? "h-[42px] w-[29px] rounded-[5px]"
    : "h-[84px] w-[58px] rounded-[10px] px-2 py-1.5 sm:h-[96px] sm:w-[66px]";
  const rankClass = compact ? "text-[7px] leading-none" : "text-sm leading-none sm:text-base";
  const cornerSuitClass = compact ? "text-[7px] leading-none" : "text-base leading-none";
  const suitClass = compact ? "text-[0.9rem]" : "text-[2rem] sm:text-[2.35rem]";
  const isJokerCard = card.cardAccent === "joker";
  const isReserveCard = card.cardAccent === "reserve";
  const isFlipperCard = card.cardAccent === "flipper";

  const surfaceClasses = compact
    ? isFlipperCard
      ? "border-[#5ac8fa] bg-[linear-gradient(180deg,#e8f8ff_0%,#a8dcf8_100%)] text-[#003a5c]"
      : isJokerCard
        ? "border-[#48d7d1] bg-[linear-gradient(180deg,#d8fffb_0%,#8cebe2_100%)] text-[#053436]"
        : isReserveCard
          ? "border-[#ffb867] bg-[linear-gradient(180deg,#fff0cc_0%,#f7be68_100%)] text-[#4b2700]"
          : "border-[#d2ae58] bg-[linear-gradient(180deg,#ffefb2_0%,#f1c764_100%)] text-[#080808]"
    : isFlipperCard
      ? "border-[#5ac8fa] bg-[linear-gradient(180deg,#eaf7ff_0%,#a0d8f8_100%)] text-[#003a5c]"
      : isJokerCard
        ? "border-[#42d8d1] bg-[linear-gradient(180deg,#d9fffb_0%,#90eee5_100%)] text-[#053436]"
        : isReserveCard
          ? "border-[#ffb663] bg-[linear-gradient(180deg,#fff1cf_0%,#f3be67_100%)] text-[#4b2700]"
          : "border-[#d8b45b] bg-[linear-gradient(180deg,#ffefb5_0%,#f3cd69_100%)] text-[#080808]";

  const inkClass = isFlipperCard
    ? "text-[#003a5c]"
    : isJokerCard
      ? "text-[#053436]"
      : isReserveCard
        ? "text-[#4b2700]"
        : "text-[#080808]";

  if (hidden) {
    // Flipper card hidden back: blue-tinted so opponents know a blue Flipper card exists
    if (isFlipperCard) {
      return (
        <div
          className={[
            "hidden-playing-card hidden-playing-card--flipper relative overflow-hidden border border-[#5ac8fa]/70 drop-shadow-[0_12px_18px_rgba(0,0,0,0.28)]",
            sizeClasses,
            className
          ].join(" ")}
          style={style}
        >
          <span className="absolute inset-0 bg-[linear-gradient(145deg,#d6f0ff_0%,#6ab8e8_42%,#1a6a9e_100%)]" />
          <span className="absolute inset-[2px] rounded-[inherit] border border-white/28 bg-[radial-gradient(circle_at_top,rgba(255,255,255,0.42),transparent_32%),linear-gradient(160deg,rgba(220,245,255,0.94)_0%,rgba(100,185,235,0.96)_52%,rgba(20,90,140,0.98)_100%)]" />
          <span
            className="absolute inset-[10%] rounded-[inherit] opacity-40"
            style={{
              backgroundImage:
                "repeating-linear-gradient(45deg, rgba(10,60,110,0.38) 0 6px, rgba(200,235,255,0.06) 6px 12px)",
            }}
          />
          <span className="absolute inset-[18%] rounded-[inherit] border border-[#c8eeff]/50 bg-[linear-gradient(135deg,rgba(210,240,255,0.48),rgba(30,110,175,0.22))] shadow-[inset_0_0_0_1px_rgba(10,70,130,0.18)]" />
          <span className="absolute left-1/2 top-1/2 h-[26%] w-[26%] -translate-x-1/2 -translate-y-1/2 rotate-45 rounded-[4px] border border-[#aadcf8]/35 bg-[linear-gradient(135deg,rgba(220,245,255,0.92),rgba(60,150,210,0.72))]" />
        </div>
      );
    }

    return (
      <div
        className={[
          "hidden-playing-card relative overflow-hidden border border-[#d4b06a] drop-shadow-[0_12px_18px_rgba(0,0,0,0.28)]",
          sizeClasses,
          className
        ].join(" ")}
        style={style}
      >
        <span className="absolute inset-0 bg-[linear-gradient(145deg,#fff6dc_0%,#e3c17d_42%,#8b6329_100%)]" />
        <span className="absolute inset-[2px] rounded-[inherit] border border-white/28 bg-[radial-gradient(circle_at_top,rgba(255,255,255,0.42),transparent_32%),linear-gradient(160deg,rgba(255,247,225,0.94)_0%,rgba(226,192,119,0.96)_52%,rgba(118,79,24,0.98)_100%)]" />
        <span
          className="absolute inset-[10%] rounded-[inherit] opacity-40"
          style={{
            backgroundImage:
              "repeating-linear-gradient(45deg, rgba(112,72,20,0.38) 0 6px, rgba(255,244,216,0.06) 6px 12px)",
          }}
        />
        <span className="absolute inset-[18%] rounded-[inherit] border border-[#fff0c8]/50 bg-[linear-gradient(135deg,rgba(255,248,227,0.48),rgba(145,96,28,0.22))] shadow-[inset_0_0_0_1px_rgba(92,58,12,0.18)]" />
        <span className="absolute inset-[28%] rotate-45 rounded-[6px] border border-[#fff4d1]/58 bg-[linear-gradient(135deg,rgba(255,251,236,0.9),rgba(206,164,89,0.82))] shadow-[0_0_0_1px_rgba(109,76,23,0.12)]" />
        <span className="absolute left-1/2 top-1/2 h-[26%] w-[26%] -translate-x-1/2 -translate-y-1/2 rotate-45 rounded-[4px] border border-[#8d6424]/35 bg-[linear-gradient(135deg,rgba(255,246,220,0.92),rgba(177,124,44,0.72))]" />
      </div>
    );
  }

  if (compact) {
    return (
      <div
        className={[
          `relative shrink-0 overflow-hidden border ${surfaceClasses}`,
          "shadow-[0_10px_18px_rgba(0,0,0,0.22)]",
          sizeClasses,
          className
        ].join(" ")}
        style={style}
      >
        <div className={`absolute left-[3px] top-[3px] flex flex-col font-black ${inkClass}`}>
          <span className={rankClass}>{card.rank}</span>
          <span className={cornerSuitClass}>{suitSymbol}</span>
        </div>
        <div className={`absolute inset-0 flex items-center justify-center font-black leading-none ${inkClass} ${suitClass}`}>
          {suitSymbol}
        </div>
        <div
          className={`absolute bottom-[3px] right-[3px] flex rotate-180 flex-col font-black ${inkClass}`}
        >
          <span className={rankClass}>{card.rank}</span>
          <span className={cornerSuitClass}>{suitSymbol}</span>
        </div>
      </div>
    );
  }

  return (
    <div
      className={[
        `relative flex shrink-0 flex-col justify-between overflow-hidden border ${surfaceClasses}`,
        "shadow-[0_14px_28px_rgba(0,0,0,0.28)]",
        sizeClasses,
        className
      ].join(" ")}
      style={style}
    >
      <div className={`flex flex-col font-black ${inkClass}`}>
        <span className={rankClass}>{card.rank}</span>
        <span className={cornerSuitClass}>{suitSymbol}</span>
      </div>
      <div className={`self-center font-black leading-none ${inkClass} ${suitClass}`}>
        {suitSymbol}
      </div>
      <div
        className={`flex rotate-180 flex-col self-end font-black ${inkClass}`}
      >
        <span className={rankClass}>{card.rank}</span>
        <span className={cornerSuitClass}>{suitSymbol}</span>
      </div>
    </div>
  );
}
