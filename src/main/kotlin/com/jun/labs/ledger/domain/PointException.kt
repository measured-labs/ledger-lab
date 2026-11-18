package com.jun.labs.ledger.domain

class PointException(
    val errorCode: ErrorCode,
    detail: String? = null,
) : RuntimeException(detail ?: errorCode.message)
