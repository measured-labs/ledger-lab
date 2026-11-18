package com.jun.labs.ledger.api

import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.service.CancelUseCommand
import com.jun.labs.ledger.service.EarnPointsCommand
import com.jun.labs.ledger.service.GetPointsUseCase
import com.jun.labs.ledger.service.UsePointsCommand
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * 필드는 모두 nullable — 빠진 값도 INVALID_REQUEST(400)로 직접 판정한다(ADR-000 §6.1).
 * 형식 · 범위는 여기서, "지금보다 뒤"는 Clock을 가진 도메인 팩토리가 본다(둘 다 400 · 존재 판정보다 앞).
 */
data class EarnPointsRequest(
    val userId: Long? = null,
    val amount: Long? = null,
    val expiresAt: String? = null,
    val sourceRef: String? = null,
) {
    fun toCommand(): EarnPointsCommand {
        val userId = userId ?: throw invalid("userId는 1 이상의 정수")
        val amount = amount ?: throw invalid("amount는 정수")
        val sourceRef = sourceRef ?: throw invalid("sourceRef는 공백 아님 · 1~${PointRules.REF_MAX}자")
        PointRules.checkUserId(userId)
        PointRules.checkAmount(amount)
        val expiresAt = parseInstant(expiresAt)
        PointRules.checkRef(sourceRef, "sourceRef")
        return EarnPointsCommand(userId, amount, expiresAt, sourceRef)
    }
}

data class UsePointsRequest(
    val userId: Long? = null,
    val amount: Long? = null,
    val orderRef: String? = null,
) {
    fun toCommand(): UsePointsCommand {
        val userId = userId ?: throw invalid("userId는 1 이상의 정수")
        val amount = amount ?: throw invalid("amount는 정수")
        val orderRef = orderRef ?: throw invalid("orderRef는 공백 아님 · 1~${PointRules.REF_MAX}자")
        PointRules.checkUserId(userId)
        PointRules.checkAmount(amount)
        PointRules.checkRef(orderRef, "orderRef")
        return UsePointsCommand(userId, amount, orderRef)
    }
}

/** cancelAmount 생략 또는 null = 남은 사용액 전부 */
data class CancelUseRequest(
    val cancelAmount: Long? = null,
) {
    fun toCommand(useId: Long): CancelUseCommand {
        cancelAmount?.let { PointRules.checkAmount(it, "cancelAmount") }
        return CancelUseCommand(useId, cancelAmount)
    }
}

/** ISO-8601 시각 — UTC `Z` 또는 오프셋 필수(오프셋 없는 지역 시각은 400) */
private fun parseInstant(raw: String?): Instant {
    if (raw == null) throw invalid("expiresAt은 ISO-8601 시각")
    val instant = try {
        OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeParseException) {
        throw invalid("expiresAt은 ISO-8601 시각(오프셋 포함)")
    }
    PointRules.checkExpiresAtMax(PointRules.normalize(instant))
    return instant
}

/** 경로 id · userId — 양의 정수 · Long 범위 */
fun parsePathId(raw: String, name: String): Long =
    raw.takeIf { DIGITS.matches(it) }?.toLongOrNull()?.takeIf { it > 0 } ?: throw invalid("${name}는 양의 정수")

/** limit — 생략 = 기본 50 · 정수 1~100 */
fun parseHistoryLimit(raw: String?): Int {
    if (raw == null) return GetPointsUseCase.HISTORY_LIMIT_DEFAULT
    return raw.takeIf { DIGITS.matches(it) }?.toIntOrNull()
        ?.takeIf { it in GetPointsUseCase.HISTORY_LIMIT_MIN..GetPointsUseCase.HISTORY_LIMIT_MAX }
        ?: throw invalid("limit은 ${GetPointsUseCase.HISTORY_LIMIT_MIN}~${GetPointsUseCase.HISTORY_LIMIT_MAX}의 정수")
}

/** 숫자만 — toLongOrNull은 앞의 '+' · '-'를 받아 준다 */
private val DIGITS = Regex("[0-9]+")

private fun invalid(detail: String) = PointException(ErrorCode.INVALID_REQUEST, detail)
