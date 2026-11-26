export function buildStakeControlState({
  round,
  userSeat,
  acting,
}) {
  const roundInactive = !round || round.status !== "active";
  const isTurn = userSeat?.isTurn && round?.status === "active";
  const balance = userSeat?.balance || 0;
  const stake = round?.currentStake || 0;
  const minimumStake = userSeat?.seen ? stake * 2 : stake;
  const controlsLocked = Boolean(acting);

  return {
    roundInactive,
    isTurn,
    balance,
    stake,
    minimumStake,
    canAffordCall: balance >= minimumStake,
    controlsLocked,
  };
}
