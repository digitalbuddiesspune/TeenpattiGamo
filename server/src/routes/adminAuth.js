import express from "express";
import {
  login,
  logout,
  getSession,
  getBearerToken,
  requireAdmin,
} from "../services/adminAuthService.js";

const router = express.Router();

router.post("/login", (request, response, next) => {
  try {
    const result = login(request.body?.email, request.body?.password);
    response.json(result);
  } catch (error) {
    next(error);
  }
});

router.post("/logout", requireAdmin, (request, response) => {
  logout(request.adminToken);
  response.status(204).end();
});

router.get("/me", (request, response) => {
  const session = getSession(getBearerToken(request));
  if (!session) {
    return response.status(401).json({
      error: { code: "unauthorized", message: "Admin authentication required." },
    });
  }
  return response.json(session);
});

export default router;
