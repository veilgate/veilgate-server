package com.veilgate.privacyguard.llm

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/** 도구 호출(function calling)을 주고받는 메시지 한 건. OpenAI Chat Completions 모양 그대로 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolChatMessage(
    val role: String = "",
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: ToolCallFunction = ToolCallFunction(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ToolCallFunction(val name: String = "", val arguments: String = "{}")

/** 외부 AI 에 알려 주는 도구 하나. parameters 는 JSON Schema */
data class ToolDefinition(val type: String = "function", val function: ToolFunction)

data class ToolFunction(val name: String, val description: String, val parameters: JsonNode)

/** 외부 AI 의 답: 글로 답했거나(content), 도구를 불러 달라고 했거나(toolCalls) */
data class ToolChatReply(val content: String?, val toolCalls: List<ToolCall>)
