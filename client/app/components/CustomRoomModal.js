"use client";

import { useState } from "react";
import Image from "next/image";
import { VARIANT_OPTIONS } from "../lib/variants";

function CustomRoomModalDialog({
  onClose,
  onCreateRoom,
  onJoinRoom,
  onStartGame,
  onLeaveRoom,
  roomState = null,
  roomCode = "",
  isHost = false,
  globalBootAmount = null,
  initialRoomCode = "",
  initialMode = "create",
  busy = false,
  error = "",
}) {
  const [mode, setMode] = useState(initialMode); // "create" | "join"
  const [inputRoomCode, setInputRoomCode] = useState(() => initialRoomCode || "");
  const [variant, setVariant] = useState("classic");
  const [localError, setLocalError] = useState("");
  const [copied, setCopied] = useState(false);

  const activeError = error || localError;
  const activeRoomCode = (roomCode || roomState?.roomCode || "").toUpperCase();
  const isInLobby = Boolean(
    activeRoomCode &&
    roomState &&
    (!roomState.round ||
      roomState.status === "lobby" ||
      roomState.status === "between_rounds" ||
      roomState.round?.status === "complete")
  );
  const lastRoundResult = roomState?.round?.status === "complete" ? roomState.round.result : null;
  const lastRoundWinAmount =
    typeof roomState?.round?.potAmount === "number" && roomState.round.potAmount > 0
      ? roomState.round.potAmount
      : typeof lastRoundResult?.payout === "number"
        ? lastRoundResult.payout
        : 0;

  const players = roomState?.players || [];
  const variantObj = typeof roomState?.config?.variant === "object"
    ? roomState.config.variant
    : VARIANT_OPTIONS.find((v) => v.id === (roomState?.config?.variant || variant));
  const variantLabel = variantObj?.label || (typeof roomState?.config?.variant === "string" ? roomState.config.variant : "Classic");
  const tableBootAmount = roomState?.config?.bootAmount ?? globalBootAmount ?? 100;
  const canStartGame = isHost && players.length >= 2;

  async function handleCopy() {
    if (!activeRoomCode) return;
    try {
      if (navigator?.clipboard?.writeText) {
        await navigator.clipboard.writeText(activeRoomCode);
      } else {
        const input = document.createElement("input");
        input.value = activeRoomCode;
        document.body.appendChild(input);
        input.select();
        document.execCommand("copy");
        document.body.removeChild(input);
      }
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {}
  }

  async function handleCreateSubmit(e) {
    e.preventDefault();
    setLocalError("");
    try {
      await onCreateRoom({ variant });
    } catch (err) {
      setLocalError(err?.message || "Failed to create custom room.");
    }
  }

  async function handleJoinSubmit(e) {
    e.preventDefault();
    setLocalError("");
    const trimmedCode = inputRoomCode.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");

    if (!trimmedCode) {
      setLocalError("Please enter a 6-character room code.");
      return;
    }

    try {
      await onJoinRoom({ roomCode: trimmedCode });
    } catch (err) {
      setLocalError(err?.message || "Failed to join custom room.");
    }
  }

  return (
    <div className="private-room-modal-backdrop" role="presentation">
      <div
        className="private-room-modal"
        role="dialog"
        aria-modal="true"
        aria-label={isInLobby ? "Custom Room Lobby" : mode === "create" ? "Create Custom Room" : "Join Custom Room"}
      >
        <button
          className="private-room-close"
          type="button"
          onClick={isInLobby && typeof onLeaveRoom === "function" ? onLeaveRoom : onClose}
          disabled={busy}
          aria-label="Close dialog"
        >
          ×
        </button>

        {isInLobby ? (
          /* ========================================================== */
          /*                    ROOM LOBBY VIEW                         */
          /* ========================================================== */
          <div className="flex flex-col gap-4">
            <div className="private-room-modal-copy !mb-2">
              <div className="flex items-center gap-2">
                <p className="private-room-kicker">Custom Room Lobby</p>
                <span className="rounded-full bg-[#33d552]/20 px-2 py-0.5 text-[10px] font-semibold sm:font-extrabold uppercase tracking-wider text-[#96f2a5]">
                  No Bots
                </span>
              </div>
              <h2>{roomState?.roomName || "Custom Room"}</h2>
              <span>
                Share this room code with friends. The game will start when the host clicks Start Game.
              </span>
            </div>

            {lastRoundResult ? (
              <div className="rounded-xl border border-[#65f27c]/35 bg-[#33d552]/12 px-3.5 py-2.5 text-center text-xs font-medium sm:font-bold text-[#c7ffd0]">
                🏆 Last Round: <strong className="font-semibold sm:font-black text-white">{lastRoundResult.winnerName || "Winner"}</strong> won
                {lastRoundWinAmount > 0 ? ` ₹${lastRoundWinAmount.toLocaleString("en-IN")}` : ""}
                {lastRoundResult.winningHand ? ` with ${lastRoundResult.winningHand}` : ""}.
              </div>
            ) : null}

            {/* Room Code Card */}
            <div className="rounded-2xl border-2 border-[#efc24e]/70 bg-gradient-to-b from-[#3a080e] to-[#1e0407] p-4 text-center shadow-[0_12px_28px_rgba(0,0,0,0.38)]">
              <span className="block text-[11px] font-semibold sm:font-extrabold uppercase tracking-[0.2em] text-[#ffd778]">
                Room Code
              </span>
              <div className="my-2.5 flex items-center justify-center gap-3">
                <span className="font-mono text-3xl sm:text-4xl font-bold sm:font-black tracking-[0.28em] text-white select-all">
                  {activeRoomCode}
                </span>
                <button
                  type="button"
                  onClick={handleCopy}
                  className="rounded-xl border border-[#ffe46d]/40 bg-gradient-to-b from-[#ffd973] to-[#c98e1f] px-3.5 py-2 text-xs font-semibold sm:font-black text-[#2e1304] shadow-md transition hover:scale-105 active:scale-95"
                >
                  {copied ? "Copied!" : "Copy Code"}
                </button>
              </div>
              <div className="flex items-center justify-center gap-4 text-xs font-medium sm:font-semibold text-white/70">
                <span>
                  Variant: <strong className="font-semibold sm:font-bold text-[#ffe6a0]">{variantLabel}</strong>
                </span>
                <span>•</span>
                <span>
                  Boot: <strong className="font-semibold sm:font-bold text-[#ffe46d]">₹{tableBootAmount.toLocaleString("en-IN")}</strong>
                </span>
              </div>
            </div>

            {/* Players in Lobby */}
            <div>
              <div className="mb-2.5 flex items-center justify-between">
                <span className="text-xs font-semibold sm:font-black uppercase tracking-wider text-[#ffe7a2]">
                  Players in Lobby ({players.length}/6)
                </span>
                {players.length < 2 ? (
                  <span className="text-[11px] font-medium sm:font-bold text-[#ffbe76]">
                    Waiting for at least 1 more player...
                  </span>
                ) : (
                  <span className="text-[11px] font-medium sm:font-bold text-[#96f2a5]">
                    ✓ Ready to start
                  </span>
                )}
              </div>

              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                {players.map((p, idx) => (
                  <div
                    key={p.id || idx}
                    className="flex items-center gap-2.5 rounded-xl border border-[#ffe46d]/25 bg-black/40 p-2.5 shadow-sm"
                  >
                    <div className="relative flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-[#ffe46d]/40 bg-gradient-to-br from-[#ffd973]/25 to-black/70 text-lg">
                      {p.avatar ? (
                        <Image
                          src={p.avatar === "you" ? "/newAssets/avatars/avatar2.png" : "/newAssets/avatars/avatar1.png"}
                          alt=""
                          width={32}
                          height={32}
                          className="rounded-full object-cover"
                        />
                      ) : (
                        <span>{p.isHost ? "👑" : "👤"}</span>
                      )}
                    </div>
                    <div className="min-w-0 flex-1">
                      <strong className="block truncate text-xs font-semibold sm:font-black text-white">
                        {p.name || (p.isHost ? "Host" : "Player")}
                      </strong>
                      <span className="block truncate text-[10px] font-medium sm:font-bold text-[#ffe7a2]/75">
                        {p.isHost ? "👑 Host" : p.isViewer ? "You" : "Connected"}
                      </span>
                    </div>
                  </div>
                ))}

                {/* Empty waiting slots */}
                {Array.from({ length: Math.max(0, Math.min(6 - players.length, 6 - players.length)) }).map((_, i) => (
                  <div
                    key={`empty-${i}`}
                    className="flex items-center gap-2 rounded-xl border border-dashed border-white/15 bg-white/[0.02] p-2.5 text-white/40"
                  >
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-dashed border-white/20 text-xs">
                      ⌛
                    </div>
                    <span className="text-[11px] font-normal sm:font-medium italic">Waiting for friend...</span>
                  </div>
                ))}
              </div>
            </div>

            {activeError ? (
              <div className="private-room-error text-center" role="alert">
                {activeError}
              </div>
            ) : null}

            {/* Lobby Action Controls */}
            <div className="flex flex-col gap-2 pt-1">
              {isHost ? (
                <button
                  type="button"
                  onClick={onStartGame}
                  disabled={busy || !canStartGame}
                  className={[
                    "min-h-[54px] w-full rounded-2xl border-2 text-base font-semibold sm:font-black shadow-lg transition",
                    canStartGame
                      ? "border-[#65f27c] bg-gradient-to-b from-[#33d552] to-[#168122] text-white hover:scale-[1.01] active:scale-[0.99] cursor-pointer"
                      : "border-white/10 bg-white/10 text-white/40 cursor-not-allowed",
                  ].join(" ")}
                >
                  {busy ? "Starting Round..." : canStartGame ? "Start Game" : "Waiting for Players (Min 2)"}
                </button>
              ) : (
                <div className="rounded-xl border border-[#ffe46d]/20 bg-black/35 p-3 text-center text-xs font-medium sm:font-semibold text-white/80">
                  Waiting for the host to start the game...
                </div>
              )}

              <button
                type="button"
                onClick={typeof onLeaveRoom === "function" ? onLeaveRoom : onClose}
                disabled={busy}
                className="min-h-[42px] w-full rounded-xl border border-white/15 bg-white/5 text-xs font-medium sm:font-bold text-white/70 hover:bg-white/10 transition"
              >
                Leave Lobby
              </button>
            </div>
          </div>
        ) : (
          /* ========================================================== */
          /*               CREATE / JOIN FORM TABS                      */
          /* ========================================================== */
          <div>
            <div className="private-room-modal-copy">
              <p className="private-room-kicker">Custom Room</p>
              <h2>{mode === "create" ? "Create Room" : "Join with Code"}</h2>
              <span>
                Play privately with friends. No bots, strict room code access only.
              </span>
            </div>

            <div className="private-room-toggle" role="tablist" aria-label="Room mode">
              <button
                type="button"
                role="tab"
                aria-selected={mode === "create"}
                className={mode === "create" ? "is-active" : ""}
                onClick={() => {
                  setMode("create");
                  setLocalError("");
                }}
                disabled={busy}
              >
                Create Room
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === "join"}
                className={mode === "join" ? "is-active" : ""}
                onClick={() => {
                  setMode("join");
                  setLocalError("");
                }}
                disabled={busy}
              >
                Join Room
              </button>
            </div>

            {mode === "create" ? (
              <form className="private-room-form" onSubmit={handleCreateSubmit}>
                <div className="private-room-field">
                  <span>Game Variant</span>
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {VARIANT_OPTIONS.map((opt) => {
                      const isSelected = variant === opt.id;
                      return (
                        <button
                          key={opt.id}
                          type="button"
                          onClick={() => setVariant(opt.id)}
                          disabled={busy}
                          className={[
                            "flex flex-col items-start rounded-[14px] border px-3 py-2 text-left transition",
                            isSelected
                              ? "border-[#ffd778] bg-[linear-gradient(180deg,rgba(162,87,18,0.4),rgba(70,25,12,0.6))] shadow-[0_4px_12px_rgba(0,0,0,0.3)] text-white"
                              : "border-white/10 bg-black/25 text-white/70 hover:border-white/30",
                          ].join(" ")}
                        >
                          <strong className="text-xs font-semibold sm:font-black uppercase tracking-wider text-[#ffe7a2]">
                            {opt.label}
                          </strong>
                          <span className="mt-0.5 line-clamp-1 text-[11px] font-normal text-white/60">
                            {opt.summary}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="flex items-center justify-between rounded-[14px] border border-white/10 bg-black/25 px-4 py-3 text-xs text-white/80">
                  <span className="font-medium sm:font-semibold text-white/70">Table Boot Amount</span>
                  <strong className="font-mono text-sm font-semibold sm:font-black text-[#ffe46d]">
                    ₹{typeof globalBootAmount === "number" ? globalBootAmount.toLocaleString("en-IN") : "100"}
                  </strong>
                </div>

                <div className="rounded-[14px] border border-white/10 bg-black/25 p-3 text-xs text-white/70">
                  <strong className="block font-semibold sm:font-bold text-[#ffe7a2]">Private & Protected</strong>
                  Only friends who enter your room code can join. Throughout all rounds, no bots are ever added.
                </div>

                {activeError ? (
                  <div className="private-room-error" role="alert">
                    {activeError}
                  </div>
                ) : null}

                <button
                  type="submit"
                  className="private-room-submit"
                  disabled={busy}
                >
                  {busy ? "Creating Custom Room..." : "Create Custom Room"}
                </button>
              </form>
            ) : (
              <form className="private-room-form" onSubmit={handleJoinSubmit}>
                <label className="private-room-field">
                  <span>Room Code</span>
                  <input
                    type="text"
                    value={inputRoomCode}
                    onChange={(e) =>
                      setInputRoomCode(
                        e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8)
                      )
                    }
                    placeholder="e.g. ABCDEF"
                    className="font-mono text-center text-2xl font-semibold sm:font-black tracking-[0.3em] uppercase"
                    disabled={busy}
                    autoFocus
                    required
                  />
                </label>

                <div className="rounded-[14px] border border-white/10 bg-black/25 p-3 text-xs text-white/70">
                  <strong className="block font-semibold sm:font-bold text-[#ffe7a2]">Invite-Only Table</strong>
                  Only players who enter this exact room code can join. Bots and random users cannot enter this room.
                </div>

                {activeError ? (
                  <div className="private-room-error" role="alert">
                    {activeError}
                  </div>
                ) : null}

                <button
                  type="submit"
                  className="private-room-submit"
                  disabled={busy || !inputRoomCode.trim()}
                >
                  {busy ? "Joining Room..." : "Join Custom Room"}
                </button>
              </form>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default function CustomRoomModal(props) {
  if (!props.open) {
    return null;
  }
  return <CustomRoomModalDialog {...props} />;
}
