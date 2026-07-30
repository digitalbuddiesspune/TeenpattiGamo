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
import org.teenpatti.server.infrastructure.persistence.PrivateRoomRepository
import org.teenpatti.server.privateroom.PrivateRoomState
import java.util.concurrent.TimeUnit

@Component
internal class MongoPrivateRoomRepository(
    mongoClient: MongoClient,
    private val objectMapper: ObjectMapper,
    env: AppEnvironment,
) : PrivateRoomRepository {
    private val collection: MongoCollection<Document> = mongoClient.getDatabase(env.mongoDbName).getCollection("private_rooms")

    init {
        collection.createIndex(Document("updatedAt", -1))
        collection.createIndex(Document("expiresAt", 1), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
    }

    override fun loadRoom(roomCode: String): PrivateRoomState? {
        var document = collection.find(Filters.eq("_id", roomCode)).first()
        if (document == null) {
            document = collection.find(Filters.eq("roomCode", roomCode)).first()
        }
        return if (document == null) null else decodeRoom(document)
    }

    override fun saveRoom(state: PrivateRoomState): PrivateRoomState {
        val next = objectMapper.convertValue(state, PrivateRoomState::class.java)
        next.version = state.version + 1
        PersistenceSupport.replaceVersioned(collection, state.roomCode, state.version, encodeRoom(next))
        return next
    }

    override fun claimLease(roomCode: String, leaseOwner: String, leaseExpiresAt: String, now: String): PrivateRoomState? {
        val updated =
            collection.findOneAndUpdate(
                Filters.and(
                    Filters.eq("_id", roomCode),
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
        return if (updated == null) null else decodeRoom(updated)
    }

    override fun listActiveRooms(): List<PrivateRoomState> =
        collection.find(
            Filters.and(
                Filters.or(Filters.exists("expiresAt", false), Filters.eq("expiresAt", null)),
                Filters.ne("status", "closed"),
            ),
        ).map { decodeRoom(it) }.into(mutableListOf())

    private fun decodeRoom(document: Document): PrivateRoomState {
        val source = PersistenceSupport.normalizeDocument(document).toMutableMap()
        source["roomCode"] = source.remove("_id") as Any
        val history = source.remove("historyWindow") ?: source.getOrDefault("history", emptyList<Any>())
        source["history"] = history
        return objectMapper.convertValue(source, PrivateRoomState::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeRoom(state: PrivateRoomState): Document {
        val payload = objectMapper.convertValue(state, Map::class.java) as MutableMap<String, Any?>
        payload["_id"] = state.roomCode
        payload.remove("roomCode")
        payload["historyWindow"] = payload.remove("history")
        return PersistenceSupport.toDocument(payload as Map<String, Any?>)
    }
}
