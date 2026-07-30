package org.teenpatti.server.common

internal class ApiErrorResponse(
    val status: String = "error",
    val code: String,
    val message: String,
)
