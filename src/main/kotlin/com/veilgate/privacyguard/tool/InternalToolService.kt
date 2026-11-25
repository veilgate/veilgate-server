package com.veilgate.privacyguard.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.guard.TokenRegistry
import com.veilgate.privacyguard.llm.ToolDefinition
import com.veilgate.privacyguard.llm.ToolFunction
import org.springframework.stereotype.Service

/** 화면에 그릴 표 하나 */
data class ToolTable(val columns: List<String>, val rows: List<List<String>>)

/**
 * 도구 한 번 실행한 기록.
 * 외부 AI 가 본 호출(maskedCall)과 사내에서 실제로 실행한 호출(realCall), 사내 원본 결과와 가려서 넘긴 결과를 함께 남긴다.
 */
data class ToolExecution(
    val toolCallId: String,
    val name: String,
    val maskedCall: String,
    val realCall: String,
    val originalTable: ToolTable,
    val maskedTable: ToolTable,
    val resultForModel: String, // 외부 AI 에 돌려줄 결과 (가린 값만 들어 있다)
)

/**
 * 외부 AI 가 부를 수 있는 사내 도구.
 * 외부 AI 는 N1 같은 치환 값으로 도구를 부르고, VeilGate 가 사내에서 원래 값으로 바꿔 실행한 뒤
 * 결과의 개인정보를 다시 치환해서 돌려준다. 금액 · 날짜 · 가맹점 같은 판단에 필요한 값은 그대로 간다.
 */
@Service
class InternalToolService(
    private val db: SampleCommerceDb,
    private val objectMapper: ObjectMapper,
) {

    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(
            function = ToolFunction(
                name = "get_customer",
                description = "사내 DB 에서 고객 기본 정보(등급 · 가입일 · 연락처)를 조회한다. customer 에는 질문에 나온 고객 값(N1 등)을 그대로 넣는다.",
                parameters = objectMapper.readTree(
                    """{"type":"object","properties":{"customer":{"type":"string","description":"고객 값. 예: N1"}},"required":["customer"]}"""
                ),
            )
        ),
        ToolDefinition(
            function = ToolFunction(
                name = "get_payments",
                description = "사내 DB 에서 고객의 최근 결제 내역(결제 시각 · 가맹점 · 금액 · 상태 · 카드)을 최신순으로 조회한다. customer 에는 질문에 나온 고객 값(N1 등)을 그대로 넣는다.",
                parameters = objectMapper.readTree(
                    """{"type":"object","properties":{"customer":{"type":"string","description":"고객 값. 예: N1"},"limit":{"type":"integer","description":"가져올 건수. 기본 10"}},"required":["customer"]}"""
                ),
            )
        ),
    )

    fun execute(toolCallId: String, name: String, argumentsJson: String, registry: TokenRegistry): ToolExecution {
        val arguments = readArguments(argumentsJson)
        val customerToken = arguments.path("customer").asText("")
        // 외부 AI 가 넘긴 치환 값을 사내에서만 원래 이름으로 되돌린다.
        // 고객은 장부에 있는 이름 치환 값(N1 등)으로만 지정할 수 있다. 실제 이름이나 장부에 없는 값이면 조회하지 않는다
        val tokenMapping = registry.mappings.find { it.token == customerToken && it.type == SensitiveType.PERSON }
        val customerName: String? = tokenMapping?.original
        val realCustomerText = customerName ?: "장부에 없는 값 · 조회 안 함"
        // 한 번에 가져오는 결제 건수는 1~20건으로 제한한다
        val limit = arguments.path("limit").asInt(10).coerceIn(1, 20)

        if (name == "get_customer") {
            val customer = if (customerName == null) null else db.findCustomer(customerName)
            val original = ToolTable(listOf("이름", "전화번호", "이메일", "등급", "가입일"), emptyList())
            if (customer == null) {
                return notFound(toolCallId, name, "get_customer(customer = $customerToken)", "get_customer(customer = $realCustomerText)", original)
            }
            val originalRow = listOf(customer.name, customer.phone, customer.email, customer.grade, customer.joinedAt)
            val maskedRow = listOf(
                registry.tokenFor(customer.name, SensitiveType.PERSON),
                registry.tokenFor(customer.phone, SensitiveType.PHONE),
                registry.tokenFor(customer.email, SensitiveType.EMAIL),
                customer.grade,
                customer.joinedAt,
            )
            val maskedTable = ToolTable(original.columns, listOf(maskedRow))
            return ToolExecution(
                toolCallId, name,
                "get_customer(customer = $customerToken)", "get_customer(customer = $customerName)",
                ToolTable(original.columns, listOf(originalRow)), maskedTable, toJson(maskedTable),
            )
        }

        if (name == "get_payments") {
            val payments = if (customerName == null) emptyList() else db.findPayments(customerName, limit)
            val columns = listOf("고객", "결제 시각", "가맹점", "금액", "상태", "카드")
            val maskedCall = "get_payments(customer = $customerToken, limit = $limit)"
            val realCall = "get_payments(customer = $realCustomerText, limit = $limit)"
            if (payments.isEmpty()) {
                return notFound(toolCallId, name, maskedCall, realCall, ToolTable(columns, emptyList()))
            }
            val originalRows = payments.map { listOf(it.customerName, it.paidAt, it.merchant, formatWon(it.amount), it.status, it.cardNumber) }
            // 이름 · 카드번호만 치환하고, 판단에 필요한 시각 · 가맹점 · 금액 · 상태는 그대로 둔다
            val maskedRows = payments.map {
                listOf(
                    registry.tokenFor(it.customerName, SensitiveType.PERSON),
                    it.paidAt,
                    it.merchant,
                    formatWon(it.amount),
                    it.status,
                    registry.tokenFor(it.cardNumber, SensitiveType.CARD),
                )
            }
            val maskedTable = ToolTable(columns, maskedRows)
            return ToolExecution(toolCallId, name, maskedCall, realCall, ToolTable(columns, originalRows), maskedTable, toJson(maskedTable))
        }

        val unknown = ToolTable(listOf("결과"), listOf(listOf("알 수 없는 도구: $name")))
        return ToolExecution(toolCallId, name, "$name(...)", "$name(...)", unknown, unknown, """{"error":"unknown tool"}""")
    }

    private fun notFound(toolCallId: String, name: String, maskedCall: String, realCall: String, emptyTable: ToolTable): ToolExecution {
        return ToolExecution(toolCallId, name, maskedCall, realCall, emptyTable, emptyTable, """{"result":"조회 결과가 없습니다."}""")
    }

    private fun readArguments(argumentsJson: String): ObjectNode {
        try {
            val node = objectMapper.readTree(argumentsJson)
            if (node is ObjectNode) {
                return node
            }
        } catch (e: Exception) {
            // 인자가 JSON 이 아니면 빈 인자로 본다
        }
        return objectMapper.createObjectNode()
    }

    /** 표를 외부 AI 가 읽기 쉬운 JSON 배열로 만든다: [{"고객":"N1","금액":"39,800원",…}] */
    private fun toJson(table: ToolTable): String {
        val array = objectMapper.createArrayNode()
        for (row in table.rows) {
            val item = objectMapper.createObjectNode()
            for (i in table.columns.indices) {
                item.put(table.columns[i], row[i])
            }
            array.add(item)
        }
        return objectMapper.writeValueAsString(array)
    }

    private fun formatWon(amount: Int): String = String.format("%,d원", amount)
}
