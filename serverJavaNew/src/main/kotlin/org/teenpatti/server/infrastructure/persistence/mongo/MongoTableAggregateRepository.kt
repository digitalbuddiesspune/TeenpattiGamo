package org.teenpatti.server.infrastructure.persistence.mongo

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.ReturnDocument
import org.bson.Document
import org.springframework.stereotype.Component
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.game.TableState
import org.teenpatti.server.infrastructure.persistence.TableAggregateRepository
import java.util.concurrent.TimeUnit

@Component
internal class MongoTableAggregateRepository(
    mongoClient: MongoClient,
    private val objectMapper: ObjectMapper,
    env: AppEnvironment,
) : TableAggregateRepository {
    private val collection: MongoCollection<Document> = mongoClient.getDatabase(env.mongoDbName).getCollection("tables")

    init {
        collection.createIndex(Document("updatedAt", -1))
        collection.createIndex(Document("tableType", 1).append("variantId", 1))
        collection.createIndex(Document("expiresAt", 1), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
    }

    override fun loadTable(tableId: String): TableState? {
        val document = collection.find(Filters.eq("_id", tableId)).first()
        return if (document == null) null else decodeTable(document)
    }

    override fun saveTable(state: TableState): TableState {
        val next = objectMapper.convertValue(state, TableState::class.java)
        next.config = state.config
        next.version = state.version + 1
        PersistenceSupport.replaceVersioned(collection, state.id, state.version, encodeTable(next))
        return next
    }

    override fun claimLease(tableId: String, leaseOwner: String, leaseExpiresAt: String, now: String): TableState? {
        val updated =
            collection.findOneAndUpdate(
                Filters.and(
                    Filters.eq("_id", tableId),
                    Filters.or(
                        Filters.exists("leaseOwner", false),
                        Filters.eq("leaseOwner", null),
                        Filters.eq("leaseOwner", leaseOwner),
                        Filters.lte("leaseExpiresAt", requireNotNull(PersistenceSupport.toDate(now))),
                    ),
                ),
                Document(
                    "\$set",
                    Document("leaseOwner", leaseOwner)
                        .append("leaseExpiresAt", requireNotNull(PersistenceSupport.toDate(leaseExpiresAt)))
                        .append("updatedAt", requireNotNull(PersistenceSupport.toDate(now))),
                ),
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )
        return if (updated == null) null else decodeTable(updated)
    }

    override fun listActiveTables(tableType: String?, variantId: String?): List<TableState> {
        val filters = mutableListOf<org.bson.conversions.Bson>()
        filters += Filters.or(Filters.exists("expiresAt", false), Filters.eq("expiresAt", null))
        if (tableType != null) {
            filters += Filters.eq("tableType", tableType)
        }
        if (variantId != null) {
            filters += Filters.eq("variantId", variantId)
        }
        return collection.find(Filters.and(filters)).map { decodeTable(it) }.into(mutableListOf())
    }

    private fun decodeTable(document: Document): TableState {
        val source = PersistenceSupport.normalizeDocument(document).toMutableMap()
        source["id"] = source.remove("_id") as Any
        val history = source.remove("historyWindow") ?: source.getOrDefault("history", emptyList<Any>())
        source["history"] = history
        return objectMapper.convertValue(source, TableState::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeTable(state: TableState): Document {
        val payload = objectMapper.convertValue(state, Map::class.java) as MutableMap<String, Any?>
        payload["_id"] = state.id
        payload.remove("id")
        payload["historyWindow"] = payload.remove("history")
        return PersistenceSupport.toDocument(payload as Map<String, Any?>)
    }
}
