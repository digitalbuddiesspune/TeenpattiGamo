package org.teenpatti.server.config

import org.teenpatti.server.common.ApiErrorResponse
import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.common.GameEventLog
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
internal class ApiExceptionHandler {
    @ExceptionHandler(Exception::class)
    fun handle(error: Exception): ResponseEntity<ApiErrorResponse> {
        GameEventLog.error("http_request_failed", error, "code" to ApiSupport.errorCode(error))
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = ApiSupport.errorCode(error),
                message = error.message ?: "Unexpected error.",
            ),
        )
    }
}
