"use client";

import { useCallback, useEffect, useRef, useState, startTransition } from "react";
import {
  createPrivateRoom,
  joinPrivateRoom,
  createPlatformPrivateRoom,
  joinPlatformPrivateRoom,
  fetchPrivateRoomSession,
  leavePrivateRoom as apiLeavePrivateRoom,
  getPrivateRoomWebSocketUrl
} from "../lib/api";
import { getPlatformLaunchContext } from "../lib/platformLaunch";
import { createClientSeed } from "../lib/clientSeed";

const SOCKET_HEARTBEAT_INTERVAL_MS = 15000;
const SOCKET_ACK_TIMEOUT_MS = 10000;
const SOCKET_RECONNECT_MAX_ATTEMPTS = 10;

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

function getPrivateSessionStorageKey(roomCode) {
  const code = (roomCode || "").trim().toUpperCase();
  return `teen-patti-private-session:${code}:${getWindowId()}`;
}

export function readStoredPrivateSession(roomCode) {
  try {
    if (typeof window === "undefined" || !roomCode) {
      return null;
    }
    const raw = window.sessionStorage.getItem(getPrivateSessionStorageKey(roomCode));
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw);
    if (!parsed?.playerId || !parsed?.playerToken || !parsed?.roomCode) {
      window.sessionStorage.removeItem(getPrivateSessionStorageKey(roomCode));
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function writeStoredPrivateSession(roomCode, session) {
  try {
    if (typeof window === "undefined" || !roomCode) {
      return;
    }
    const key = getPrivateSessionStorageKey(roomCode);
    if (!session?.playerId || !session?.playerToken) {
      window.sessionStorage.removeItem(key);
      return;
    }
    window.sessionStorage.setItem(
      key,
      JSON.stringify({
        roomCode: (roomCode || "").trim().toUpperCase(),
        playerId: session.playerId,
        playerToken: session.playerToken,
        clientSeed: session.clientSeed || ""
      })
    );
  } catch {}
}

export function clearStoredPrivateSession(roomCode) {
  writeStoredPrivateSession(roomCode, null);
}

function normalizePrivateRoomState(rawState, currentSession) {
  if (!rawState) {
    return null;
  }

  const room = rawState.roomState || rawState;
  const viewerId = room.viewerPlayerId || currentSession?.playerId || "";
  const hostId = room.hostPlayerId || "";
  const isHost = Boolean(viewerId && hostId && viewerId === hostId);

  return {
    ...room,
    roomCode: (room.roomCode || currentSession?.roomCode || "").toUpperCase(),
    roomName: room.roomName || "Custom Room",
    status: room.status || "lobby",
    hostPlayerId: hostId,
    viewerPlayerId: viewerId,
    isHost,
    playerId: viewerId,
    playerToken: currentSession?.playerToken || "",
    players: room.players || [],
    round: room.round || null,
    nextRound: room.nextRound || null,
    config: room.config || null,
    history: room.history || []
  };
}

function getReconnectDelay(attempt) {
  if (attempt <= 0) return 500;
  if (attempt === 1) return 1000;
  if (attempt === 2) return 2000;
  return 5000;
}

export function usePrivateRoom(initialRoomCode = "", enabled = true) {
  const [currentRoomCode, setCurrentRoomCode] = useState(() => (initialRoomCode || "").trim().toUpperCase());
  const [roomState, setRoomState] = useState(null);
  const [loading, setLoading] = useState(false);
  const [acting, setActing] = useState(false);
  const [error, setError] = useState("");

  const sessionRef = useRef(null);
  const socketRef = useRef(null);
  const reconnectTimeoutRef = useRef(null);
  const reconnectAttemptRef = useRef(0);
  const heartbeatIntervalRef = useRef(null);
  const manualCloseRef = useRef(false);
  const socketReadyRef = useRef(false);
  const connectingSocketRef = useRef(false);
  const pendingRequestsRef = useRef(new Map());
  const requestSequenceRef = useRef(1);

  function nextRequestId() {
    const current = requestSequenceRef.current;
    requestSequenceRef.current += 1;
    return `private-room-request-${current}`;
  }

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      window.clearTimeout(reconnectTimeoutRef.current);
      reconnectTimeoutRef.current = null;
    }
  }, []);

  const clearHeartbeat = useCallback(() => {
    if (heartbeatIntervalRef.current) {
      window.clearInterval(heartbeatIntervalRef.current);
      heartbeatIntervalRef.current = null;
    }
  }, []);

  const rejectPendingRequests = useCallback((message) => {
    pendingRequestsRef.current.forEach(({ reject, timeoutId }) => {
      if (timeoutId) {
        window.clearTimeout(timeoutId);
      }
      reject(new Error(message));
    });
    pendingRequestsRef.current.clear();
  }, []);

  const applyRoomState = useCallback((incoming) => {
    if (!incoming) return;
    const normalized = normalizePrivateRoomState(incoming, sessionRef.current);
    startTransition(() => {
      setRoomState(normalized);
      setLoading(false);
    });
  }, []);

  const startHeartbeat = useCallback((socket) => {
    clearHeartbeat();
    heartbeatIntervalRef.current = window.setInterval(() => {
      if (socket.readyState !== WebSocket.OPEN) {
        return;
      }
      try {
        socket.send(JSON.stringify({ type: "private_room:ping", payload: {} }));
      } catch {}
    }, SOCKET_HEARTBEAT_INTERVAL_MS);
  }, [clearHeartbeat]);

  const emitWithAck = useCallback(async (type, payload = {}) => {
    const socket = socketRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN || !socketReadyRef.current) {
      throw new Error("Private room connection is not ready.");
    }

    const requestId = nextRequestId();

    return new Promise((resolve, reject) => {
      const timeoutId = window.setTimeout(() => {
        const pending = pendingRequestsRef.current.get(requestId);
        if (!pending) {
          return;
        }
        pendingRequestsRef.current.delete(requestId);
        reject(new Error("Private room request timed out."));
      }, SOCKET_ACK_TIMEOUT_MS);

      pendingRequestsRef.current.set(requestId, {
        resolve: (data) => {
          window.clearTimeout(timeoutId);
          resolve(data);
        },
        reject: (err) => {
          window.clearTimeout(timeoutId);
          reject(err);
        },
        timeoutId
      });

      try {
        socket.send(
          JSON.stringify({
            type,
            requestId,
            payload
          })
        );
      } catch (err) {
        window.clearTimeout(timeoutId);
        pendingRequestsRef.current.delete(requestId);
        reject(err instanceof Error ? err : new Error("Failed to send request."));
      }
    });
  }, []);

  const connectSocket = useCallback(async (sessionData) => {
    if (!sessionData?.roomCode || !sessionData?.playerId || !sessionData?.playerToken) {
      return;
    }

    socketReadyRef.current = false;
    connectingSocketRef.current = true;

    const previousSocket = socketRef.current;
    socketRef.current = null;
    if (previousSocket && previousSocket.readyState < WebSocket.CLOSING) {
      try {
        previousSocket.close();
      } catch {}
    }

    try {
      await new Promise((resolve, reject) => {
        const socket = new WebSocket(getPrivateRoomWebSocketUrl());
        socketRef.current = socket;
        let settled = false;

        const settle = (fn, value) => {
          if (settled) return;
          settled = true;
          fn(value);
        };

        socket.addEventListener("open", () => {
          reconnectAttemptRef.current = 0;
          startHeartbeat(socket);
          const requestId = nextRequestId();
          const timeoutId = window.setTimeout(() => {
            const pending = pendingRequestsRef.current.get(requestId);
            if (!pending) return;
            pendingRequestsRef.current.delete(requestId);
            socketReadyRef.current = false;
            settle(reject, new Error("Private room authentication timed out."));
          }, SOCKET_ACK_TIMEOUT_MS);

          pendingRequestsRef.current.set(requestId, {
            resolve: (data) => {
              window.clearTimeout(timeoutId);
              if (socketRef.current !== socket) {
                settle(reject, new Error("Private room connection closed."));
                return;
              }
              socketReadyRef.current = true;
              if (data?.roomState) {
                applyRoomState(data.roomState);
              }
              setError("");
              settle(resolve);
            },
            reject: (nextError) => {
              window.clearTimeout(timeoutId);
              socketReadyRef.current = false;
              settle(reject, nextError instanceof Error ? nextError : new Error(String(nextError)));
            },
            timeoutId
          });

          try {
            socket.send(
              JSON.stringify({
                type: "private_room:authenticate",
                requestId,
                payload: {
                  roomCode: sessionData.roomCode,
                  playerId: sessionData.playerId,
                  playerToken: sessionData.playerToken
                }
              })
            );
          } catch (err) {
            window.clearTimeout(timeoutId);
            pendingRequestsRef.current.delete(requestId);
            settle(reject, err instanceof Error ? err : new Error("Private room connection failed."));
          }
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

            if (message.type === "private_room:snapshot" && message.payload) {
              applyRoomState(message.payload);
              setError("");
              return;
            }

            if (message.type === "private_room:session_closed") {
              manualCloseRef.current = true;
              socketReadyRef.current = false;
              clearReconnectTimer();
              clearStoredPrivateSession(sessionData.roomCode);
              sessionRef.current = null;
              setError(message.message || "Private room session expired.");
              startTransition(() => {
                setRoomState(null);
              });
              setActing(false);
              socket.close();
              return;
            }

            if (message.type === "private_room:error") {
              setError(message.message || "Private room action failed.");
            }
          } catch {
            setError("Unable to process live private room updates.");
          }
        });

        socket.addEventListener("close", () => {
          if (socketRef.current !== socket) return;

          socketRef.current = null;
          socketReadyRef.current = false;
          clearHeartbeat();
          rejectPendingRequests("Private room connection closed.");
          setActing(false);

          if (manualCloseRef.current || !sessionRef.current) {
            settle(reject, new Error("Private room connection closed."));
            return;
          }

          if (reconnectAttemptRef.current >= SOCKET_RECONNECT_MAX_ATTEMPTS) {
            setError("Unable to maintain private room connection. Please rejoin.");
            settle(reject, new Error("Max reconnection attempts reached."));
            return;
          }

          const delay = getReconnectDelay(reconnectAttemptRef.current);
          reconnectAttemptRef.current += 1;
          setError("Connection interrupted. Reconnecting to custom room...");

          clearReconnectTimer();
          reconnectTimeoutRef.current = window.setTimeout(() => {
            if (!sessionRef.current) return;
            connectSocket(sessionRef.current).catch(() => {});
          }, delay);
        });

        socket.addEventListener("error", () => {
          socketReadyRef.current = false;
          if (socket.readyState === WebSocket.OPEN) {
            socket.close();
          }
        });
      });
    } finally {
      connectingSocketRef.current = false;
    }
  }, [applyRoomState, clearHeartbeat, clearReconnectTimer, rejectPendingRequests, startHeartbeat]);

  // Handle initialization or reconnection from stored session
  useEffect(() => {
    if (!enabled || !currentRoomCode) {
      return undefined;
    }

    const stored = readStoredPrivateSession(currentRoomCode);
    if (!stored?.playerId || !stored?.playerToken) {
      return undefined;
    }

    sessionRef.current = stored;
    manualCloseRef.current = false;
    setLoading(true);

    fetchPrivateRoomSession(currentRoomCode, stored.playerId, stored.playerToken)
      .then((data) => {
        const state = data?.roomState || data;
        applyRoomState(state);
        connectSocket(stored).catch(() => {});
      })
      .catch((err) => {
        clearStoredPrivateSession(currentRoomCode);
        sessionRef.current = null;
        setError(err?.message || "Failed to restore custom room session.");
        setLoading(false);
      });

    return () => {
      clearReconnectTimer();
      clearHeartbeat();
      const currentSocket = socketRef.current;
      socketRef.current = null;
      socketReadyRef.current = false;
      if (currentSocket && currentSocket.readyState < WebSocket.CLOSING) {
        currentSocket.close();
      }
    };
  }, [currentRoomCode, enabled, applyRoomState, clearHeartbeat, clearReconnectTimer, connectSocket]);

  const handleCreateRoom = useCallback(async ({ roomName, playerName, variant = "classic", bootAmount = 1000 }) => {
    setLoading(true);
    setError("");
    manualCloseRef.current = false;

    try {
      const clientSeed = createClientSeed();
      const platformContext = getPlatformLaunchContext();
      const response = platformContext
        ? await createPlatformPrivateRoom(
            platformContext.token,
            platformContext.gameId,
            roomName,
            clientSeed,
            variant,
            bootAmount
          )
        : await createPrivateRoom(roomName, playerName, clientSeed, variant, bootAmount);

      const code = (response.roomCode || "").toUpperCase();
      const sessionData = {
        roomCode: code,
        playerId: response.playerId,
        playerToken: response.playerToken,
        clientSeed
      };

      sessionRef.current = sessionData;
      writeStoredPrivateSession(code, sessionData);
      setCurrentRoomCode(code);
      applyRoomState(response.roomState || response);

      await connectSocket(sessionData);
      return response;
    } catch (err) {
      const msg = err?.message || "Failed to create custom room.";
      setError(msg);
      setLoading(false);
      throw err;
    }
  }, [applyRoomState, connectSocket]);

  const handleJoinRoom = useCallback(async ({ roomCode, playerName }) => {
    const code = (roomCode || "").trim().toUpperCase();
    if (!code) {
      throw new Error("Room code is required.");
    }

    setLoading(true);
    setError("");
    manualCloseRef.current = false;

    try {
      const clientSeed = createClientSeed();
      const platformContext = getPlatformLaunchContext();
      const response = platformContext
        ? await joinPlatformPrivateRoom(
            platformContext.token,
            platformContext.gameId,
            code,
            clientSeed
          )
        : await joinPrivateRoom(code, playerName, clientSeed);

      const sessionData = {
        roomCode: code,
        playerId: response.playerId,
        playerToken: response.playerToken,
        clientSeed
      };

      sessionRef.current = sessionData;
      writeStoredPrivateSession(code, sessionData);
      setCurrentRoomCode(code);
      applyRoomState(response.roomState || response);

      await connectSocket(sessionData);
      return response;
    } catch (err) {
      const msg = err?.message || "Failed to join custom room.";
      setError(msg);
      setLoading(false);
      throw err;
    }
  }, [applyRoomState, connectSocket]);

  const runAction = useCallback(async (actionType, payload = {}) => {
    setActing(true);
    setError("");
    try {
      const result = await emitWithAck("private_room:action", {
        actionType,
        payload
      });
      if (result?.roomState) {
        applyRoomState(result.roomState);
      }
      return result;
    } catch (err) {
      const msg = err?.message || "Action failed.";
      setError(msg);
      throw err;
    } finally {
      setActing(false);
    }
  }, [applyRoomState, emitWithAck]);

  const startRound = useCallback(async () => {
    setActing(true);
    setError("");
    try {
      const result = await emitWithAck("private_room:start_round", {});
      if (result?.roomState) {
        applyRoomState(result.roomState);
      }
      return result;
    } catch (err) {
      const msg = err?.message || "Failed to start round.";
      setError(msg);
      throw err;
    } finally {
      setActing(false);
    }
  }, [applyRoomState, emitWithAck]);

  const readyNextRound = useCallback(async () => {
    setActing(true);
    setError("");
    try {
      const result = await emitWithAck("private_room:accept_next_round", {});
      if (result?.roomState) {
        applyRoomState(result.roomState);
      }
      return result;
    } catch (err) {
      const msg = err?.message || "Failed to ready up for next round.";
      setError(msg);
      throw err;
    } finally {
      setActing(false);
    }
  }, [applyRoomState, emitWithAck]);

  const startNextRound = useCallback(async () => {
    setActing(true);
    setError("");
    try {
      const result = await emitWithAck("private_room:next_round", {});
      if (result?.roomState) {
        applyRoomState(result.roomState);
      }
      return result;
    } catch (err) {
      const msg = err?.message || "Failed to start next round.";
      setError(msg);
      throw err;
    } finally {
      setActing(false);
    }
  }, [applyRoomState, emitWithAck]);

  const updateConfig = useCallback(async (variant, bootAmount) => {
    setActing(true);
    setError("");
    try {
      const result = await emitWithAck("private_room:update_config", {
        variant,
        bootAmount
      });
      if (result?.roomState) {
        applyRoomState(result.roomState);
      }
      return result;
    } catch (err) {
      const msg = err?.message || "Failed to update room settings.";
      setError(msg);
      throw err;
    } finally {
      setActing(false);
    }
  }, [applyRoomState, emitWithAck]);

  const leaveRoom = useCallback(async () => {
    manualCloseRef.current = true;
    clearReconnectTimer();
    clearHeartbeat();

    const session = sessionRef.current;
    if (session?.roomCode) {
      clearStoredPrivateSession(session.roomCode);
      if (socketRef.current && socketReadyRef.current) {
        try {
          await emitWithAck("private_room:leave", {});
        } catch {
          if (session.playerId && session.playerToken) {
            try {
              await apiLeavePrivateRoom(session.roomCode, session.playerId, session.playerToken);
            } catch {}
          }
        }
      }
    }

    if (socketRef.current && socketRef.current.readyState < WebSocket.CLOSING) {
      socketRef.current.close();
    }
    socketRef.current = null;
    socketReadyRef.current = false;
    sessionRef.current = null;
    setRoomState(null);
    setCurrentRoomCode("");
  }, [clearHeartbeat, clearReconnectTimer, emitWithAck]);

  return {
    roomCode: currentRoomCode,
    roomState,
    loading,
    acting,
    error,
    createRoom: handleCreateRoom,
    joinRoom: handleJoinRoom,
    runAction,
    startRound,
    readyNextRound,
    startNextRound,
    updateConfig,
    leaveRoom,
    clearError: () => setError("")
  };
}
