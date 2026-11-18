package com.jun.labs.ledger.domain

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 입력 규칙(ADR-000 §6.1) — API와 도메인이 같은 함수를 쓴다.
 * 도메인 팩토리 · 메서드는 상태를 바꾸기 전에 이 검사를 부른다(API 밖 진입점도 같은 규칙).
 */
object PointRules {
    const val AMOUNT_MIN = 1L
    const val AMOUNT_MAX = 100_000_000L
    const val REF_MAX = 64

    /** 만료 시각 상한 — 4자리 연도 안(JSON · DB 왕복이 안전한 범위) */
    val EXPIRES_AT_MAX: Instant = Instant.parse("9999-12-31T23:59:59Z")

    fun checkUserId(userId: Long) {
        if (userId < 1) throw invalid("userId는 1 이상의 정수")
    }

    fun checkAmount(amount: Long, field: String = "amount") {
        if (amount !in AMOUNT_MIN..AMOUNT_MAX) throw invalid("${field}는 $AMOUNT_MIN~${AMOUNT_MAX}의 정수")
    }

    /** 공백 아님 · 1~64자(문자 수 = 코드 포인트 — VARCHAR(64)와 같은 셈) · 제어 문자(NUL 포함) · 짝 없는 서로게이트(UTF-8로 저장 불가) 없음 */
    fun checkRef(ref: String, field: String) {
        if (ref.isBlank() || ref.codePointCount(0, ref.length) > REF_MAX) throw invalid("${field}는 공백 아님 · 1~${REF_MAX}자")
        if (ref.codePoints().anyMatch(Character::isISOControl)) throw invalid("${field}에 제어 문자를 쓸 수 없습니다")
        // codePoints()는 짝이 맞는 서로게이트를 하나로 합치므로, 남은 서로게이트 코드 포인트는 짝이 없는 것이다
        if (ref.codePoints().anyMatch { it in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code }) throw invalid("${field}에 짝 없는 서로게이트를 쓸 수 없습니다")
    }

    /** 상한만 — 시각이 필요 없는 API 형식 검사에서도 쓴다 */
    fun checkExpiresAtMax(expiresAt: Instant) {
        if (expiresAt.isAfter(EXPIRES_AT_MAX)) throw invalid("expiresAt은 $EXPIRES_AT_MAX 이하")
    }

    /** now < expiresAt ≤ 상한 */
    fun checkExpiresAt(expiresAt: Instant, now: Instant) {
        checkExpiresAtMax(expiresAt)
        if (!expiresAt.isAfter(now)) throw invalid("expiresAt은 지금보다 뒤")
    }

    /** DB(TIMESTAMPTZ)가 마이크로초까지 저장하므로 메모리 값도 같은 정밀도로 맞춘다 — 응답과 재조회가 같게 */
    fun normalize(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MICROS)

    private fun invalid(detail: String) = PointException(ErrorCode.INVALID_REQUEST, detail)
}
