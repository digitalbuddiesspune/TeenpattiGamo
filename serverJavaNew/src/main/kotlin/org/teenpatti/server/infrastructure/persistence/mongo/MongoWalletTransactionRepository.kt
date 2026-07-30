package org.teenpatti.server.infrastructure.persistence.mongo

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.springframework.stereotype.Component
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.infrastructure.persistence.WalletTransaction
import org.teenpatti.server.infrastructure.persistence.WalletTransactionRepository

@Component
internal class MongoWalletTransactionRepository(
    mongoClient: MongoClient,
    private val objectMapper: ObjectMapper,
    env: AppEnvironment,
) : WalletTransactionRepository {
    private val collection: MongoCollection<Document> =
        mongoClient.getDatabase(env.mongoDbName).getCollection("wallet_transactions")

    init {
        collection.createIndex(Document("txnId", 1), IndexOptions().unique(true))
        collection.createIndex(Document("operationKey", 1), IndexOptions().unique(true))
        collection.createIndex(Document("status", 1).append("txnType", 1).append("updatedAt", 1))
        collection.createIndex(Document("playerId", 1).append("roundId", 1).append("txnType", 1).append("updatedAt", -1))
        collection.createIndex(Document("platformUserId", 1).append("txnType", 1).append("updatedAt", -1))
        collection.createIndex(Document("platformUserId", 1).append("platformOperatorId", 1).append("playerId", 1))
    }

    override fun loadByOperationKey(operationKey: String): WalletTransaction? {
        val document = collection.find(Filters.eq("operationKey", operationKey)).first()
        return if (document == null) null else decode(document)
    }

    override fun save(transaction: WalletTransaction): WalletTransaction {
        val next = objectMapper.convertValue(transaction, WalletTransaction::class.java)
        collection.replaceOne(Filters.eq("_id", next.id), encode(next), com.mongodb.client.model.ReplaceOptions().upsert(true))
        return next
    }

    override fun listPendingCredits(limit: Int): List<WalletTransaction> =
        collection.find(
            Filters.and(
                Filters.eq("txnType", "credit"),
                Filters.`in`("status", listOf("created", "sent", "failed", "succeeded")),
            ),
        ).sort(Sorts.ascending("updatedAt")).limit(limit).map { decode(it) }.into(mutableListOf())

    override fun loadMostRecentDebit(playerId: String, roundId: String): WalletTransaction? {
        val document =
            collection.find(
                Filters.and(
                    Filters.eq("playerId", playerId),
                    Filters.eq("roundId", roundId),
                    Filters.eq("txnType", "debit"),
                    Filters.`in`("status", listOf("succeeded", "applied")),
                ),
            ).sort(Sorts.descending("updatedAt")).first()
        return if (document == null) null else decode(document)
    }

    override fun listRecentTransactions(platformUserId: String, txnType: String, offset: Int, limit: Int): List<WalletTransaction> =
        collection.find(
            Filters.and(
                Filters.eq("platformUserId", platformUserId),
                Filters.eq("txnType", txnType),
            ),
        ).sort(Sorts.descending("updatedAt")).skip(offset).limit(limit).map { decode(it) }.into(mutableListOf())

    override fun listPlayerIdsForPlatformUser(platformUserId: String): List<String> =
        collection.distinct("playerId", Filters.eq("platformUserId", platformUserId), String::class.java)
            .into(mutableListOf())

    private fun decode(document: Document): WalletTransaction {
        val source = PersistenceSupport.normalizeDocument(document).toMutableMap()
        source["id"] = source.remove("_id") as Any
        return objectMapper.convertValue(source, WalletTransaction::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encode(transaction: WalletTransaction): Document {
        val payload = objectMapper.convertValue(transaction, Map::class.java) as MutableMap<String, Any?>
        payload["_id"] = transaction.id
        payload.remove("id")
        return PersistenceSupport.toDocument(payload as Map<String, Any?>)
    }
}
