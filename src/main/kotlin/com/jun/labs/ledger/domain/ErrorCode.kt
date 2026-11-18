package com.jun.labs.ledger.domain

enum class ErrorCode(val message: String) {
    INVALID_REQUEST("요청 값이 올바르지 않습니다."),
    EARN_NOT_FOUND("적립 건을 찾을 수 없습니다."),
    USE_NOT_FOUND("사용 건을 찾을 수 없습니다."),
    EARN_NOT_CANCELABLE("취소할 수 없는 상태의 적립 건입니다."),
    USE_NOT_CANCELABLE("취소할 수 없는 상태의 사용 건입니다."),
    CANCEL_AMOUNT_EXCEEDED("취소 금액이 남은 사용액을 초과합니다."),
    /** 지갑 없음 · 잔액 부족 · 사용 가능한 적립 건 합 부족 */
    INSUFFICIENT_BALANCE("잔액이 부족합니다."),
    /** 계약 밖 서버 오류 — 본문 모양만 {code, message}로 맞춘다 */
    INTERNAL_ERROR("서버 오류가 발생했습니다."),
}
