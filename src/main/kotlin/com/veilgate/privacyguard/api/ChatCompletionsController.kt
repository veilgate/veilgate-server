package com.veilgate.privacyguard.api

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.guard.PrivacyGuardFacade
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * OpenAI 와 같은 모양의 API. 기존 AI 호출 코드에서 주소만 http://localhost:8080/v1 로 바꾸면 VeilGate 를 거친다.
 * 개인정보는 기본 보호 종류(이름 · 전화번호 · 주민번호 · 이메일 · 주소 · 카드번호)를 가린다.
 */
@RestController
class ChatCompletionsController(private val privacyGuardFacade: PrivacyGuardFacade) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatMessage(val role: String = "user", val content: String = "")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatCompletionRequest(val model: String = "", val messages: List<ChatMessage> = emptyList())

    data class ChatChoice(val index: Int, val message: ChatMessage, val finish_reason: String)

    data class ChatCompletionResponse(
        val id: String,
        val `object`: String,
        val created: Long,
        val model: String,
        val choices: List<ChatChoice>,
    )

    data class GuardError(val error: String)

    @PostMapping("/v1/chat/completions")
    fun chatCompletions(
        @RequestBody request: ChatCompletionRequest,
    ): ResponseEntity<Any> {
        // 시연용: 마지막 사용자 메시지 하나를 VeilGate 로 처리한다
        val lastUserMessage = request.messages.lastOrNull { it.role == "user" }
        if (lastUserMessage == null) {
            return ResponseEntity.badRequest().body(GuardError("user 메시지가 없습니다."))
        }
        val restoredText = privacyGuardFacade.runAll(lastUserMessage.content, SensitiveType.defaultProtected())
        if (restoredText == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(GuardError("외부로 보낼 글에 개인정보가 남아 있어 보내지 않았습니다."))
        }
        val response = ChatCompletionResponse(
            id = "veilgate-" + System.currentTimeMillis(),
            `object` = "chat.completion",
            created = System.currentTimeMillis() / 1000,
            model = request.model,
            choices = listOf(ChatChoice(0, ChatMessage("assistant", restoredText), "stop")),
        )
        return ResponseEntity.ok(response)
    }
}
