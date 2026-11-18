package com.jun.labs.ledger.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration

/** 조회 — ADR-000 §6 잔액 · 이력(최신순 · limit 1~100 기본 50) · 단건 조회 · §5 같은 user_id 지갑 둘이면 가장 작은 id. */
class QueryBlindTest : BlindTestSupport() {

    @Test
    fun `잔액 - 200 {userId, balance}`() {
        val userId = newUserId()
        earnOk(userId, 700L)
        useOk(userId, 200L)

        val res = balance(userId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["userId"].asLong()).isEqualTo(userId)
        assertThat(res.json["balance"].asLong()).isEqualTo(500L)
    }

    @Test
    fun `지갑 없는 사용자 잔액 - 200, balance 0, 지갑을 만들지 않는다`() {
        val userId = newUserId()

        val res = balance(userId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["userId"].asLong()).isEqualTo(userId)
        assertThat(res.json["balance"].asLong()).isEqualTo(0L)
        assertThat(walletRows(userId)).isEmpty()
    }

    @Test
    fun `이력 - 최신순(id 내림차순), 응답 필드`() {
        val userId = newUserId()
        val earnId = earnOk(userId, 1_000L)["earnId"].asLong()
        val useId = useOk(userId, 300L)["useId"].asLong()
        cancelEarn(earnId)

        val res = history(userId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        val items = res.json.toList()
        assertThat(items).hasSize(3)
        assertThat(items.map { it.textOrNull("type") }).containsExactly("EARN_CANCEL", "USE", "EARN")
        val ids = items.map { it["historyId"].asLong() }
        assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder())
        assertThat(ids).containsExactlyElementsOf(historyRows(userId).map { it.long("id") }.reversed())

        val use = items[1]
        assertThat(use["amount"].asLong()).isEqualTo(300L)
        assertThat(use["refId"].asLong()).isEqualTo(useId)
        assertThat(use["balanceAfter"].asLong()).isEqualTo(700L)
        assertThat(use.instant("createdAt")).isEqualTo(now())
        assertThat(items[0]["amount"].asLong()).isEqualTo(700L)
        assertThat(items[0]["refId"].asLong()).isEqualTo(earnId)
        assertThat(items[0]["balanceAfter"].asLong()).isEqualTo(0L)
        assertThat(items[2]["balanceAfter"].asLong()).isEqualTo(1_000L)
    }

    @Test
    fun `이력 없는 사용자 - 200 빈 배열`() {
        val res = history(newUserId())
        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.isArray).isTrue()
        assertThat(res.json.size()).isEqualTo(0)
    }

    @Test
    fun `이력 limit - 기본 50, 1, 100, 최신 것부터`() {
        val userId = newUserId()
        repeat(120) { i ->
            jdbc.update(
                "insert into point_history(user_id, type, amount, ref_id, balance_after, created_at) values (?, 'EARN', ?, ?, ?, ?)",
                userId, (i + 1).toLong(), (i + 1).toLong(), (i + 1).toLong(), Timestamp.from(now()),
            )
        }
        val allDesc = historyRows(userId).map { it.long("id") }.reversed()

        val def = history(userId)
        assertThat(def.status).describedAs(def.body).isEqualTo(200)
        assertThat(def.json.map { it["historyId"].asLong() }).containsExactlyElementsOf(allDesc.take(50))

        val one = history(userId, "1")
        assertThat(one.status).isEqualTo(200)
        assertThat(one.json.map { it["historyId"].asLong() }).containsExactly(allDesc.first())
        assertThat(one.json[0]["amount"].asLong()).isEqualTo(120L)

        val hundred = history(userId, "100")
        assertThat(hundred.status).isEqualTo(200)
        assertThat(hundred.json.map { it["historyId"].asLong() }).containsExactlyElementsOf(allDesc.take(100))
    }

    @Test
    fun `적립 건 단건 조회 - 200 적립 건 응답, 상태 반영`() {
        val userId = newUserId()
        val expiresAt = now().plus(Duration.ofDays(2))
        val earnId = earn(userId, 500L, iso(expiresAt), "g-1").json["earnId"].asLong()
        useOk(userId, 120L)

        val res = getEarn(earnId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["earnId"].asLong()).isEqualTo(earnId)
        assertThat(res.json["userId"].asLong()).isEqualTo(userId)
        assertThat(res.json["amount"].asLong()).isEqualTo(500L)
        assertThat(res.json["remaining"].asLong()).isEqualTo(380L)
        assertThat(res.json.textOrNull("source")).isEqualTo("EARN")
        assertThat(res.json.textOrNull("sourceRef")).isEqualTo("g-1")
        assertThat(res.json.textOrNull("status")).isEqualTo("ACTIVE")
        assertThat(res.json.instant("expiresAt")).isEqualTo(expiresAt)
        assertThat(res.json.instant("createdAt")).isEqualTo(now())
    }

    @Test
    fun `사용 건 단건 조회 - 200 사용 건 응답, 취소 반영`() {
        val userId = newUserId()
        earnOk(userId, 500L)
        val useId = useOk(userId, 300L, "o-9")["useId"].asLong()
        cancelUse(useId, json("cancelAmount" to 100L))

        val res = getUse(useId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json["useId"].asLong()).isEqualTo(useId)
        assertThat(res.json["userId"].asLong()).isEqualTo(userId)
        assertThat(res.json["amount"].asLong()).isEqualTo(300L)
        assertThat(res.json["canceledAmount"].asLong()).isEqualTo(100L)
        assertThat(res.json.textOrNull("orderRef")).isEqualTo("o-9")
        assertThat(res.json.textOrNull("status")).isEqualTo("PARTIALLY_CANCELED")
        assertThat(res.json.instant("usedAt")).isEqualTo(now())
    }

    @Test
    fun `없는 적립 건 · 사용 건 조회 - 404 EARN_NOT_FOUND · USE_NOT_FOUND`() {
        assertError(getEarn(Long.MAX_VALUE), 404, "EARN_NOT_FOUND")
        assertError(getUse(Long.MAX_VALUE), 404, "USE_NOT_FOUND")
    }

    @Test
    fun `같은 user_id 지갑이 둘이면 가장 작은 id 지갑을 읽고 갱신한다`() {
        val userId = newUserId()
        val small = insertWallet(userId, 0L)
        val big = insertWallet(userId, 9_999L)
        assertThat(small).isLessThan(big)

        assertThat(balanceOf(userId)).isEqualTo(0L)

        earnOk(userId, 300L)
        assertThat(walletRows(userId)).hasSize(2)
        assertThat(jdbc.queryForObject("select balance from point_wallet where id = ?", Long::class.java, small)).isEqualTo(300L)
        assertThat(jdbc.queryForObject("select balance from point_wallet where id = ?", Long::class.java, big)).isEqualTo(9_999L)
        assertThat(balanceOf(userId)).isEqualTo(300L)

        useOk(userId, 100L)
        assertThat(jdbc.queryForObject("select balance from point_wallet where id = ?", Long::class.java, small)).isEqualTo(200L)
        assertThat(jdbc.queryForObject("select balance from point_wallet where id = ?", Long::class.java, big)).isEqualTo(9_999L)
        assertThat(historyRows(userId).last().long("balance_after")).isEqualTo(200L)
        assertThat(balanceOf(userId)).isEqualTo(200L)
    }
}
