import express from "express";
import { getTeenPattiEarningsSummary } from "../services/earningsSummaryService.js";

export function createEarningsSummaryRouter(dependencies = {}) {
  const router = express.Router();
  const getSummary = dependencies.getTeenPattiEarningsSummary || getTeenPattiEarningsSummary;

  router.get("/earnings-summary", async (request, response, next) => {
    try {
      const payload = await getSummary(request.query);
      response.json(payload);
    } catch (error) {
      next(error);
    }
  });

  return router;
}

export default createEarningsSummaryRouter();
