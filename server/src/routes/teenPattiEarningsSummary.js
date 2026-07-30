const express = require("express");
const { getTeenPattiEarningsSummary } = require("../services/earningsSummaryService");

function createEarningsSummaryRouter(dependencies = {}) {
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

module.exports = createEarningsSummaryRouter();
module.exports.createEarningsSummaryRouter = createEarningsSummaryRouter;
