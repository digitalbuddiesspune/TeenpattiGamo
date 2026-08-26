import express from "express";
import { listTeenPattiRounds } from "../services/roundHistoryService.js";

const router = express.Router();

router.get("/rounds", async (request, response, next) => {
  try {
    const payload = await listTeenPattiRounds(request.query);
    response.json(payload);
  } catch (error) {
    next(error);
  }
});

export default router;
