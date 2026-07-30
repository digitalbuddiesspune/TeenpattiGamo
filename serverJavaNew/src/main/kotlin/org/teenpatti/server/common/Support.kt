package org.teenpatti.server.common

import org.teenpatti.server.game.ProvablyFairPlayerSeedInput
import org.teenpatti.server.game.ProvablyFairState
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun interface ScheduledTask {
    fun cancel()
}

internal fun interface Scheduler {
    fun schedule(delayMs: Long, task: Runnable): ScheduledTask
}

internal class AppException : IllegalStateException {
    @JvmField
    val code: String

    constructor(code: String, message: String) : super(message) {
        this.code = code
    }

    constructor(code: String, message: String, cause: Throwable) : super(message, cause) {
        this.code = code
    }

    companion object {
        @JvmStatic
        fun badRequest(code: String, message: String): AppException = AppException(code, message)
    }
}

internal interface ClockProvider {
    fun now(): Instant

    fun nowIso(): String = DateTimeFormatter.ISO_INSTANT.format(now())

    fun isoFromMillis(epochMillis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))
}

internal class AppClock(
    private val clock: Clock = Clock.systemUTC(),
) : ClockProvider {
    override fun now(): Instant = Instant.now(clock)
}

internal fun interface IdGenerator {
    fun newId(): String
}

internal class AppIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

internal interface RandomSource {
    fun nextDouble(): Double

    fun nextInt(bound: Int): Int
}

internal class AppRandomSource : RandomSource {
    private val secureRandom = SecureRandom()

    override fun nextDouble(): Double = secureRandom.nextDouble()

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive." }
        return secureRandom.nextInt(bound)
    }
}

internal object SchedulerFactory {
    @JvmStatic
    fun fromExecutor(executor: ScheduledExecutorService): Scheduler =
        Scheduler { delayMs, task ->
            val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            ScheduledTask { future.cancel(false) }
        }
}

internal object TokenSupport {
    private val secureRandom = SecureRandom()

    @JvmStatic
    fun hashToken(token: String): String = sha256Hex(token)

    @JvmStatic
    fun sha256Hex(value: String): String =
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val builder = StringBuilder(digest.size * 2)
            for (item in digest) {
                builder.append(String.format("%02x", item))
            }
            builder.toString()
        } catch (error: Exception) {
            throw AppException("token_hash_failed", "Unable to hash token.", error)
        }

    @JvmStatic
    fun hmacSha256(key: ByteArray, value: String): ByteArray =
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.doFinal(value.toByteArray(Charsets.UTF_8))
        } catch (error: Exception) {
            throw AppException("hmac_sha256_failed", "Unable to compute HMAC.", error)
        }

    @JvmStatic
    fun newSecureRandomHex(bytes: Int): String {
        require(bytes > 0) { "bytes must be positive." }
        val value = ByteArray(bytes)
        secureRandom.nextBytes(value)
        return HexFormat.of().formatHex(value)
    }

    @JvmStatic
    fun requireClientSeed(rawSeed: String?): String {
        val normalized = rawSeed?.trim().orEmpty()
        if (normalized.isBlank()) {
            throw AppException.badRequest("client_seed_required", "Client seed is required.")
        }
        if (normalized.length > 128) {
            throw AppException.badRequest("client_seed_invalid", "Client seed must be at most 128 characters.")
        }
        return normalized
    }

    @JvmStatic
    fun copyProvablyFairState(source: ProvablyFairState?, revealServerSeed: Boolean): ProvablyFairState? {
        if (source == null) {
            return null
        }
        val copy = ProvablyFairState()
        copy.version = source.version
        copy.algorithm = source.algorithm
        copy.roundId = source.roundId
        copy.serverSeedHash = source.serverSeedHash
        copy.serverSeed = if (revealServerSeed) source.serverSeed else null
        copy.deckHash = source.deckHash
        copy.openingPlayerIndex = source.openingPlayerIndex
        for (item in source.playerSeedInputs) {
            val next = ProvablyFairPlayerSeedInput()
            next.playerId = item.playerId
            next.clientSeed = item.clientSeed
            copy.playerSeedInputs.add(next)
        }
        return copy
    }

    @JvmStatic
    fun formatIndianNumber(value: Int): String = NumberFormat.getNumberInstance(Locale.of("en", "IN")).format(value)
}
