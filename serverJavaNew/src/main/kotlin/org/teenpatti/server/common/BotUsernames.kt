package org.teenpatti.server.common

internal object BotUsernames {
    private val names: List<String> by lazy {
        val stream =
            BotUsernames::class.java.getResourceAsStream("/bot-usernames.txt")
                ?: return@lazy emptyList()
        stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
    }

    fun isPlaceholder(name: String?): Boolean {
        val normalized = name?.trim().orEmpty()
        if (normalized.isEmpty()) {
            return true
        }
        if (normalized.equals("Bot", ignoreCase = true) ||
            normalized.startsWith("Bot ", ignoreCase = true) ||
            normalized.endsWith(" Bot", ignoreCase = true)
        ) {
            return true
        }
        if (normalized.equals("Guest Player", ignoreCase = true)) {
            return true
        }
        return normalized.startsWith("Guest_", ignoreCase = true) ||
            normalized.matches(Regex("^Guest\\s*\\d+$", RegexOption.IGNORE_CASE))
    }

    fun pick(
        random: RandomSource,
        exclude: Collection<String> = emptyList(),
    ): String {
        val excluded = exclude.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        val pool = names.filter { it !in excluded }.ifEmpty { names }
        if (pool.isEmpty()) {
            return "Player_${100000 + random.nextInt(900000)}"
        }
        return pool[random.nextInt(pool.size)]
    }

    fun resolve(
        current: String?,
        random: RandomSource,
        exclude: Collection<String> = emptyList(),
    ): String {
        val normalized = current?.trim().orEmpty()
        if (normalized.isNotEmpty() && !isPlaceholder(normalized)) {
            return normalized.removePrefix("Bot ").removeSuffix(" Bot").trim().ifBlank {
                pick(random, exclude)
            }
        }
        return pick(random, exclude)
    }
}
