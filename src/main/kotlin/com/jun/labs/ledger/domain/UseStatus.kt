package com.jun.labs.ledger.domain

/** USED → PARTIALLY_CANCELED → CANCELED · USED → CANCELED(ADR-000 §3) */
enum class UseStatus(val cancelable: Boolean) {
    USED(true),
    PARTIALLY_CANCELED(true),
    CANCELED(false),
}
