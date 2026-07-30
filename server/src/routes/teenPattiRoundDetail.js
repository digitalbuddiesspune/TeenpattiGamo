const express = require("express");
const { findLatestRound } = require("../services/roundDetailService");
const { renderError, renderRoundDetail } = require("../views/roundDetailHtml");

function createRoundDetailRouter(options = {}) {
  const router = express.Router();
  const lookup = options.findLatestRound || findLatestRound;

  router.get("/round-detail", async (request, response) => {
    response.type("html");
    response.set({
      "Cache-Control": "no-store, no-cache, must-revalidate, private",
      Pragma: "no-cache",
      Expires: "0",
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "no-referrer",
    });

    try {
      const detail = await lookup(request.query);
      return response.status(200).send(renderRoundDetail(detail));
    } catch (error) {
      const statusCode = error.statusCode || 500;
      const message = statusCode === 500 ? "Unable to load the Teen Patti round." : error.message;
      return response.status(statusCode).send(renderError(statusCode, message));
    }
  });
  return router;
}

module.exports = createRoundDetailRouter();
module.exports.createRoundDetailRouter = createRoundDetailRouter;
