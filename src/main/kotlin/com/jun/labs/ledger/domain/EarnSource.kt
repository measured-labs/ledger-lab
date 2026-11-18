package com.jun.labs.ledger.domain

enum class EarnSource {
    /** 적립 요청 */
    EARN,
    /** 사용 취소 환원 — 원래 적립 건과 연결이 없어 새 적립으로 되돌린다(ADR-000 §3) */
    USE_CANCEL,
}
