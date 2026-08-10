const express = require("express");
const {
  listGames,
  getGameById,
  deleteGame,
  getSummary,
  listUsers,
} = require("../services/adminProfitLossService");
const { requireAdmin } = require("../services/adminAuthService");

const router = express.Router();

router.use(requireAdmin);

router.get("/summary", async (request, response, next) => {
  try {
    response.json(await getSummary(request.query));
  } catch (error) {
    next(error);
  }
});

router.get("/games", async (request, response, next) => {
  try {
    response.json(await listGames(request.query));
  } catch (error) {
    next(error);
  }
});

router.get("/games/:roundId", async (request, response, next) => {
  try {
    response.json(await getGameById(request.params.roundId));
  } catch (error) {
    next(error);
  }
});

router.delete("/games/:roundId", async (request, response, next) => {
  try {
    response.json(await deleteGame(request.params.roundId));
  } catch (error) {
    next(error);
  }
});

router.get("/users", async (request, response, next) => {
  try {
    response.json(await listUsers(request.query));
  } catch (error) {
    next(error);
  }
});

module.exports = router;
