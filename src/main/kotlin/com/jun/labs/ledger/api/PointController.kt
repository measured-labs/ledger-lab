package com.jun.labs.ledger.api

import com.jun.labs.ledger.domain.ErrorCode
import com.jun.labs.ledger.domain.PointException
import com.jun.labs.ledger.service.BalanceResult
import com.jun.labs.ledger.service.CancelEarnUseCase
import com.jun.labs.ledger.service.CancelUseUseCase
import com.jun.labs.ledger.service.EarnPointsUseCase
import com.jun.labs.ledger.service.EarnResult
import com.jun.labs.ledger.service.GetPointsUseCase
import com.jun.labs.ledger.service.HistoryResult
import com.jun.labs.ledger.service.UsePointsUseCase
import com.jun.labs.ledger.service.UseResult
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** ADR-000 §6 — 경로 id · limit은 문자열로 받아 직접 판정한다(+5 · -1 · abc · Long 범위 밖 = 400) */
@RestController
@RequestMapping("/points")
class PointController(
    private val earnPointsUseCase: EarnPointsUseCase,
    private val usePointsUseCase: UsePointsUseCase,
    private val cancelUseUseCase: CancelUseUseCase,
    private val cancelEarnUseCase: CancelEarnUseCase,
    private val getPointsUseCase: GetPointsUseCase,
) {
    @PostMapping("/earn")
    @ResponseStatus(HttpStatus.CREATED)
    fun earn(@RequestBody(required = false) request: EarnPointsRequest?): EarnResult =
        earnPointsUseCase.earn((request ?: throw noBody()).toCommand())

    @PostMapping("/use")
    @ResponseStatus(HttpStatus.CREATED)
    fun use(@RequestBody(required = false) request: UsePointsRequest?): UseResult =
        usePointsUseCase.use((request ?: throw noBody()).toCommand())

    /** 본문 없음 · {} · {"cancelAmount": null} = 남은 사용액 전부 */
    @PostMapping("/uses/{useId}/cancel")
    fun cancelUse(@PathVariable useId: String, @RequestBody(required = false) request: CancelUseRequest?): UseResult {
        val id = parsePathId(useId, "useId")
        return cancelUseUseCase.cancel((request ?: CancelUseRequest()).toCommand(id))
    }

    /** 본문을 읽지 않는다 */
    @PostMapping("/earns/{earnId}/cancel")
    fun cancelEarn(@PathVariable earnId: String): EarnResult =
        cancelEarnUseCase.cancel(parsePathId(earnId, "earnId"))

    @GetMapping("/users/{userId}/balance")
    fun balance(@PathVariable userId: String): BalanceResult =
        getPointsUseCase.balance(parsePathId(userId, "userId"))

    @GetMapping("/users/{userId}/history")
    fun history(@PathVariable userId: String, @RequestParam(required = false) limit: String?): List<HistoryResult> {
        val id = parsePathId(userId, "userId")
        return getPointsUseCase.history(id, parseHistoryLimit(limit))
    }

    @GetMapping("/earns/{earnId}")
    fun getEarn(@PathVariable earnId: String): EarnResult =
        getPointsUseCase.earn(parsePathId(earnId, "earnId"))

    @GetMapping("/uses/{useId}")
    fun getUse(@PathVariable useId: String): UseResult =
        getPointsUseCase.use(parsePathId(useId, "useId"))

    private fun noBody() = PointException(ErrorCode.INVALID_REQUEST, "본문 없음")
}
