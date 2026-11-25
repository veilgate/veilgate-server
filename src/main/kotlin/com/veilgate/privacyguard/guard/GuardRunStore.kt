package com.veilgate.privacyguard.guard

import com.veilgate.privacyguard.detect.SensitiveInfoDetectService
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.llm.ToolCall
import com.veilgate.privacyguard.llm.ToolChatMessage
import com.veilgate.privacyguard.tool.ToolExecution
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 화면이 단계를 하나씩 부를 때 쓰는 처리 상태 한 건.
 * 원래 값이 들어 있으므로 마지막 단계(파기)에서 장부와 함께 지운다.
 */
class GuardRun(val runId: String, val input: String, val protectedTypes: List<SensitiveType>, val startedAt: Long) {
    var detection: SensitiveInfoDetectService.DetectionResult? = null
    val registry = TokenRegistry()
    var masked: MaskingService.MaskOutcome? = null
    var vaultId: String? = null
    var answer: String? = null
    val messages = mutableListOf<ToolChatMessage>() // 외부 AI 와 주고받은 대화 (치환된 값만 들어 있다)
    var pendingToolCalls: List<ToolCall> = emptyList()
    val toolExecutions = mutableListOf<ToolExecution>()
    var usedExternalLlm = false
    var blocked = false
    var blockReason: String? = null
    var detectMillis = 0L
    var externalMillis = 0L
}

/** 처리 중인 GuardRun 을 잠깐 두는 메모리 보관함. 10분이 지난 것은 버린다 */
@Component
class GuardRunStore {

    private val runs = CopyOnWriteArrayList<GuardRun>()

    fun create(input: String, protectedTypes: List<SensitiveType>): GuardRun {
        removeOld()
        val run = GuardRun(UUID.randomUUID().toString(), input, protectedTypes, System.currentTimeMillis())
        runs.add(run)
        return run
    }

    fun find(runId: String): GuardRun? {
        removeOld()
        return runs.find { it.runId == runId }
    }

    fun remove(runId: String) {
        runs.removeIf { it.runId == runId }
    }

    private fun removeOld() {
        // 10분 넘게 끝나지 않은 처리는 버린다 (원래 값이 메모리에 오래 남지 않게)
        val limit = System.currentTimeMillis() - 10 * 60 * 1000
        runs.removeIf { it.startedAt < limit }
    }
}
