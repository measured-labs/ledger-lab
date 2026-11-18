package com.jun.labs.ledger.domain

/** ACTIVE → CANCELED · ACTIVE → EXPIRED만(ADR-000 §3) */
enum class EarnStatus {
    ACTIVE,
    CANCELED,
    EXPIRED,
}
