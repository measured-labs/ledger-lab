package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * 만료 배치 — ADR-000 §3 만료 절차 · §4 batch-size · test-contract §2(ExpirePointsUseCase.expire(): Int).
 * 시험 설정 batch-size = 3. 배치는 사용자 구분 없이 전역으로 돈다 — 다른 테스트가 남긴 행이 같은 실행에 끼어도
 * 판단이 흔들리지 않게, 실행 전 DB에서 기대 대상을 고르고(expireOnceChecked) 내 사용자 행은 따로 확인한다.
 */
class ExpiryBlindTest : BlindTestSupport() {

    @Test
    fun `expiresAt 이 지금 이하인 ACTIVE 건만 EXPIRED - remaining 0, 잔액 감소, 이력 EXPIRE, 반환 값 = 건 수`() {
        drainExpiry()
        val userId = newUserId()
        val due = earnOk(userId, 300L, Duration.ofSeconds(10))["earnId"].asLong()
        val notYet = earnOk(userId, 200L, Duration.ofSeconds(11))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(10)) // due 는 정확히 지금 = 만료

        assertThat(expirableIds(Int.MAX_VALUE)).contains(due).doesNotContain(notYet)
        expireUntilDone(listOf(due))
        drainExpiry()

        assertThat(earnRow(due).str("status")).isEqualTo("EXPIRED")
        assertThat(earnRow(due).long("remaining")).isEqualTo(0L)
        assertThat(earnRow(notYet).str("status")).isEqualTo("ACTIVE")
        assertThat(earnRow(notYet).long("remaining")).isEqualTo(200L)
        assertThat(balanceOf(userId)).isEqualTo(200L)

        val rows = historyRows(userId)
        assertThat(rows).hasSize(3)
        val last = rows.last()
        assertThat(last.str("type")).isEqualTo("EXPIRE")
        assertThat(last.long("amount")).isEqualTo(300L)
        assertThat(last.long("ref_id")).isEqualTo(due)
        assertThat(last.long("balance_after")).isEqualTo(200L)
        assertInvariant(userId)
    }

    @Test
    fun `만료 대상이 없으면 0 을 반환하고 아무것도 바꾸지 않는다`() {
        drainExpiry()
        val userId = newUserId()
        earnOk(userId, 100L, Duration.ofDays(1))
        val before = snapshot(userId)

        assertThat(expire()).isEqualTo(0)
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `일부 쓴 적립 만료 - 남은 만큼만 잔액에서 뺀다`() {
        drainExpiry()
        val userId = newUserId()
        val earnId = earnOk(userId, 1_000L, Duration.ofSeconds(5))["earnId"].asLong()
        earnOk(userId, 100L, Duration.ofDays(30))
        useOk(userId, 400L)
        clock.advance(Duration.ofSeconds(5))

        expireUntilDone(listOf(earnId))

        assertThat(earnRow(earnId).long("remaining")).isEqualTo(0L)
        assertThat(balanceOf(userId)).isEqualTo(100L)
        val last = historyRows(userId).last()
        assertThat(last.str("type")).isEqualTo("EXPIRE")
        assertThat(last.long("amount")).isEqualTo(600L)
        assertThat(last.long("balance_after")).isEqualTo(100L)
        assertInvariant(userId)
    }

    @Test
    fun `다 쓴 적립 만료 - EXPIRED, 잔액 그대로, 이력 EXPIRE amount 0`() {
        drainExpiry()
        val userId = newUserId()
        val spent = earnOk(userId, 500L, Duration.ofSeconds(5))["earnId"].asLong()
        earnOk(userId, 50L, Duration.ofDays(30))
        useOk(userId, 500L)
        val historyBefore = historyRows(userId).size
        clock.advance(Duration.ofSeconds(5))

        expireUntilDone(listOf(spent))

        assertThat(earnRow(spent).str("status")).isEqualTo("EXPIRED")
        assertThat(balanceOf(userId)).isEqualTo(50L)
        val rows = historyRows(userId)
        assertThat(rows).hasSize(historyBefore + 1)
        assertThat(rows.last().str("type")).isEqualTo("EXPIRE")
        assertThat(rows.last().long("amount")).isEqualTo(0L)
        assertThat(rows.last().long("ref_id")).isEqualTo(spent)
        assertThat(rows.last().long("balance_after")).isEqualTo(50L)
        assertInvariant(userId)
    }

    @Test
    fun `한 번에 batch-size 건까지만 expiresAt 순(같으면 id 순)으로 처리하고 나머지는 다음 실행`() {
        drainExpiry()
        val userId = newUserId()
        val e4 = earnOk(userId, 40L, Duration.ofSeconds(4))["earnId"].asLong()
        val e1 = earnOk(userId, 10L, Duration.ofSeconds(1))["earnId"].asLong()
        val e3a = earnOk(userId, 30L, Duration.ofSeconds(3))["earnId"].asLong()
        val e3b = earnOk(userId, 31L, Duration.ofSeconds(3))["earnId"].asLong()
        val e2 = earnOk(userId, 20L, Duration.ofSeconds(2))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(5))
        assertThat(balanceOf(userId)).isEqualTo(131L)
        val mine = listOf(e1, e2, e3a, e3b, e4)

        val runs = expireUntilDone(mine)

        assertThat(runs.first()).describedAs("만료 대상이 5건 이상이면 첫 실행은 상한만큼").hasSize(BLIND_BATCH_SIZE)
        assertThat(runs).allSatisfy { assertThat(it.size).isLessThanOrEqualTo(BLIND_BATCH_SIZE) }
        assertThat(runs.size).isGreaterThanOrEqualTo(2)
        // 내 건들이 처리된 순서 = expiresAt 순, 같으면 id 순
        assertThat(runs.flatten().filter { it in mine }).containsExactly(e1, e2, e3a, e3b, e4)
        assertThat(balanceOf(userId)).isEqualTo(0L)

        val expires = historyRows(userId).filter { it.str("type") == "EXPIRE" }
        assertThat(expires.map { it.long("ref_id") }).describedAs("이력도 expiresAt 순").containsExactly(e1, e2, e3a, e3b, e4)
        assertThat(expires.map { it.long("amount") }).containsExactly(10L, 20L, 30L, 31L, 40L)
        assertThat(expires.map { it.long("balance_after") }).containsExactly(121L, 101L, 71L, 40L, 0L)
        assertInvariant(userId)
    }

    @Test
    fun `배치가 상한에서 멈추면 남은 만료 건은 다음 실행 전까지 ACTIVE 로 남는다`() {
        drainExpiry()
        val userId = newUserId()
        val ids = (1..BLIND_BATCH_SIZE + 1).map { earnOk(userId, 10L, Duration.ofSeconds(1))["earnId"].asLong() }
        clock.advance(Duration.ofSeconds(2))

        val first = expireOnceChecked()

        assertThat(first).hasSize(BLIND_BATCH_SIZE)
        val stillActive = ids.filter { earnRow(it).str("status") == "ACTIVE" }
        assertThat(stillActive).describedAs("상한을 넘는 만료 건은 남는다").isNotEmpty()
        assertInvariant(userId)
        expireUntilDone(ids)
        assertThat(balanceOf(userId)).isEqualTo(0L)
    }

    @Test
    fun `CANCELED 적립은 만료 시각이 지나도 배치 대상이 아니다`() {
        drainExpiry()
        val userId = newUserId()
        val canceled = earnOk(userId, 100L, Duration.ofSeconds(1))["earnId"].asLong()
        assertThat(cancelEarn(canceled).status).isEqualTo(200)
        val before = snapshot(userId)
        clock.advance(Duration.ofSeconds(2))

        assertThat(expirableIds(Int.MAX_VALUE)).doesNotContain(canceled)
        drainExpiry()
        assertThat(earnRow(canceled).str("status")).isEqualTo("CANCELED")
        assertThat(snapshot(userId)).isEqualTo(before)
    }

    @Test
    fun `이미 EXPIRED 인 건은 다시 처리하지 않는다`() {
        drainExpiry()
        val userId = newUserId()
        val earnId = earnOk(userId, 100L, Duration.ofSeconds(1))["earnId"].asLong()
        clock.advance(Duration.ofSeconds(2))
        expireUntilDone(listOf(earnId))
        drainExpiry()
        val before = snapshot(userId)

        assertThat(expire()).isEqualTo(0)
        assertThat(snapshot(userId)).isEqualTo(before)
    }
}
