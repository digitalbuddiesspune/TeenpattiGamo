import { MongoClient } from "mongodb";
import config from "./env.js";

let client;
let database;

async function connectMongo() {
  if (database) {
    return database;
  }

  client = new MongoClient(config.mongoUri);
  await client.connect();
  database = client.db(config.mongoDbName);
  return database;
}

function getMongoClient() {
  return client;
}

async function closeMongo() {
  if (client) {
    await client.close();
    client = undefined;
    database = undefined;
  }
}

export { closeMongo, connectMongo, getMongoClient };
