package com.veilgate.privacyguard

import com.fasterxml.jackson.databind.ObjectMapper
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.guard.MappingVault
import com.veilgate.privacyguard.guard.TokenRegistry
import com.veilgate.privacyguard.tool.InternalToolService
import com.veilgate.privacyguard.tool.SampleCommerceDb
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternalToolServiceTest {

    private val service = InternalToolService(SampleCommerceDb(), ObjectMapper())

    // 프롬프트를 치환하면서 천동민이 N1 을 받은 상태
    private fun registryWithCustomer(): TokenRegistry {
        val registry = TokenRegistry()
        registry.tokenFor("천동민", SensitiveType.PERSON)
        return registry
    }

    @Test
    fun `N1 으로 부르면 사내에서는 원래 이름으로 조회하고 결과의 이름 · 카드번호는 치환해서 넘긴다`() {
        val registry = registryWithCustomer()

        val result = service.execute("call-1", "get_payments", """{"customer":"N1","limit":3}""", registry)

        assertEquals("get_payments(customer = N1, limit = 3)", result.maskedCall)
        assertEquals("get_payments(customer = 천동민, limit = 3)", result.realCall)
        assertEquals(3, result.originalTable.rows.size)
        // 원본에는 진짜 값이 있다
        assertEquals("천동민", result.originalTable.rows[0][0])
        assertEquals("4518-2273-9904-1186", result.originalTable.rows[0][5])
        // 외부로 넘기는 결과: 이름 · 카드는 치환, 시각 · 가맹점 · 금액 · 상태는 그대로
        assertEquals(listOf("N1", "2026-11-24 12:31:48", "무신사스토어", "39,800원", "승인", "K1"), result.maskedTable.rows[0])
        assertFalse(result.resultForModel.contains("천동민"))
        assertFalse(result.resultForModel.contains("4518-2273-9904-1186"))
        assertTrue(result.resultForModel.contains("39,800원"))
        // 새로 생긴 치환 값은 장부에 들어간다
        assertTrue(registry.mappings.any { it.token == "K1" && it.original == "4518-2273-9904-1186" })
    }

    @Test
    fun `고객 조회 결과의 전화번호 · 이메일도 치환해서 넘긴다`() {
        val registry = registryWithCustomer()

        val result = service.execute("call-1", "get_customer", """{"customer":"N1"}""", registry)

        assertEquals(listOf("N1", "P1", "E1", "VIP", "2021-03-14"), result.maskedTable.rows[0])
        assertFalse(result.resultForModel.contains("010-8375-4499"))
        assertFalse(result.resultForModel.contains("dongmin.chun@example.com"))
    }

    @Test
    fun `장부에 없는 값으로 부르면 조회 결과가 없다고 돌려준다`() {
        val result = service.execute("call-1", "get_payments", """{"customer":"N9"}""", registryWithCustomer())

        assertTrue(result.originalTable.rows.isEmpty())
        assertTrue(result.resultForModel.contains("조회 결과가 없습니다"))
    }

    @Test
    fun `실제 이름이나 이름이 아닌 치환 값으로 부르면 사내 DB 를 조회하지 않는다`() {
        val registry = registryWithCustomer()
        registry.tokenFor("010-8375-4499", SensitiveType.PHONE) // P1

        // 사내 DB 에 있는 다른 고객의 실제 이름
        val byOtherRealName = service.execute("call-1", "get_payments", """{"customer":"박지훈"}""", registry)
        // N1 의 원래 이름을 직접 넣은 경우
        val byOwnRealName = service.execute("call-2", "get_customer", """{"customer":"천동민"}""", registry)
        // 장부에는 있지만 이름이 아니라 전화번호의 치환 값
        val byPhoneToken = service.execute("call-3", "get_payments", """{"customer":"P1"}""", registry)

        for (result in listOf(byOtherRealName, byOwnRealName, byPhoneToken)) {
            assertTrue(result.originalTable.rows.isEmpty())
            assertTrue(result.maskedTable.rows.isEmpty())
            assertTrue(result.resultForModel.contains("조회 결과가 없습니다"))
            assertTrue(result.realCall.contains("조회 안 함"))
        }
        // 조회를 하지 않았으니 장부에 새 치환 값이 생기지 않는다 (N1, P1 그대로)
        assertEquals(listOf("N1", "P1"), registry.mappings.map { it.token })
    }

    @Test
    fun `결제 조회 건수는 1건에서 20건 사이로 맞춘다`() {
        val registry = registryWithCustomer()

        val zero = service.execute("call-1", "get_payments", """{"customer":"N1","limit":0}""", registry)
        val tooMany = service.execute("call-2", "get_payments", """{"customer":"N1","limit":100}""", registry)

        assertEquals(1, zero.originalTable.rows.size)
        assertEquals("get_payments(customer = N1, limit = 1)", zero.maskedCall)
        assertEquals("get_payments(customer = N1, limit = 20)", tooMany.maskedCall)
    }

    @Test
    fun `도구 결과로 늘어난 치환 값은 저장소 장부에도 반영되고 만료 시각은 그대로다`() {
        val vault = MappingVault()
        val now = LocalDateTime.of(2026, 11, 25, 10, 0)
        val registry = registryWithCustomer()
        val saved = vault.save(registry.mappings, now)

        service.execute("call-1", "get_payments", """{"customer":"N1"}""", registry)
        vault.updateMappings(saved.vaultId, registry.mappings)

        val updated = vault.find(saved.vaultId, now)!!
        assertEquals(registry.mappings.size, updated.mappings.size)
        assertTrue(updated.mappings.any { it.token == "K1" })
        assertEquals(saved.expiresAt, updated.expiresAt)
    }
}
