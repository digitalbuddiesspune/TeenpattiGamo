const express = require("express");
const { listTeenPattiRounds } = require("../services/roundHistoryService");

const router = express.Router();

router.get("/rounds", async (request, response, next) => {
  try {
    const payload = await listTeenPattiRounds(request.query);
    response.json(payload);
  } catch (error) {
    next(error);
  }
});

module.exports = router;
