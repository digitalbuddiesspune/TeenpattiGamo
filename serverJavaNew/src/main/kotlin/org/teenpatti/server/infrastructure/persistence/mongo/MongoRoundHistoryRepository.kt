package org.teenpatti.server.infrastructure.persistence.mongo

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import org.bson.Document
import org.springframework.stereotype.Component
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.game.RoundHistoryEntry
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository

@Component
internal class MongoRoundHistoryRepository(
    mongoClient: MongoClient,
    private val objectMapper: ObjectMapper,
    env: AppEnvironment,
) : RoundHistoryRepository {
    private val collection: MongoCollection<Document> = mongoClient.getDatabase(env.mongoDbName).getCollection("round_history")

    init {
        collection.createIndex(Document("aggregateType", 1).append("aggregateId", 1).append("settledAt", -1))
        collection.createIndex(Document("participants.id", 1).append("settledAt", -1))
        collection.createIndex(Document("aggregateId", 1).append("participants.id", 1).append("settledAt", -1))
    }

    override fun appendRound(entry: RoundHistoryEntry) {
        if (collection.find(Filters.eq("_id", entry.id)).first() != null) {
            return
        }
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.convertValue(entry, Map::class.java) as MutableMap<String, Any?>
        payload["_id"] = entry.id
        payload.remove("id")
        collection.insertOne(Document(payload))
    }

    override fun loadRoundsForAggregate(aggregateType: String, aggregateId: String, limit: Int): List<RoundHistoryEntry> =
        collection.find(Filters.and(Filters.eq("aggregateType", aggregateType), Filters.eq("aggregateId", aggregateId)))
            .limit(limit)
            .map { document ->
                val source = document.toMutableMap()
                source["id"] = source.remove("_id") as Any
                objectMapper.convertValue(source, RoundHistoryEntry::class.java)
            }.into(mutableListOf())

    override fun listRecentRoundsForParticipants(participantIds: List<String>, offset: Int, limit: Int): List<RoundHistoryEntry> {
        if (participantIds.isEmpty()) {
            return emptyList()
        }
        return collection.find(Filters.`in`("participants.id", participantIds))
            .sort(Sorts.descending("settledAt"))
            .skip(offset)
            .limit(limit)
            .map { document ->
                val source = document.toMutableMap()
                source["id"] = source.remove("_id") as Any
                objectMapper.convertValue(source, RoundHistoryEntry::class.java)
            }.into(mutableListOf())
    }
}
