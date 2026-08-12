import Image from "next/image";
import PlayingCard from "./PlayingCard";

const avatarMap = {
  you: "/newAssets/avatars/avatar1.png",
  raj: "/newAssets/avatars/avatar2.png",
  captain: "/newAssets/avatars/avatar3.png",
  maya: "/newAssets/avatars/avatar4.png",
  ace: "/newAssets/avatars/avatar5.png"
};

const seatLayouts = [
  {
    // 0 — user, bottom center
    wrapper: "left-1/2 bottom-[14%] z-[24] w-[34%] min-w-[176px] max-w-[246px] -translate-x-1/2 sm:bottom-[10%]",
    avatarShell: "relative z-[22] mx-auto h-[42px] w-[42px] rounded-full border-[3px] border-[#2dd6cf] bg-[radial-gradient(circle_at_50%_32%,#66f8e0_0%,#1aa8a1_42%,#0d5558_100%)] p-[3px] shadow-[0_0_18px_rgba(74,243,223,0.32)] sm:h-[48px] sm:w-[48px]",
    avatarInner: "rounded-full border-[2px] border-[#f4db9f]",
    label: "relative z-[22] mt-[-10px] mx-auto w-fit min-w-[88px] rounded-[10px] border border-[#e0c97f] bg-[linear-gradient(180deg,#f9df8a_0%,#d3a93e_100%)] px-1.5 pt-0.5 pb-0 text-center text-[#5b3500] shadow-[0_10px_18px_rgba(0,0,0,0.28)]",
    cardsWrap: "left-1/2 bottom-[-86px] -translate-x-1/2 sm:bottom-[-112px]",
    cardsRow: "flex items-end justify-center",
    overlap: "-ml-4 sm:-ml-5",
    stateWrap: "bottom-[calc(100%+30px)]",
    hiddenCardClass: "",
    hiddenCardStyles: [
      { transform: "rotate(-10deg) translate(4px, 4px)", zIndex: 1 },
      { transform: "rotate(0deg) translate(0, 0)", zIndex: 2 },
      { transform: "rotate(10deg) translate(-4px, 4px)", zIndex: 1 }
    ],
    visibleCardStyles: [
      { transform: "rotate(-12deg) translate(10px, 6px)", zIndex: 1 },
      { transform: "rotate(0deg) translate(0, 0)", zIndex: 2 },
      { transform: "rotate(12deg) translate(-10px, 6px)", zIndex: 3 }
    ]
  },
  {
    // 1 — left-lower (below the x-axis pair)
    wrapper: "left-[2%] top-[62%] z-[18] w-[20%] min-w-[92px] max-w-[122px] -translate-y-1/2",
    avatarShell: "mx-auto h-[33px] w-[33px] rounded-full border-[2px] border-[#ffe3a2] bg-[linear-gradient(180deg,#f4be66_0%,#915d18_100%)] p-[2px] shadow-[0_8px_12px_rgba(0,0,0,0.24)] sm:h-[37px] sm:w-[37px]",
    avatarInner: "rounded-full border border-white/55",
    label: "mt-1 mx-auto w-fit min-w-[56px] rounded-[8px] border border-[#d8bd78] bg-[linear-gradient(180deg,#fee58d_0%,#d3a83b_100%)] px-1 py-0 text-center text-[#5b3500] shadow-[0_6px_10px_rgba(0,0,0,0.18)]",
    cardsWrap: "left-[calc(100%+1px)] top-[50%] -translate-y-1/2",
    cardsRow: "flex flex-col items-center",
    overlap: "-mt-7 sm:-mt-8",
    stateWrap: "top-[-20px]",
    hiddenCardClass: "",
    hiddenCardStyles: [
      { transform: "translate(0, -2px) rotate(80deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(90deg)", zIndex: 2 },
      { transform: "translate(0, 2px) rotate(100deg)", zIndex: 1 }
    ],
    visibleCardStyles: [
      { transform: "translate(0, -3px) rotate(88deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(90deg)", zIndex: 2 },
      { transform: "translate(0, 3px) rotate(92deg)", zIndex: 3 }
    ]
  },
  {
    // 2 — left-upper (x-axis pair)
    wrapper: "left-[2%] top-[34%] z-[18] w-[20%] min-w-[92px] max-w-[122px] -translate-y-1/2",
    avatarShell: "mx-auto h-[33px] w-[33px] rounded-full border-[2px] border-[#ffe3a2] bg-[linear-gradient(180deg,#f4be66_0%,#915d18_100%)] p-[2px] shadow-[0_8px_12px_rgba(0,0,0,0.24)] sm:h-[37px] sm:w-[37px]",
    avatarInner: "rounded-full border border-white/55",
    label: "mt-1 mx-auto w-fit min-w-[56px] rounded-[8px] border border-[#d8bd78] bg-[linear-gradient(180deg,#fee58d_0%,#d3a83b_100%)] px-1 py-0 text-center text-[#5b3500] shadow-[0_6px_10px_rgba(0,0,0,0.18)]",
    cardsWrap: "left-[calc(100%+1px)] top-[50%] -translate-y-1/2",
    cardsRow: "flex flex-col items-center",
    overlap: "-mt-7 sm:-mt-8",
    stateWrap: "top-[-20px]",
    hiddenCardClass: "",
    hiddenCardStyles: [
      { transform: "translate(0, -2px) rotate(80deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(90deg)", zIndex: 2 },
      { transform: "translate(0, 2px) rotate(100deg)", zIndex: 1 }
    ],
    visibleCardStyles: [
      { transform: "translate(0, -3px) rotate(88deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(90deg)", zIndex: 2 },
      { transform: "translate(0, 3px) rotate(92deg)", zIndex: 3 }
    ]
  },
  {
    // 3 — right-upper (x-axis pair, opposite left-upper)
    wrapper: "right-[2%] top-[34%] z-[18] w-[20%] min-w-[92px] max-w-[122px] -translate-y-1/2",
    avatarShell: "mx-auto h-[33px] w-[33px] rounded-full border-[2px] border-[#ffe3a2] bg-[linear-gradient(180deg,#f4be66_0%,#915d18_100%)] p-[2px] shadow-[0_8px_12px_rgba(0,0,0,0.24)] sm:h-[37px] sm:w-[37px]",
    avatarInner: "rounded-full border border-white/55",
    label: "mt-1 mx-auto w-fit min-w-[56px] rounded-[8px] border border-[#d8bd78] bg-[linear-gradient(180deg,#fee58d_0%,#d3a83b_100%)] px-1 py-0 text-center text-[#5b3500] shadow-[0_6px_10px_rgba(0,0,0,0.18)]",
    cardsWrap: "right-[calc(100%+1px)] top-[50%] -translate-y-1/2",
    cardsRow: "flex flex-col items-center",
    overlap: "-mt-7 sm:-mt-8",
    stateWrap: "top-[-20px]",
    hiddenCardClass: "",
    hiddenCardStyles: [
      { transform: "translate(0, -2px) rotate(-80deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(-90deg)", zIndex: 2 },
      { transform: "translate(0, 2px) rotate(-100deg)", zIndex: 1 }
    ],
    visibleCardStyles: [
      { transform: "translate(0, -3px) rotate(-88deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(-90deg)", zIndex: 2 },
      { transform: "translate(0, 3px) rotate(-92deg)", zIndex: 3 }
    ]
  },
  {
    // 4 — right-lower (below the x-axis pair)
    wrapper: "right-[2%] top-[62%] z-[18] w-[20%] min-w-[92px] max-w-[122px] -translate-y-1/2",
    avatarShell: "mx-auto h-[33px] w-[33px] rounded-full border-[2px] border-[#ffe3a2] bg-[linear-gradient(180deg,#f4be66_0%,#915d18_100%)] p-[2px] shadow-[0_8px_12px_rgba(0,0,0,0.24)] sm:h-[37px] sm:w-[37px]",
    avatarInner: "rounded-full border border-white/55",
    label: "mt-1 mx-auto w-fit min-w-[56px] rounded-[8px] border border-[#d8bd78] bg-[linear-gradient(180deg,#fee58d_0%,#d3a83b_100%)] px-1 py-0 text-center text-[#5b3500] shadow-[0_6px_10px_rgba(0,0,0,0.18)]",
    cardsWrap: "right-[calc(100%+1px)] top-[50%] -translate-y-1/2",
    cardsRow: "flex flex-col items-center",
    overlap: "-mt-7 sm:-mt-8",
    stateWrap: "top-[-20px]",
    hiddenCardClass: "",
    hiddenCardStyles: [
      { transform: "translate(0, -2px) rotate(-80deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(-90deg)", zIndex: 2 },
      { transform: "translate(0, 2px) rotate(-100deg)", zIndex: 1 }
    ],
    visibleCardStyles: [
      { transform: "translate(0, -3px) rotate(-88deg)", zIndex: 1 },
      { transform: "translate(0, 0) rotate(-90deg)", zIndex: 2 },
      { transform: "translate(0, 3px) rotate(-92deg)", zIndex: 3 }
    ]
  }
];

function getAvatarImage(avatar, seatIndex) {
  const fallback = [
    "/newAssets/avatars/avatar1.png",
    "/newAssets/avatars/avatar2.png",
    "/newAssets/avatars/avatar3.png",
    "/newAssets/avatars/avatar4.png",
    "/newAssets/avatars/avatar5.png",
    "/newAssets/avatars/avatar6.png"
  ];

  return avatarMap[avatar] || fallback[seatIndex % fallback.length];
}

function getOpponentActionLabel(lastAction) {
  if (!lastAction) {
    return "WAIT";
  }

  switch (lastAction.type) {
    case "blind":
      return "BLIND";
    case "chaal":
      return "CHAAL";
    case "raise":
      return "RAISE";
    case "show":
      return "SHOW";
    case "see":
      return "SEEN";
    case "pack":
      return "PACK";
    case "sideshow-requested":
      return "SIDE";
    case "sideshow-denied":
      return "DENY";
    case "sideshow-accepted":
      return "ACCEPT";
    case "timeout":
      return "TIME";
    default:
      return lastAction.type.slice(0, 5).toUpperCase();
  }
}

function getHandLabelTone(handLabel) {
  const normalized = String(handLabel || "").trim().toLowerCase();
  if (normalized === "trail" || normalized === "pure sequence") {
    return {
      className:
        "border-[#ffe888]/45 bg-[linear-gradient(180deg,#fff2a8_0%,#d0a22e_100%)] text-[#5c3900]",
    };
  }
  if (normalized === "sequence" || normalized === "color") {
    return {
      className:
        "border-[#9ff5c8]/35 bg-[linear-gradient(180deg,rgba(18,88,62,0.96),rgba(8,42,32,0.98))] text-[#d6ffe8]",
    };
  }
  if (normalized === "pair") {
    return {
      className:
        "border-[#9fd4ff]/35 bg-[linear-gradient(180deg,rgba(18,52,88,0.96),rgba(8,28,42,0.98))] text-[#d8f0ff]",
    };
  }
  return {
    className:
      "border-[#d8bd78]/35 bg-[linear-gradient(180deg,rgba(36,28,18,0.96),rgba(18,12,8,0.98))] text-[#f2dfae]",
  };
}

function TurnClock({ turnClock, isUser, className }) {
  const progress = Math.max(0, Math.min(100, turnClock?.progress ?? 0));
  const ringBackground = `conic-gradient(${turnClock?.isCritical ? "#ff7c70" : "#ffdf86"} ${progress}%, rgba(255,255,255,0.14) ${progress}% 100%)`;

  return (
    <div className={`absolute left-1/2 z-[28] -translate-x-1/2 ${className}`}>
      <div className="relative flex h-full w-full items-center justify-center rounded-full shadow-[0_10px_20px_rgba(0,0,0,0.26)]">
        <span className="absolute inset-0 rounded-full opacity-95" style={{ background: ringBackground }} />
        <span className="absolute inset-[5px] rounded-full border border-white/18 bg-[rgba(7,30,34,0.2)]" />
        <strong className="relative text-[8px] font-black text-[#fff1c1] sm:text-[9px]">
          {turnClock?.secondsRemaining ?? 0}
        </strong>
      </div>
    </div>
  );
}

export default function Seat({
  seat,
  isUser,
  isRoundComplete,
  turnClock,
  roundStatus,
  winnerId,
  sideShowResult,
  dealtCount = null,
  cardsPerSeat = 3,
  publicCardMode = "none",
  registerCardAnchor,
  actionNotice = null,
}) {
  const layout = seatLayouts[seat.seatIndex] || seatLayouts[0];
  const isWinner = Boolean(winnerId && winnerId === seat.id);
  const isStarting = roundStatus === "starting";
  const isDealing = roundStatus === "dealing";
  const shouldRevealCards = Boolean(isRoundComplete || seat.seen);
  const hasVisibleCards = Boolean(shouldRevealCards && seat.cards?.some((card) => !card.hidden));
  const cardsRevealed = hasVisibleCards;
  const isSideSeat = !isUser && seat.seatIndex >= 1 && seat.seatIndex <= 4;
  const revealedCardStyles = isUser
    ? [
        { transform: "rotate(-6deg) translateY(2px)", zIndex: 1 },
        { transform: "rotate(0deg) translateY(0)", zIndex: 2 },
        { transform: "rotate(6deg) translateY(2px)", zIndex: 3 },
        { transform: "rotate(10deg) translateY(4px)", zIndex: 4 },
      ]
    : [
        { transform: "rotate(-2deg)", zIndex: 1 },
        { transform: "rotate(0deg)", zIndex: 2 },
        { transform: "rotate(2deg)", zIndex: 3 },
        { transform: "rotate(4deg)", zIndex: 4 },
      ];
  const cardsRowClass = cardsRevealed
    ? isUser
      ? "flex items-end justify-center gap-1.5 sm:gap-2"
      : "table-seat__cards-row table-seat__cards-row--opponent-revealed flex items-end justify-center"
    : layout.cardsRow;
  const cardsWrapClass = cardsRevealed && isSideSeat
    ? seat.seatIndex <= 2
      ? "left-[calc(100%-2px)] top-1/2 -translate-y-1/2"
      : "right-[calc(100%-2px)] top-1/2 -translate-y-1/2"
    : layout.cardsWrap;
  const publicCards = Array.isArray(seat.publicCards) ? seat.publicCards.filter(Boolean) : [];
  const showPublicCards = publicCardMode === "third_card_rank_joker" && publicCards.length > 0 && !isStarting && !isDealing;
  const shouldShowReservePlaceholder = publicCardMode === "third_card_rank_joker" && !isStarting && !isDealing && publicCards.length < 2;
  const rawHandCards = Array.isArray(seat.cards) ? seat.cards : [];
  const duplicatedPublicCardCount = showPublicCards && rawHandCards.length > 0 ? 1 : 0;
  const privateHandCards = duplicatedPublicCardCount > 0 ? rawHandCards.slice(0, rawHandCards.length - duplicatedPublicCardCount) : rawHandCards;
  const handCardCount = privateHandCards.length || Math.max(2, cardsPerSeat - publicCards.length - duplicatedPublicCardCount);
  const dealtCardCount = Number.isInteger(dealtCount) ? Math.max(0, Math.min(cardsPerSeat, dealtCount)) : 0;
  const handCardsToRender = hasVisibleCards
    ? privateHandCards.map((card, index) => ({ ...card, displayType: "hand", renderKey: `${seat.id}-hand-${index}-${card.id || "card"}` }))
    : isStarting
      ? []
      : isDealing
        ? Array.from({ length: dealtCardCount }).map((_, index) => ({
            id: `${seat.id}-dealt-hidden-${index}`,
            hidden: true,
            displayType: "hand",
            renderKey: `${seat.id}-dealt-${index}`
          }))
        : Array.from({ length: handCardCount }).map((_, index) => ({
            id: `${seat.id}-hidden-${index}`,
            hidden: true,
            displayType: "hand",
            renderKey: `${seat.id}-hidden-slot-${index}`
          }));
  const publicCardsToRender = showPublicCards
    ? publicCards.map((card, index) => ({
        ...card,
        id: card.id || `${seat.id}-public-${index}`,
        displayType: "public",
        cardAccent: index === 0 ? "joker" : "reserve",
        renderKey: `${seat.id}-public-${index}-${card.id || "card"}`
      }))
    : [];
  const reservePlaceholderCard = shouldShowReservePlaceholder
    ? {
        id: `${seat.id}-reserve-placeholder`,
        hidden: true,
        displayType: "reserve_placeholder",
        cardAccent: "reserve",
        renderKey: `${seat.id}-reserve-placeholder`
      }
    : null;
  const cardsToRender = [
    ...handCardsToRender,
    ...publicCardsToRender,
    ...(reservePlaceholderCard ? [reservePlaceholderCard] : [])
  ];
  const seatState = seat.packed ? "PACK" : seat.seen ? "SEEN" : "BLIND";
  const sideShowStatus = sideShowResult
    ? sideShowResult.winnerId === seat.id
      ? "WIN"
      : sideShowResult.loserId === seat.id
        ? "LOSS"
        : null
    : null;
  const displayName = (() => {
    const raw =
      typeof seat.name === "string" && seat.name.trim()
        ? seat.name.trim()
        : `Player ${String(seat.seatIndex).padStart(2, "0")}`;
    const cleaned = raw
      .replace(/\b[Bb][Oo][Tt]\b/g, " ")
      .replace(/\s+/g, " ")
      .trim();
    return cleaned || raw;
  })();
  const handLabel = typeof seat.handLabel === "string" ? seat.handLabel.trim() : "";
  const showHandLabel =
    Boolean(handLabel) &&
    !isStarting &&
    !isDealing &&
    (isUser ? seat.seen : (cardsRevealed || isRoundComplete));
  const handLabelTone = getHandLabelTone(handLabel);

  return (
    <div
      className={`table-seat table-seat--index-${seat.seatIndex} absolute ${layout.wrapper} ${
        isUser ? "table-seat--user" : "table-seat--opponent"
      }`}
    >
      <div className="relative">
        <div
          ref={(node) => {
            if (typeof registerCardAnchor === "function") {
              registerCardAnchor(seat.id, node);
            }
          }}
          className={`table-seat__cards absolute z-[20] ${cardsWrapClass} ${isDealing ? "is-dealing" : ""} ${
            cardsRevealed ? "is-revealed" : ""
          }`}
          data-card-count={cardsToRender.length}
        >
          <div className={cardsRowClass}>
            {cardsToRender.map((card, index) => (
              <div
                key={card.renderKey || `${seat.id}-${index}`}
                className={`table-seat__dealt-card ${
                  cardsRevealed || index === 0 ? "" : layout.overlap
                }`}
              >
                <PlayingCard
                  card={card}
                  revealed={card.displayType === "public" || (hasVisibleCards && shouldRevealCards)}
                  compact={!isUser}
                  className={
                    card.hidden
                      ? layout.hiddenCardClass
                      : card.cardAccent === "joker"
                        ? "ring-1 ring-[#7ef6eb]/80"
                        : card.cardAccent === "reserve"
                          ? "ring-1 ring-[#ffd08a]/85"
                          : ""
                  }
                  style={
                    card.hidden
                      ? layout.hiddenCardStyles?.[index] ?? layout.hiddenCardStyles?.[layout.hiddenCardStyles.length - 1]
                      : cardsRevealed
                        ? revealedCardStyles[Math.min(index, revealedCardStyles.length - 1)]
                      : hasVisibleCards
                        ? layout.visibleCardStyles?.[index] ?? layout.visibleCardStyles?.[layout.visibleCardStyles.length - 1]
                        : card.displayType === "public"
                          ? revealedCardStyles[Math.min(index, revealedCardStyles.length - 1)]
                          : undefined
                  }
                />
              </div>
            ))}
          </div>
        </div>

        <div className={`table-seat__avatar ${layout.avatarShell} relative`}>
          {seat.isTurn ? (
            <TurnClock
              turnClock={turnClock}
              isUser={isUser}
              className="inset-0 h-full w-full"
            />
          ) : null}
          <div
            className={[
              "relative h-full w-full overflow-hidden",
              layout.avatarInner,
              isWinner ? "shadow-[0_0_24px_rgba(255,235,146,0.72)]" : "",
              seat.packed ? "opacity-70" : ""
            ].join(" ")}
          >
            <Image
              src={getAvatarImage(seat.avatar, seat.seatIndex)}
              alt={`${seat.name} avatar`}
              fill
              className="object-cover"
              sizes={isUser ? "(max-width: 768px) 100px, 100px" : "(max-width: 768px) 62px, 62px"}
            />
          </div>
        </div>

        <div className={layout.label}>
          <strong className={`block truncate font-black leading-[1] ${isUser ? "text-[12px] sm:text-[13px]" : "text-[9px] sm:text-[10px]"}`}>
            {displayName}
          </strong>
          {showHandLabel ? (
            <span
              className={`mx-auto mt-1 inline-block max-w-full truncate rounded-full border px-2 py-0.5 text-[7px] font-black uppercase leading-[1] tracking-[0.1em] sm:text-[8px] ${handLabelTone.className}`}
            >
              {handLabel}
            </span>
          ) : (
            <span className={`mt-[1px] block font-black uppercase leading-[1] tracking-[0.08em] ${isUser ? "text-[9px]" : "text-[7px] sm:text-[8px]"}`}>
              {isUser ? seatState : getOpponentActionLabel(seat.lastAction)}
            </span>
          )}
        </div>

        {actionNotice ? (
          <div
            className={`table-seat__action-notice absolute left-1/2 z-[30] -translate-x-1/2 ${
              isUser ? "bottom-[calc(100%+8px)]" : "bottom-[calc(100%+6px)]"
            }`}
          >
            <div className="whitespace-nowrap rounded-full border border-[#43d8cc]/35 bg-[linear-gradient(180deg,rgba(8,52,56,0.96),rgba(4,24,27,0.98))] px-2 py-0.5 text-[8px] font-black uppercase tracking-[0.12em] text-[#abfff5] shadow-[0_8px_16px_rgba(0,0,0,0.28)] sm:text-[9px]">
              {actionNotice}
            </div>
          </div>
        ) : null}

        {seat.seen && !seat.packed && !isUser ? (
          <div className="absolute left-1/2 top-[-10px] z-[26] -translate-x-1/2 rounded-full border border-[#fff0bc]/22 bg-[rgba(9,35,39,0.86)] px-2 py-0.5 text-[8px] font-black uppercase tracking-[0.14em] text-[#fff2be]">
            Seen
          </div>
        ) : null}

        {isWinner || sideShowStatus || !seat.connected ? (
          <div
            className={`pointer-events-none absolute left-1/2 z-[27] flex -translate-x-1/2 flex-col items-center gap-1 ${
              isUser ? "top-[calc(100%+4px)]" : "top-[calc(100%+8px)]"
            }`}
          >
            {isWinner ? (
              <div className="whitespace-nowrap rounded-full border border-[#ffe888]/40 bg-[linear-gradient(180deg,#fff2a8_0%,#d0a22e_100%)] px-3 py-1 text-[9px] font-black uppercase tracking-[0.18em] text-[#5c3900] shadow-[0_10px_20px_rgba(0,0,0,0.28)]">
                Winner
              </div>
            ) : null}

            {sideShowStatus ? (
              <div
                className={`whitespace-nowrap rounded-full px-3 py-1 text-[9px] font-black uppercase tracking-[0.18em] ${
                  sideShowStatus === "WIN"
                    ? "bg-[#d9ff87] text-[#294400]"
                    : "bg-[#ff9e9e] text-[#651010]"
                }`}
              >
                Side {sideShowStatus}
              </div>
            ) : null}

            {!seat.connected ? (
              <div className="whitespace-nowrap rounded-full border border-[#ffad9f]/30 bg-[rgba(87,20,20,0.88)] px-2.5 py-1 text-[8px] font-black uppercase tracking-[0.18em] text-[#ffe0d6]">
                OFFLINE
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
