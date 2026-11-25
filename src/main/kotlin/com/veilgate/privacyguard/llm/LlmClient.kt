package com.veilgate.privacyguard.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * OpenRouter 의 Chat Completions API 를 부른다.
 * 시연용이라 "로컬 LLM"(탐지)과 "외부 LLM"(답변) 모두 같은 OpenRouter 로 부르고, 모델만 다르게 쓴다.
 */
@Component
class LlmClient(private val properties: GuardLlmProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(properties.endpoint.trimEnd('/'))
        .requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(5_000)
            setReadTimeout(60_000)
        })
        .defaultHeader("Authorization", "Bearer " + properties.apiKey)
        .build()

    /** 로컬 LLM 역할: 민감정보 찾기 */
    fun askLocalModel(instructions: String, input: String): String? {
        return chat(properties.localModel, instructions, input)
    }

    /** 외부 LLM 역할: 가린 요청으로 실제 답변 만들기 */
    fun askExternalModel(instructions: String, input: String): String? {
        return chat(properties.externalModel, instructions, input)
    }

    /** 호출이 실패하면 null 을 돌려준다. 시연이 멈추지 않게 호출부가 대신할 값을 쓴다 */
    private fun chat(model: String, instructions: String, input: String): String? {
        val isConfigured = properties.apiKey.isNotBlank() && model.isNotBlank()
        if (!isConfigured) {
            log.warn("OpenRouter 설정(.env)이 비어 있어 LLM 을 부르지 않는다. model={}", model)
            return null
        }
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = instructions),
                ChatMessage(role = "user", content = input),
            ),
            temperature = 0.0,
        )
        try {
            val response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatResponse::class.java)
            val firstChoice = response?.choices?.firstOrNull()
            return firstChoice?.message?.content
        } catch (e: Exception) {
            log.warn("LLM 호출 실패. model={}, error={}", model, e.message)
            return null
        }
    }

    /**
     * 외부 AI 를 도구와 함께 부른다. 도구를 쓸 수 없는 모델이면 도구 없이 다시 부른다.
     * 호출이 실패하면 null
     */
    fun askExternalModelWithTools(messages: List<ToolChatMessage>, tools: List<ToolDefinition>): ToolChatReply? {
        val model = properties.externalModel
        val isConfigured = properties.apiKey.isNotBlank() && model.isNotBlank()
        if (!isConfigured) {
            log.warn("OpenRouter 설정(.env)이 비어 있어 LLM 을 부르지 않는다. model={}", model)
            return null
        }
        val withTools = callWithTools(ToolChatRequest(model, messages, tools, 0.0))
        if (withTools != null) {
            return withTools
        }
        log.warn("도구와 함께 부르기 실패. 도구 없이 다시 부른다. model={}", model)
        return callWithTools(ToolChatRequest(model, messages, null, 0.0))
    }

    private fun callWithTools(request: ToolChatRequest): ToolChatReply? {
        try {
            val response = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ToolChatResponse::class.java)
            val message = response?.choices?.firstOrNull()?.message
            if (message == null) {
                return null
            }
            return ToolChatReply(message.content, message.tool_calls ?: emptyList())
        } catch (e: Exception) {
            log.warn("LLM 호출 실패. model={}, error={}", request.model, e.message)
            return null
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ToolChatRequest(
        val model: String,
        val messages: List<ToolChatMessage>,
        val tools: List<ToolDefinition>?,
        val temperature: Double,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ToolChatResponse(val choices: List<ToolChatChoice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ToolChatChoice(val message: ToolChatMessage? = null)

    data class ChatMessage(val role: String, val content: String)

    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatResponse(val choices: List<ChatChoice> = emptyList())

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatChoice(val message: ChatResponseMessage? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChatResponseMessage(val content: String? = null)
}
