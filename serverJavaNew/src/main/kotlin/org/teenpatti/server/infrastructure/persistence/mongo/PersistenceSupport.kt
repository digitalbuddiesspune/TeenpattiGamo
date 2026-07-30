package org.teenpatti.server.infrastructure.persistence.mongo

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.ReplaceOptions
import org.bson.Document
import java.time.Instant
import java.util.ConcurrentModificationException
import java.util.Date
import java.util.LinkedHashMap

internal object PersistenceSupport {
    private val TIMESTAMP_KEYS =
        listOf(
            "createdAt",
            "updatedAt",
            "expiresAt",
            "leaseExpiresAt",
            "closedAt",
            "leftAt",
            "joinedAt",
            "lastSeenAt",
            "settledAt",
            "dealingStartedAt",
            "dealingEndsAt",
            "turnStartedAt",
            "turnDeadlineAt",
            "resolvedAt",
            "countdownEndsAt",
            "startedAt",
            "stoppedAt",
            "timestamp",
        )

    @JvmStatic
    fun replaceVersioned(collection: MongoCollection<Document>, id: String, expectedVersion: Long, payload: Document) {
        val existing = collection.find(Filters.eq("_id", id)).projection(Document("_id", 1).append("version", 1)).first()
        if (existing == null) {
            check(expectedVersion == 0L) { "Aggregate $id is missing and cannot be updated from version $expectedVersion." }
            collection.insertOne(payload)
            return
        }
        val versionFilter: org.bson.conversions.Bson =
            if (expectedVersion == 0L) {
                Filters.or(Filters.exists("version", false), Filters.eq("version", 0L))
            } else {
                Filters.eq("version", expectedVersion)
            }
        val matched =
            collection.replaceOne(
                Filters.and(Filters.eq("_id", id), versionFilter),
                payload,
                ReplaceOptions().upsert(false),
            ).matchedCount
        if (matched == 0L) {
            throw ConcurrentModificationException("Aggregate $id was modified concurrently.")
        }
    }

    @JvmStatic
    fun toDate(value: String?): Date? = if (value == null) null else Date.from(Instant.parse(value))

    @JvmStatic
    fun toDocument(payload: Map<String, Any?>): Document = Document(castMap(convertValueForStorage(payload)))

    @JvmStatic
    fun normalizeDocument(document: Document): Map<String, Any?> = castMap(convertValueForModel(document))

    private fun convertValueForStorage(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                val converted = LinkedHashMap<String, Any?>()
                for ((rawKey, rawValue) in value) {
                    val key = rawKey.toString()
                    val next = convertValueForStorage(rawValue)
                    converted[key] =
                        if (TIMESTAMP_KEYS.contains(key) && next is String) {
                            toDate(next)
                        } else {
                            next
                        }
                }
                converted
            }

            is List<*> -> value.map { convertValueForStorage(it) }
            else -> value
        }

    private fun convertValueForModel(value: Any?): Any? =
        when (value) {
            is Date -> Instant.ofEpochMilli(value.time).toString()
            is Map<*, *> -> {
                val converted = LinkedHashMap<String, Any?>()
                for ((key, next) in value) {
                    converted[key.toString()] = convertValueForModel(next)
                }
                converted
            }

            is List<*> -> value.map { convertValueForModel(it) }
            else -> value
        }

    @Suppress("UNCHECKED_CAST")
    private fun castMap(value: Any?): Map<String, Any?> = value as Map<String, Any?>
}
