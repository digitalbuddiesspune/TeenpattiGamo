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

    fun pick(
        random: RandomSource,
        exclude: Collection<String> = emptyList(),
    ): String {
        val excluded = exclude.toHashSet()
        val pool = names.filter { it !in excluded }.ifEmpty { names }
        if (pool.isEmpty()) {
            return "Guest_${100000 + random.nextInt(900000)}"
        }
        return pool[random.nextInt(pool.size)]
    }
}
