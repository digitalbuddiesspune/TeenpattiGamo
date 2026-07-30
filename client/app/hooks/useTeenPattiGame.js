"use client";

import { useCallback, useEffect, useRef, useState, startTransition } from "react";
import {
  joinPublicTable,
  createPlatformPublicSession,
  fetchPublicSession,
  leavePublicTable as apiLeavePublicTable,
  getPublicTableWebSocketUrl
} from "../lib/api";
import { createClientSeed } from "../lib/clientSeed";

const INITIAL_PUBLIC_LOADING_DELAY_MS = 0;
const pendingPublicSessionRequests = new Map();

function createWindowId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }

  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function getWindowId() {
  if (typeof window === "undefined") {
    return "server";
  }

  const current = window.name?.trim();
  if (current) {
    return current;
  }

  const next = `teen-patti-window-${createWindowId()}`;
  window.name = next;
  return next;
}

function getSessionStorageKey(variant) {
  return `teen-patti-public-session:${variant}:${getWindowId()}`;
}

function getPlatformLaunchContext() {
  if (typeof window === "undefined") {
    return null;
  }
  const params = new URLSearchParams(window.location.search);
  const token = params.get("id")?.trim() || "";
  if (!token) {
    return null;
  }
  const rawGameId = params.get("game_id")?.trim() || "";
  const gameId = Number(rawGameId);
  return { token, gameId };
}

function readStoredSession(variant) {
  try {
    const raw = window.sessionStorage.getItem(getSessionStorageKey(variant));
    if (!raw) {
      return null;
    }

    const parsed = JSON.parse(raw);
    if (!parsed?.playerId || !parsed?.playerToken || !parsed?.clientSeed) {
      window.sessionStorage.removeItem(getSessionStorageKey(variant));
      return null;
    }

    return parsed;
  } catch {
    return null;
  }
}

function writeStoredSession(variant, session) {
  try {
    const key = getSessionStorageKey(variant);
    if (!session?.playerId || !session?.playerToken || !session?.clientSeed) {
      window.sessionStorage.removeItem(key);
      pendingPublicSessionRequests.delete(key);
      return;
    }

    window.sessionStorage.setItem(
      key,
      JSON.stringify({
        playerId: session.playerId,
        playerToken: session.playerToken,
        clientSeed: session.clientSeed
      })
    );
  } catch {}
}

export function clearStoredPublicSession(variant) {
  writeStoredSession(variant, null);
  pendingPublicSessionRequests.delete(getSessionStorageKey(variant));
}

function createPublicSessionOnce(variant) {
  const key = getSessionStorageKey(variant);
  const pending = pendingPublicSessionRequests.get(key);
  if (pending) {
    return pending;
  }

  const request = (async () => {
    const clientSeed = createClientSeed();
    const platformContext = getPlatformLaunchContext();
    const data = platformContext
      ? await createPlatformPublicSession(platformContext.token, platformContext.gameId, clientSeed, variant)
      : await joinPublicTable(undefined, clientSeed, variant);

    return {
      ...data,
      clientSeed
    };
  })();

  pendingPublicSessionRequests.set(key, request);
  request.catch(() => {
    pendingPublicSessionRequests.delete(key);
  });
  return request;
}

function normalizeSession(session) {
  const table = session?.table || null;

  if (!table) {
    return null;
  }

  const roundSeats = table?.round?.seats || [];
  const viewerIsSeatedInCurrentRound =
    table?.round?.status !== "complete" &&
    roundSeats.some((seat) => seat?.isUser || seat?.id === session.playerId);
  const playerStatus = viewerIsSeatedInCurrentRound ? "active_at_table" : session.playerStatus;

  return {
    ...table,
    viewerPlayerStatus: viewerIsSeatedInCurrentRound ? "active_at_table" : table.viewerPlayerStatus,
    playerId: session.playerId,
    playerToken: session.playerToken,
    playerName: session.playerName,
    playerStatus,
    connected: session.connected,
    joinedAt: session.joinedAt,
    lastSeenAt: session.lastSeenAt
  };
}

function getReconnectDelay(attempt) {
  if (attempt <= 0) {
    return 500;
  }

  if (attempt === 1) {
    return 1000;
  }

  if (attempt === 2) {
    return 2000;
  }

  return 5000;
}

function waitForInitialLoadingDelay(startedAt) {
  const elapsed = Date.now() - startedAt;
  const remaining = INITIAL_PUBLIC_LOADING_DELAY_MS - elapsed;

  if (remaining <= 0) {
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    window.setTimeout(resolve, remaining);
  });
}

export function useTeenPattiGame(variant = "classic", enabled = true) {
  const [tableState, setTableState] = useState(null);
  const [loading, setLoading] = useState(true);
  const [acting, setActing] = useState(false);
  const [error, setError] = useState("");
  const sessionRef = useRef(null);
  const socketRef = useRef(null);
  const reconnectTimeoutRef = useRef(null);
  const reconnectAttemptRef = useRef(0);
  const manualCloseRef = useRef(false);
  const pendingRequestsRef = useRef(new Map());
  const requestSequenceRef = useRef(1);
  const packableActionsRef = useRef(new Set(["blind", "chaal", "raise", "show"]));

  function nextRequestId() {
    const current = requestSequenceRef.current;
    requestSequenceRef.current += 1;
    return `public-table-request-${current}`;
  }

  const applyState = useCallback((session) => {
    const clientSeed = session.clientSeed || sessionRef.current?.clientSeed;
    sessionRef.current = {
      playerId: session.playerId,
      playerToken: session.playerToken,
      clientSeed
    };
    writeStoredSession(variant, sessionRef.current);

    startTransition(() => {
      setTableState(normalizeSession(session));
      setLoading(false);
    });
  }, [variant]);

  function rejectPendingRequests(message) {
    pendingRequestsRef.current.forEach(({ reject }) => {
      reject(new Error(message));
    });
    pendingRequestsRef.current.clear();
  }

  function clearReconnectTimer() {
    if (reconnectTimeoutRef.current) {
      window.clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
  }

  async function emitWithAck(type, payload = {}) {
    const socket = socketRef.current;

    if (!socket || socket.readyState !== WebSocket.OPEN) {
      throw new Error("Public table connection is not ready.");
    }

    const requestId = nextRequestId();

    return new Promise((resolve, reject) => {
      pendingRequestsRef.current.set(requestId, { resolve, reject });

      socket.send(
        JSON.stringify({
          type,
          requestId,
          payload
        })
      );
    });
  }

  useEffect(() => {
    if (!enabled) {
      manualCloseRef.current = true;
      clearReconnectTimer();
      socketRef.current?.close();
      socketRef.current = null;
      rejectPendingRequests("Public table connection closed.");
      startTransition(() => {
        setTableState(null);
        setError("");
      });
      setLoading(false);
      return undefined;
    }

    let cancelled = false;
    manualCloseRef.current = false;

    setLoading(true);
    setError("");

    async function connectSocket(data) {
      if (cancelled) {
        return;
      }

      const socket = new WebSocket(getPublicTableWebSocketUrl());
      socketRef.current = socket;

      socket.addEventListener("open", () => {
        reconnectAttemptRef.current = 0;
        const requestId = nextRequestId();
        pendingRequestsRef.current.set(requestId, {
          resolve: () => {
            if (!cancelled) {
              setError("");
            }
          },
          reject: (nextError) => {
            if (!cancelled) {
              setError(nextError.message || "Unable to authenticate the public table session.");
            }
          }
        });

        socket.send(
          JSON.stringify({
            type: "public_table:authenticate",
            requestId,
            payload: {
              variant,
              playerId: data.playerId,
              playerToken: data.playerToken
            }
          })
        );
      });

      socket.addEventListener("message", (event) => {
        try {
          const message = JSON.parse(event.data);
          const pending = message.requestId ? pendingRequestsRef.current.get(message.requestId) : null;

          if (pending) {
            pendingRequestsRef.current.delete(message.requestId);
            if (message.status === "ok") {
              pending.resolve(message.data);
            } else {
              pending.reject(new Error(message.message || "Request failed."));
            }
            return;
          }

          if (message.type === "public_table:snapshot" && message.payload) {
            applyState(message.payload);
            setError("");
            return;
          }

          if (message.type === "public_table:session_closed") {
            manualCloseRef.current = true;
            clearReconnectTimer();
            writeStoredSession(variant, null);
            sessionRef.current = null;
            setError(message.message || "Public table session expired.");
            startTransition(() => {
              setTableState(null);
            });
            socket.close();
            return;
          }

          if (message.type === "public_table:error") {
            setError(message.message || "Public table action failed.");
          }
        } catch {
          if (!cancelled) {
            setError("Unable to process live table updates.");
          }
        }
      });

      socket.addEventListener("close", () => {
        if (socketRef.current === socket) {
          socketRef.current = null;
        }

        rejectPendingRequests("Public table connection closed.");

        if (cancelled || manualCloseRef.current || !sessionRef.current) {
          return;
        }

        reconnectAttemptRef.current += 1;
        const delay = getReconnectDelay(reconnectAttemptRef.current);
        setError("Live connection interrupted. Reconnecting...");
        clearReconnectTimer();
        reconnectTimeoutRef.current = window.setTimeout(async () => {
          try {
            const latest = await fetchPublicSession(
              sessionRef.current.playerId,
              sessionRef.current.playerToken,
              variant
            );
            if (cancelled || manualCloseRef.current) {
              return;
            }
            applyState({
              ...latest,
              clientSeed: sessionRef.current.clientSeed
            });
            await connectSocket({
              playerId: latest.playerId,
              playerToken: latest.playerToken
            });
          } catch (nextError) {
            if (!cancelled && !manualCloseRef.current) {
              writeStoredSession(variant, null);
              sessionRef.current = null;
              setError(nextError.message || "Public table session expired.");
              startTransition(() => {
                setTableState(null);
              });
            }
          }
        }, delay);
      });

      socket.addEventListener("error", () => {
        if (!cancelled && !manualCloseRef.current) {
          setError("Public table websocket connection failed.");
        }
      });
    }

    async function bootstrap() {
      const loadingStartedAt = Date.now();

      try {
        let session = readStoredSession(variant);
        let data;

        if (session?.playerId && session?.playerToken && session?.clientSeed) {
          try {
            data = await fetchPublicSession(session.playerId, session.playerToken, variant);
            data = {
              ...data,
              clientSeed: session.clientSeed
            };
          } catch {
            writeStoredSession(variant, null);
            sessionRef.current = null;
          }
        }

        if (!data) {
          data = await createPublicSessionOnce(variant);
        }

        while (!cancelled && data?.playerStatus === "matchmaking" && !data?.tableId) {
          const clientSeed = data.clientSeed || session?.clientSeed;
          sessionRef.current = {
            playerId: data.playerId,
            playerToken: data.playerToken,
            clientSeed
          };
          writeStoredSession(variant, sessionRef.current);
          setError("Teen Patti matchmaking is finding the right table. Please wait.");
          await new Promise((resolve) => window.setTimeout(resolve, 500));
          data = await fetchPublicSession(data.playerId, data.playerToken, variant);
          data = { ...data, clientSeed };
        }

        if (cancelled) {
          return;
        }

        await waitForInitialLoadingDelay(loadingStartedAt);

        if (cancelled) {
          return;
        }

        applyState(data);
        setError("");
        await connectSocket(data);
      } catch (nextError) {
        if (!cancelled) {
          await waitForInitialLoadingDelay(loadingStartedAt);

          if (cancelled) {
            return;
          }

          setError(nextError.message);
          setLoading(false);
        }
      }
    }

    bootstrap();

    return () => {
      cancelled = true;
      manualCloseRef.current = true;
      clearReconnectTimer();
      rejectPendingRequests("Public table connection closed.");
      socketRef.current?.close();
      socketRef.current = null;
    };
  }, [variant, enabled, applyState]);

  async function runAction(actionType, payload) {
    if (acting || !sessionRef.current) {
      return;
    }

    setActing(true);
    setError("");

    try {
      const data = await emitWithAck(
        "public_table:action",
        {
          actionType,
          payload
        }
      );
      applyState(data);
    } catch (nextError) {
      const message = nextError.message || "Request failed";

      if (message.includes("Insufficient balance") && packableActionsRef.current.has(actionType)) {
        try {
          const packed = await emitWithAck(
            "public_table:action",
            {
              actionType: "pack",
              payload: {}
            }
          );
          applyState(packed);
          setError("You don't have enough chips to place that bet. Your cards were packed.");
        } catch {
          setError("You don't have enough chips to place that bet.");
        }
      } else {
        setError(message);
      }
    } finally {
      setActing(false);
    }
  }

  async function startRound() {
    if (acting || !sessionRef.current) {
      return;
    }

    setActing(true);
    setError("");

    try {
      const data = await fetchPublicSession(
        sessionRef.current.playerId,
        sessionRef.current.playerToken,
        variant
      );
      applyState(data);
    } catch (nextError) {
      setError(nextError.message);
    } finally {
      setActing(false);
    }
  }

  async function readyNextRound() {
    if (acting || !sessionRef.current) {
      return;
    }

    setActing(true);
    setError("");

    try {
      const data = await emitWithAck(
        "public_table:action",
        {
          actionType: "ready_next_round",
          payload: {}
        }
      );
      applyState(data);
    } catch (nextError) {
      setError(nextError.message);
    } finally {
      setActing(false);
    }
  }

  async function leaveTable() {
    if (!sessionRef.current) {
      return;
    }

    manualCloseRef.current = true;
    clearReconnectTimer();

    try {
      if (socketRef.current?.readyState === WebSocket.OPEN) {
        await emitWithAck("public_table:leave");
      } else {
        await apiLeavePublicTable(
          sessionRef.current.playerId,
          sessionRef.current.playerToken,
          variant
        );
      }
    } catch {}

    writeStoredSession(variant, null);
    sessionRef.current = null;
    rejectPendingRequests("Public table connection closed.");
    socketRef.current?.close();
    socketRef.current = null;
    startTransition(() => {
      setTableState(null);
    });
  }

  async function startAutoplay() {
    setError("Autoplay is not available in public multiplayer.");
  }

  async function stopAutoplay() {
    setError("Autoplay is not available in public multiplayer.");
  }

  return {
    tableState,
    loading,
    acting,
    error,
    runAction,
    startRound,
    readyNextRound,
    startAutoplay,
    stopAutoplay,
    leaveTable
  };
}
