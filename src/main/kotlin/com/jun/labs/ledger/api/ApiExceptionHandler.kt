package com.jun.labs.ledger.api

import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.PointException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.ErrorResponse as SpringErrorResponse

data class ErrorResponse(val code: String, val message: String)

/** 오류 본문은 언제나 {code, message}(ADR-000 §6) · 5xx는 고정 문구(원인은 서버 로그) */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PointException::class)
    fun handle(e: PointException): ResponseEntity<ErrorResponse> {
        val status = e.errorCode.httpStatus()
        if (status.is5xxServerError) {
            log.error("{} — {}", e.errorCode, e.message, e)
            return ResponseEntity.status(status).body(ErrorResponse(e.errorCode.name, e.errorCode.message))
        }
        return ResponseEntity.status(status).body(ErrorResponse(e.errorCode.name, e.message ?: e.errorCode.message))
    }

    /** JSON으로 읽을 수 없는 본문 · 정수 자리의 1.5 · "100" · true · Long 범위 밖 */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<ErrorResponse> =
        handle(PointException(ErrorCode.INVALID_REQUEST, "요청 본문을 JSON으로 읽을 수 없습니다."))

    /**
     * 그 밖의 오류 — 본문 모양만 맞춘다. Spring MVC 표준 오류(없는 경로 404 · 405 · 415 등 — 헤더(Allow 등)도 그대로)는
     * 그 상태 그대로 code `HTTP_<상태>`, 나머지는 500 INTERNAL_ERROR · 원인은 서버 로그에만.
     * 406(Accept가 JSON을 받지 않음)은 JSON 본문을 쓸 수 없어 본문을 보장하지 않는다.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is SpringErrorResponse) {
            val status = e.statusCode
            val message = if (status.is5xxServerError) {
                log.error("Spring MVC 서버 오류 {}", status.value(), e)
                ErrorCode.INTERNAL_ERROR.message
            } else {
                HttpStatus.resolve(status.value())?.reasonPhrase ?: "오류"
            }
            return ResponseEntity.status(status).headers(e.headers).body(ErrorResponse("HTTP_${status.value()}", message))
        }
        log.error("처리하지 못한 예외", e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(ErrorCode.INTERNAL_ERROR.name, ErrorCode.INTERNAL_ERROR.message))
    }
}

fun ErrorCode.httpStatus(): HttpStatus = when (this) {
    ErrorCode.INVALID_REQUEST -> HttpStatus.BAD_REQUEST
    ErrorCode.EARN_NOT_FOUND,
    ErrorCode.USE_NOT_FOUND,
    -> HttpStatus.NOT_FOUND
    ErrorCode.EARN_NOT_CANCELABLE,
    ErrorCode.USE_NOT_CANCELABLE,
    ErrorCode.CANCEL_AMOUNT_EXCEEDED,
    ErrorCode.INSUFFICIENT_BALANCE,
    -> HttpStatus.CONFLICT
    ErrorCode.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
}
