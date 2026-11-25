package com.veilgate.privacyguard.guard

import com.veilgate.privacyguard.audit.AuditLogService
import com.veilgate.privacyguard.audit.AuditRecord
import com.veilgate.privacyguard.audit.TypeCount
import com.veilgate.privacyguard.detect.SensitiveInfoDetectService
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.llm.GuardLlmProperties
import com.veilgate.privacyguard.llm.LlmClient
import com.veilgate.privacyguard.llm.ToolChatMessage
import com.veilgate.privacyguard.tool.InternalToolService
import com.veilgate.privacyguard.tool.ToolExecution
import org.springframework.stereotype.Component
import java.time.LocalDateTime

private val EXTERNAL_INSTRUCTIONS = """
사용자의 요청을 수행하고, 최종 결과물만 반환하세요.
- 입력의 N1, P1 같은 "대문자 한 글자 + 숫자"는 개인정보를 대신한 값입니다. 답에서도 글자 그대로 쓰고, 뜻을 추측하거나 바꾸지 마세요.
- 요청에 사람을 대신한 값(N1 등)이 있으면, 답에서 그 사람을 부를 때도 그 값을 쓰세요. 예: "N1 회원님의 경우"
- 사용자가 지정한 조건과 출력 형식을 따르세요.
- 마크다운 기호(#, **, 표)는 쓰지 말고 평문으로 쓰세요. 항목을 나눌 때는 줄을 바꾸고 "· " 로 시작하세요.
- 고객 정보나 결제 내역이 필요하면 제공된 도구를 호출하세요. 고객을 가리킬 때는 N1 같은 값을 그대로 넘기세요.
- 도구 결과에 있는 N1 · K1 같은 값도 개인정보를 대신한 값입니다. 답에서도 그대로 쓰세요.
""".trimIndent()

/**
 * VeilGate 처리 단계. 화면은 단계를 하나씩 불러 진행 상황을 보여 주고, API 는 runAll 로 한 번에 처리한다.
 * 탐지 → 치환 · 보관 → 외부 AI 요청 → (외부 AI 가 원하면) 사내 도구 실행 → 외부 AI 답 → 재치환 → 전달 · 파기
 */
@Component
class PrivacyGuardFacade(
    private val detectService: SensitiveInfoDetectService,
    private val maskingService: MaskingService,
    private val restoreService: RestoreService,
    private val mappingVault: MappingVault,
    private val runStore: GuardRunStore,
    private val llmClient: LlmClient,
    private val internalToolService: InternalToolService,
    private val llmProperties: GuardLlmProperties,
    private val auditLogService: AuditLogService,
) {

    data class DetectStepResult(
        val originalSegments: List<TextSegment>,
        val decisions: List<MaskingService.SpanDecision>,
        val usedLocalLlm: Boolean,
        val localModel: String,
        val millis: Long,
    )

    data class MaskStepResult(
        val blocked: Boolean,
        val blockReason: String?,
        val originalSegments: List<TextSegment>,
        val maskedSegments: List<TextSegment>,
        val mappings: List<TokenMapping>,
        val vaultId: String,
        val vaultExpiresAt: LocalDateTime?,
    )

    /** 외부 AI 에 보낸 첫 요청. 외부 AI 가 도구를 원하면 toolRequests 가 있고, 바로 답했으면 answerSegments 가 있다 */
    data class AskStepResult(
        val externalModel: String,
        val sentSegments: List<TextSegment>,
        val toolRequests: List<ToolRequestView>,
        val answerSegments: List<TextSegment>,
        val usedExternalLlm: Boolean,
        val millis: Long,
    )

    data class ToolRequestView(val name: String, val arguments: String)

    data class ToolsStepResult(val skipped: Boolean, val executions: List<ToolExecution>, val newTokens: List<TokenMapping>)

    data class AnswerStepResult(val answerSegments: List<TextSegment>, val usedExternalLlm: Boolean, val millis: Long)

    data class RestoreStepResult(val restoredSegments: List<TextSegment>, val restoredText: String)

    data class FinishStepResult(val destroyedCount: Int, val vaultRemaining: Int, val totalMillis: Long)

    /** 처리를 시작한다. 이후 단계는 runId 로 이어서 부른다 */
    fun start(input: String, protectedTypes: List<SensitiveType>): String {
        return runStore.create(input, protectedTypes).runId
    }

    /** 1. 로컬 LLM(+ 정규식 안전망)이 개인정보를 찾는다 */
    fun detect(runId: String): DetectStepResult {
        val run = findRun(runId)
        val startedAt = System.currentTimeMillis()
        val detection = detectService.detect(run.input)
        run.detection = detection
        run.detectMillis = System.currentTimeMillis() - startedAt
        // 화면에 보여 주려고 미리 한 번 칠해 본다 (치환 값은 다음 단계에서 정해진다)
        val preview = maskingService.mask(run.input, detection.spans, run.protectedTypes, TokenRegistry())
        return DetectStepResult(preview.originalSegments, preview.decisions, detection.usedLocalLlm, llmProperties.localModel, run.detectMillis)
    }

    /** 2. 보호할 종류를 N1 · P1 로 치환하고, 나가기 전 검사 후 장부를 메모리 저장소에 둔다 */
    fun mask(runId: String): MaskStepResult {
        val run = findRun(runId)
        val detection = run.detection ?: throw IllegalStateException("탐지 단계를 먼저 해야 합니다.")
        val masked = maskingService.mask(run.input, detection.spans, run.protectedTypes, run.registry)
        run.masked = masked

        // 나가기 전 검사: 치환한 개인정보가 하나라도 남아 있으면 외부로 보내지 않는다
        val leaked = maskingService.findLeakedValues(masked.maskedText, masked.decisions)
        if (leaked.isNotEmpty()) {
            run.blocked = true
            run.blockReason = "외부로 보낼 글에 개인정보가 " + leaked.size + "개 남아 있어 보내지 않았습니다."
            saveAudit(run, 0)
            runStore.remove(runId)
            return MaskStepResult(true, run.blockReason, masked.originalSegments, emptyList(), emptyList(), "", null)
        }

        val vaultEntry = mappingVault.save(run.registry.mappings)
        run.vaultId = vaultEntry.vaultId
        return MaskStepResult(
            blocked = false,
            blockReason = null,
            originalSegments = masked.originalSegments,
            maskedSegments = restoreService.segmentsWithTokens(masked.maskedText, run.registry.mappings),
            mappings = run.registry.mappings,
            vaultId = vaultEntry.vaultId,
            vaultExpiresAt = vaultEntry.expiresAt,
        )
    }

    /** 3. 외부 AI 에는 치환한 글만 보낸다. 외부 AI 는 바로 답하거나, 사내 도구(DB 조회)를 불러 달라고 한다 */
    fun ask(runId: String): AskStepResult {
        val run = findRun(runId)
        val masked = run.masked ?: throw IllegalStateException("치환 단계를 먼저 해야 합니다.")
        run.messages.add(ToolChatMessage(role = "system", content = EXTERNAL_INSTRUCTIONS))
        run.messages.add(ToolChatMessage(role = "user", content = masked.maskedText))
        val startedAt = System.currentTimeMillis()
        val reply = llmClient.askExternalModelWithTools(run.messages, internalToolService.definitions)
        run.externalMillis = System.currentTimeMillis() - startedAt
        run.usedExternalLlm = reply != null
        val sentSegments = restoreService.segmentsWithTokens(masked.maskedText, run.registry.mappings)

        if (reply != null && reply.toolCalls.isNotEmpty()) {
            run.messages.add(ToolChatMessage(role = "assistant", content = reply.content, tool_calls = reply.toolCalls))
            run.pendingToolCalls = reply.toolCalls
            val requests = reply.toolCalls.map { ToolRequestView(it.function.name, it.function.arguments) }
            return AskStepResult(llmProperties.externalModel, sentSegments, requests, emptyList(), true, run.externalMillis)
        }

        // 부르지 못하면 시연이 멈추지 않게 데모 답을 쓴다
        val answer = reply?.content ?: ("외부 AI 에 연결하지 못해 데모 답을 보여 줍니다.\n보낸 요청: " + masked.maskedText)
        run.answer = answer
        val answerSegments = restoreService.segmentsWithTokens(answer, run.registry.mappings)
        return AskStepResult(llmProperties.externalModel, sentSegments, emptyList(), answerSegments, run.usedExternalLlm, run.externalMillis)
    }

    /**
     * 4. 외부 AI 가 부탁한 사내 도구를 VeilGate 가 대신 실행한다.
     * 치환 값을 사내에서만 원래 값으로 바꿔 조회하고, 결과의 개인정보는 다시 치환해서 돌려준다
     */
    fun runTools(runId: String): ToolsStepResult {
        val run = findRun(runId)
        if (run.pendingToolCalls.isEmpty()) {
            return ToolsStepResult(true, emptyList(), emptyList())
        }
        val tokenCountBefore = run.registry.mappings.size
        val executions = executePendingTools(run)
        val newTokens = run.registry.mappings.drop(tokenCountBefore)
        return ToolsStepResult(false, executions, newTokens)
    }

    /** 5. 도구 결과(치환된 값만)를 받은 외부 AI 가 최종 답을 만든다. 도구를 안 썼으면 첫 답을 그대로 쓴다 */
    fun answer(runId: String): AnswerStepResult {
        val run = findRun(runId)
        val existing = run.answer
        if (existing != null) {
            return AnswerStepResult(restoreService.segmentsWithTokens(existing, run.registry.mappings), run.usedExternalLlm, 0)
        }
        val startedAt = System.currentTimeMillis()
        var finalAnswer: String? = null
        // 외부 AI 가 도구를 한 번 더 원하면 한 번까지 더 들어준다
        for (round in 1..2) {
            val reply = llmClient.askExternalModelWithTools(run.messages, internalToolService.definitions)
            if (reply == null) {
                break
            }
            if (reply.toolCalls.isEmpty()) {
                finalAnswer = reply.content
                break
            }
            run.messages.add(ToolChatMessage(role = "assistant", content = reply.content, tool_calls = reply.toolCalls))
            run.pendingToolCalls = reply.toolCalls
            executePendingTools(run)
        }
        val millis = System.currentTimeMillis() - startedAt
        run.externalMillis += millis
        val answer = finalAnswer ?: "외부 AI 에 연결하지 못해 답을 만들지 못했습니다."
        run.answer = answer
        return AnswerStepResult(restoreService.segmentsWithTokens(answer, run.registry.mappings), finalAnswer != null, millis)
    }

    private fun executePendingTools(run: GuardRun): List<ToolExecution> {
        val executions = mutableListOf<ToolExecution>()
        for (call in run.pendingToolCalls) {
            val execution = internalToolService.execute(call.id, call.function.name, call.function.arguments, run.registry)
            // 나가기 전 검사: 도구 결과에 원래 개인정보가 남아 있으면 그 결과는 보내지 않는다
            val originals = run.registry.mappings.map { it.original }
            val leaked = originals.filter { execution.resultForModel.contains(it) }
            val resultForModel = if (leaked.isEmpty()) execution.resultForModel else """{"error":"개인정보가 남아 있어 결과를 보내지 않았습니다."}"""
            run.messages.add(ToolChatMessage(role = "tool", content = resultForModel, tool_call_id = call.id))
            executions.add(execution)
        }
        run.toolExecutions.addAll(executions)
        run.pendingToolCalls = emptyList()
        // 도구 결과를 치환하며 늘어난 치환 값(카드번호 K1 등)도 장부에 넣어 재치환에 쓴다
        mappingVault.updateMappings(run.vaultId ?: "", run.registry.mappings)
        return executions
    }

    /** 4. 저장소에서 장부를 꺼내 원래 개인정보로 재치환한다 */
    fun restore(runId: String): RestoreStepResult {
        val run = findRun(runId)
        val answer = run.answer ?: throw IllegalStateException("외부 AI 단계를 먼저 해야 합니다.")
        val vaultId = run.vaultId ?: ""
        val storedMappings = mappingVault.find(vaultId)?.mappings ?: emptyList()
        val restored = restoreService.restore(answer, storedMappings)
        return RestoreStepResult(restored.restoredSegments, restored.restoredText)
    }

    /** 5. 결과를 전달했으면 장부와 처리 상태를 메모리에서 지운다 */
    fun finish(runId: String): FinishStepResult {
        val run = findRun(runId)
        val destroyedCount = mappingVault.destroy(run.vaultId ?: "")
        saveAudit(run, destroyedCount)
        runStore.remove(runId)
        val total = System.currentTimeMillis() - run.startedAt
        return FinishStepResult(destroyedCount, mappingVault.remainingMappingCount(), total)
    }

    /** API 용: 모든 단계를 한 번에 처리하고 재치환한 답을 돌려준다. 차단되면 null */
    fun runAll(input: String, protectedTypes: List<SensitiveType>): String? {
        val runId = start(input, protectedTypes)
        detect(runId)
        val masked = mask(runId)
        if (masked.blocked) {
            return null
        }
        ask(runId)
        runTools(runId)
        answer(runId)
        val restored = restore(runId)
        finish(runId)
        return restored.restoredText
    }

    private fun findRun(runId: String): GuardRun {
        return runStore.find(runId) ?: throw NoSuchElementException("처리를 찾을 수 없습니다. 처음부터 다시 보내 주세요.")
    }

    private fun saveAudit(run: GuardRun, destroyedCount: Int) {
        // 종류별 치환 개수만 남긴다 (원래 값은 기록하지 않는다)
        val decisions = run.masked?.decisions ?: emptyList()
        val protectedCounts = decisions
            .filter { it.token != null }
            .groupBy { it.span.type }
            .map { group -> TypeCount(group.key, group.value.size) }
        auditLogService.save(
            AuditRecord(
                id = auditLogService.nextId(),
                requestedAt = LocalDateTime.now(),
                channel = "VeilGate",
                blocked = run.blocked,
                blockReason = run.blockReason,
                protectedCounts = protectedCounts,
                leakedCount = 0,
                destroyedCount = destroyedCount,
                externalModel = llmProperties.externalModel,
                usedLocalLlm = run.detection?.usedLocalLlm ?: false,
                usedExternalLlm = run.usedExternalLlm,
                elapsedMillis = System.currentTimeMillis() - run.startedAt,
            )
        )
    }
}
