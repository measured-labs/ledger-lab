package com.jun.labs.ledger.impl

import com.jun.labs.ledger.domain.EarnSource
import com.jun.labs.ledger.domain.EarnStatus
import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.HistoryType
import com.jun.labs.ledger.domain.PointEarn
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.domain.PointHistory
import com.jun.labs.ledger.domain.PointRules
import com.jun.labs.ledger.domain.PointUse
import com.jun.labs.ledger.domain.PointWallet
import com.jun.labs.ledger.domain.UseStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class PointDomainTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")
    private val later = now.plus(Duration.ofDays(10))

    private fun invalid(block: () -> Unit) =
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows<PointException> { block() }.errorCode)

    // ── 팩토리 검사(상태를 만들기 전에) ──

    @Test
    fun `적립 팩토리는 userId · 금액 범위 · sourceRef 길이 · 미래 만료를 검사한다`() {
        invalid { PointEarn.earn(0, 100, "r", later, now) }
        invalid { PointEarn.earn(1, 0, "r", later, now) }
        invalid { PointEarn.earn(1, 100_000_001, "r", later, now) }
        invalid { PointEarn.earn(1, 100, " ", later, now) }
        invalid { PointEarn.earn(1, 100, "", later, now) }
        invalid { PointEarn.earn(1, 100, "x".repeat(65), later, now) }
        invalid { PointEarn.earn(1, 100, "r", now, now) }
        invalid { PointEarn.earn(1, 100, "r", now.minusSeconds(1), now) }
        invalid { PointEarn.earn(1, 100, "a\u0000b", later, now) }
        invalid { PointEarn.earn(1, 100, "\u0007", later, now) }
        invalid { PointEarn.earn(1, 100, "r", PointRules.EXPIRES_AT_MAX.plusSeconds(1), now) }
        assertEquals(PointRules.EXPIRES_AT_MAX, PointEarn.earn(1, 100, "r", PointRules.EXPIRES_AT_MAX, now).expiresAt)

        val e = PointEarn.earn(1, 100_000_000, "x".repeat(64), later, now)
        assertEquals(100_000_000, e.remaining)
        assertEquals(EarnStatus.ACTIVE, e.status)
        assertEquals(EarnSource.EARN, e.source)
    }

    @Test
    fun `사용 팩토리는 userId · 금액 범위 · orderRef 길이를 검사한다`() {
        invalid { PointUse.use(0, 1, "o", now) }
        invalid { PointUse.use(1, 0, "o", now) }
        invalid { PointUse.use(1, 100_000_001, "o", now) }
        invalid { PointUse.use(1, 1, "\t", now) }
        invalid { PointUse.use(1, 1, "o".repeat(65), now) }
        invalid { PointUse.use(1, 1, "o\u0000", now) }
        invalid { PointUse.use(1, 1, "o\uD800", now) }
        invalid { PointUse.use(1, 1, "\uDE00o", now) }
        assertEquals(UseStatus.USED, PointUse.use(1, 1, "o\uD83D\uDE00", now).status)
        assertEquals(UseStatus.USED, PointUse.use(1, 1, "o".repeat(64), now).status)
    }

    @Test
    fun `환원 적립은 USE_CANCEL · source_ref는 사용 건 id · 유효기간은 now 더하기 기간`() {
        val e = PointEarn.restore(1, 300, 42, Duration.ofDays(365), now)
        assertEquals(EarnSource.USE_CANCEL, e.source)
        assertEquals("42", e.sourceRef)
        assertEquals(now.plus(Duration.ofDays(365)), e.expiresAt)
        invalid { PointEarn.restore(1, 0, 42, Duration.ofDays(365), now) }
        val nearMax = PointRules.EXPIRES_AT_MAX.minus(Duration.ofDays(10))
        assertEquals(PointRules.EXPIRES_AT_MAX, PointEarn.restore(1, 300, 42, Duration.ofDays(365), nearMax).expiresAt)
    }

    // ── ① balance ≥ 0 ──

    @Test
    fun `사용은 잔액으로 거른다 - 음수가 되지 않는다`() {
        val w = PointWallet.open(1, now)
        w.deposit(100, now)
        assertEquals(ErrorCode.INSUFFICIENT_BALANCE, assertThrows<PointException> { w.withdraw(101, now) }.errorCode)
        assertEquals(100, w.balance)
        w.withdraw(100, now)
        assertEquals(0, w.balance)
        invalid { w.deposit(0, now) }
        invalid { w.deposit(100_000_001, now) }
    }

    @Test
    fun `적립 취소 · 만료 차감은 거르지 않는다 - 잔액이 이미 어긋났으면 음수로 남는다`() {
        val w = PointWallet.open(1, now)
        w.deposit(100, now)
        w.deduct(101, now)
        assertEquals(-1, w.balance)
        assertThrows<IllegalArgumentException> { w.deduct(-1, now) }
    }

    // ── ② 0 ≤ remaining ≤ amount ③ 전이 ──

    @Test
    fun `적립 건 차감은 remaining 범위 안에서만`() {
        val e = PointEarn.earn(1, 100, "r", later, now)
        assertThrows<IllegalArgumentException> { e.consume(101, now) }
        assertThrows<IllegalArgumentException> { e.consume(0, now) }
        e.consume(60, now)
        assertEquals(40, e.remaining)
        // 만료 시각이 지난 건은 사용 대상이 아니다
        assertThrows<IllegalStateException> { e.consume(1, later) }
        assertEquals(40, e.remaining)
        // 판정 순서: 금액 형식이 상태보다 먼저 · 상태가 남은 금액보다 먼저
        assertThrows<IllegalArgumentException> { e.consume(0, later) }
        assertThrows<IllegalStateException> { e.consume(41, later) }
        assertEquals(40, e.remaining)
    }

    @Test
    fun `적립 취소는 ACTIVE에서만 · 남은 만큼만 소멸 · remaining 0`() {
        val e = PointEarn.earn(1, 100, "r", later, now)
        e.consume(70, now)
        assertEquals(30, e.cancel(now))
        assertEquals(EarnStatus.CANCELED, e.status)
        assertEquals(0, e.remaining)
        assertEquals(ErrorCode.EARN_NOT_CANCELABLE, assertThrows<PointException> { e.cancel(now) }.errorCode)
        assertThrows<IllegalStateException> { e.expire(later) }
        assertEquals(EarnStatus.CANCELED, e.status)
    }

    @Test
    fun `만료는 ACTIVE이고 expiresAt 이하일 때만 · 이후 취소 불가`() {
        val e = PointEarn.earn(1, 100, "r", later, now)
        assertThrows<IllegalStateException> { e.expire(later.minusNanos(1000)) }
        assertEquals(EarnStatus.ACTIVE, e.status)
        assertEquals(100, e.expire(later))
        assertEquals(EarnStatus.EXPIRED, e.status)
        assertEquals(0, e.remaining)
        assertEquals(ErrorCode.EARN_NOT_CANCELABLE, assertThrows<PointException> { e.cancel(later) }.errorCode)
    }

    @Test
    fun `다 쓴 적립 건의 만료는 소멸액 0`() {
        val e = PointEarn.earn(1, 100, "r", later, now)
        e.consume(100, now)
        assertEquals(0, e.expire(later))
        assertEquals(EarnStatus.EXPIRED, e.status)
    }

    // ── ④ 0 ≤ canceledAmount ≤ amount ⑤ 상태 ⇔ 금액 ──

    @Test
    fun `사용 취소 판정 순서는 금액 형식 → 상태 → 남은 사용액`() {
        val u = PointUse.use(1, 1000, "o", now)
        invalid { u.cancel(0, now) }
        assertEquals(ErrorCode.CANCEL_AMOUNT_EXCEEDED, assertThrows<PointException> { u.cancel(1001, now) }.errorCode)
        assertEquals(0, u.canceledAmount)

        assertEquals(300, u.cancel(300, now))
        assertEquals(UseStatus.PARTIALLY_CANCELED, u.status)
        assertEquals(ErrorCode.CANCEL_AMOUNT_EXCEEDED, assertThrows<PointException> { u.cancel(701, now) }.errorCode)
        assertEquals(300, u.canceledAmount)

        assertEquals(700, u.cancel(null, now))
        assertEquals(UseStatus.CANCELED, u.status)
        assertEquals(1000, u.canceledAmount)

        // CANCELED면 금액이 넘쳐도 상태 오류가 먼저 · 형식 오류는 그보다 먼저
        assertEquals(ErrorCode.USE_NOT_CANCELABLE, assertThrows<PointException> { u.cancel(5000, now) }.errorCode)
        assertEquals(ErrorCode.USE_NOT_CANCELABLE, assertThrows<PointException> { u.cancel(null, now) }.errorCode)
        invalid { u.cancel(0, now) }
    }

    @Test
    fun `이력은 지갑의 변화 뒤 잔액을 담고 amount는 0 이상`() {
        val w = PointWallet.open(7, now)
        w.deposit(500, now)
        val h = PointHistory.record(w, HistoryType.EARN, 500, 1, now)
        assertEquals(500, h.balanceAfter)
        assertEquals(7, h.userId)
        assertEquals(0, PointHistory.record(w, HistoryType.EXPIRE, 0, 1, now).amount)
        assertThrows<IllegalArgumentException> { PointHistory.record(w, HistoryType.EXPIRE, -1, 1, now) }
    }

    @Test
    fun `남은 금액이 0인 ACTIVE 건 차감은 금액 오류 - 상태 오류가 아니다`() {
        val e = PointEarn.earn(1, 100, "r", later, now)
        e.consume(100, now)
        assertThrows<IllegalArgumentException> { e.consume(1, now) }
        assertEquals(0, e.remaining)
    }
}
