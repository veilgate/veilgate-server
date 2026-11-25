package com.veilgate.privacyguard.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.guard.PrivacyGuardFacade
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 데모 화면이 단계를 하나씩 부르는 API. 단계가 끝날 때마다 화면이 결과를 그리고 다음 단계로 넘어간다 */
@RestController
@RequestMapping("/api/run")
class GuardStepController(private val privacyGuardFacade: PrivacyGuardFacade) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StartRequest(val input: String = "", val protect: List<SensitiveType> = emptyList())

    data class StartResponse(val runId: String)

    data class StepError(val error: String)

    @PostMapping
    fun start(@RequestBody request: StartRequest): StartResponse {
        return StartResponse(privacyGuardFacade.start(request.input, request.protect))
    }

    @PostMapping("/{runId}/detect")
    fun detect(@PathVariable runId: String) = privacyGuardFacade.detect(runId)

    @PostMapping("/{runId}/mask")
    fun mask(@PathVariable runId: String) = privacyGuardFacade.mask(runId)

    @PostMapping("/{runId}/ask")
    fun ask(@PathVariable runId: String) = privacyGuardFacade.ask(runId)

    @PostMapping("/{runId}/tools")
    fun tools(@PathVariable runId: String) = privacyGuardFacade.runTools(runId)

    @PostMapping("/{runId}/answer")
    fun answer(@PathVariable runId: String) = privacyGuardFacade.answer(runId)

    @PostMapping("/{runId}/restore")
    fun restore(@PathVariable runId: String) = privacyGuardFacade.restore(runId)

    @PostMapping("/{runId}/finish")
    fun finish(@PathVariable runId: String) = privacyGuardFacade.finish(runId)

    @ExceptionHandler(NoSuchElementException::class, IllegalStateException::class)
    fun handleStepError(e: RuntimeException): ResponseEntity<StepError> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(StepError(e.message ?: "처리 순서가 맞지 않습니다."))
    }
}
