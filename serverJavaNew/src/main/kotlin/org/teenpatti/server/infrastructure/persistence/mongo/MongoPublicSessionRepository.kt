package org.teenpatti.server.infrastructure.persistence.mongo

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import org.bson.Document
import org.springframework.stereotype.Component
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.infrastructure.persistence.PublicSessionRepository
import org.teenpatti.server.publictable.PublicPlayerSessionState
import java.util.concurrent.TimeUnit

@Component
internal class MongoPublicSessionRepository(
    mongoClient: MongoClient,
    private val objectMapper: ObjectMapper,
    env: AppEnvironment,
) : PublicSessionRepository {
    private val collection: MongoCollection<Document> =
        mongoClient.getDatabase(env.mongoDbName).getCollection("public_player_sessions")

    init {
        collection.createIndex(Document("variantId", 1).append("tableId", 1))
        collection.createIndex(Document("expiresAt", 1), IndexOptions().expireAfter(0L, TimeUnit.SECONDS))
        collection.createIndex(Document("updatedAt", -1))
    }

    override fun loadSession(playerId: String): PublicPlayerSessionState? {
        val document = collection.find(Filters.eq("_id", playerId)).first()
        return if (document == null) null else decodeSession(document)
    }

    override fun saveSession(session: PublicPlayerSessionState): PublicPlayerSessionState {
        val next = objectMapper.convertValue(session, PublicPlayerSessionState::class.java)
        next.version = session.version + 1
        PersistenceSupport.replaceVersioned(collection, session.id, session.version, encodeSession(next))
        return next
    }

    override fun listSessionsForTable(tableId: String): List<PublicPlayerSessionState> =
        collection.find(Filters.eq("tableId", tableId)).map { decodeSession(it) }.into(mutableListOf())

    override fun listActiveSessions(variantId: String): List<PublicPlayerSessionState> =
        collection.find(
            Filters.and(
                Filters.eq("variantId", variantId),
                Filters.or(Filters.exists("expiresAt", false), Filters.eq("expiresAt", null)),
            ),
        ).map { decodeSession(it) }.into(mutableListOf())

    private fun decodeSession(document: Document): PublicPlayerSessionState {
        val source = PersistenceSupport.normalizeDocument(document).toMutableMap()
        source["id"] = source.remove("_id") as Any
        return objectMapper.convertValue(source, PublicPlayerSessionState::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun encodeSession(state: PublicPlayerSessionState): Document {
        val payload = objectMapper.convertValue(state, Map::class.java) as MutableMap<String, Any?>
        payload["_id"] = state.id
        payload.remove("id")
        return PersistenceSupport.toDocument(payload as Map<String, Any?>)
    }
}
