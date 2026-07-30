package org.teenpatti.server.common

import org.slf4j.LoggerFactory

internal object GameEventLog {
    private val logger = LoggerFactory.getLogger("TeenPattiGameEvents")

    fun info(event: String, vararg fields: Pair<String, Any?>) {
        logger.info(format(event, fields))
    }

    fun error(event: String, error: Throwable, vararg fields: Pair<String, Any?>) {
        logger.error(format(event, fields), error)
    }

    private fun format(event: String, fields: Array<out Pair<String, Any?>>): String =
        buildString {
            append("game=\"Teen Patti\" event=")
            append(safe(event))
            fields.forEach { (key, value) ->
                append(' ')
                append(key.replace(Regex("[^A-Za-z0-9_.-]"), "_"))
                append('=')
                append(safe(value))
            }
        }

    private fun safe(value: Any?): String =
        "\"${(value ?: "").toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")}\""
}
