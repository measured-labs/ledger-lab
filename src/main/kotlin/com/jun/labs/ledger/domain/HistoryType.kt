package com.jun.labs.ledger.domain

/** 변화의 방향 — 이력 amount는 크기(0 이상)만 담는다 */
enum class HistoryType {
    EARN,
    USE,
    USE_CANCEL,
    EARN_CANCEL,
    EXPIRE,
}
