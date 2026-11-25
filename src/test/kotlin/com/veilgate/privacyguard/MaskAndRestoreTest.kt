package com.veilgate.privacyguard

import com.veilgate.privacyguard.detect.DetectSource
import com.veilgate.privacyguard.detect.DetectedSpan
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.guard.MappingVault
import com.veilgate.privacyguard.guard.MaskingService
import com.veilgate.privacyguard.guard.RestoreService
import com.veilgate.privacyguard.guard.TokenMapping
import com.veilgate.privacyguard.guard.TokenRegistry
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MaskAndRestoreTest {

    private val maskingService = MaskingService()
    private val restoreService = RestoreService()

    // 원문에서 값의 위치를 찾아 span 을 만든다
    private fun spanOf(text: String, value: String, type: SensitiveType, from: Int = 0): DetectedSpan {
        val start = text.indexOf(value, from)
        return DetectedSpan(start, start + value.length, value, type, DetectSource.LLM)
    }

    @Test
    fun `이름은 N1, 전화번호는 P1 로 치환하고 약 이름은 그대로 둔다`() {
        val text = "천동민(010-8375-4499) 회원의 약 (콘서타, 웰정) 부작용 확인해줘"
        val spans = listOf(
            spanOf(text, "천동민", SensitiveType.PERSON),
            spanOf(text, "010-8375-4499", SensitiveType.PHONE),
        )
        val registry = TokenRegistry()
        val outcome = maskingService.mask(text, spans, SensitiveType.defaultProtected(), registry)

        assertEquals("N1(P1) 회원의 약 (콘서타, 웰정) 부작용 확인해줘", outcome.maskedText)
        assertEquals(
            listOf(
                TokenMapping("N1", "천동민", SensitiveType.PERSON),
                TokenMapping("P1", "010-8375-4499", SensitiveType.PHONE),
            ),
            registry.mappings,
        )
    }

    @Test
    fun `같은 값은 같은 치환 값, 다른 값은 다음 번호를 받는다`() {
        val text = "천동민과 김철수, 다시 천동민"
        val spans = listOf(
            spanOf(text, "천동민", SensitiveType.PERSON),
            spanOf(text, "김철수", SensitiveType.PERSON),
            spanOf(text, "천동민", SensitiveType.PERSON, 5),
        )
        val outcome = maskingService.mask(text, spans, SensitiveType.defaultProtected(), TokenRegistry())

        assertEquals("N1과 N2, 다시 N1", outcome.maskedText)
    }

    @Test
    fun `보호 대상에서 뺀 종류는 찾아도 그대로 보낸다`() {
        val text = "삼성전자 김철수"
        val spans = listOf(
            spanOf(text, "삼성전자", SensitiveType.COMPANY),
            spanOf(text, "김철수", SensitiveType.PERSON),
        )
        val outcome = maskingService.mask(text, spans, listOf(SensitiveType.PERSON), TokenRegistry())

        assertEquals("삼성전자 N1", outcome.maskedText)
        assertEquals(listOf(null, "N1"), outcome.decisions.map { it.token })
    }

    @Test
    fun `재치환은 장부에 있는 값만 정확히 바꾼다 - N1 이 N10 이나 비타민 B1 에 걸리지 않는다`() {
        val mappings = (1..10).map { TokenMapping("N$it", "사람$it", SensitiveType.PERSON) }
        val outcome = restoreService.restore("N1님, N10님, AN1, 비타민 B1, N1.", mappings)

        assertEquals("사람1님, 사람10님, AN1, 비타민 B1, 사람1.", outcome.restoredText)
    }

    @Test
    fun `나가기 전 검사는 치환한 개인정보가 남아 있으면 찾아낸다`() {
        val text = "천동민 회원"
        val spans = listOf(spanOf(text, "천동민", SensitiveType.PERSON))
        val outcome = maskingService.mask(text, spans, SensitiveType.defaultProtected(), TokenRegistry())

        assertEquals(emptyList(), maskingService.findLeakedValues(outcome.maskedText, outcome.decisions))
        assertEquals(listOf("천동민"), maskingService.findLeakedValues("천동민 회원", outcome.decisions))
    }

    @Test
    fun `저장소는 파기하면 다시 꺼낼 수 없고, 파기를 못 해도 5분이 지나면 꺼낼 수 없다`() {
        val vault = MappingVault()
        val now = LocalDateTime.of(2026, 11, 25, 12, 0, 0)
        val mappings = listOf(TokenMapping("N1", "천동민", SensitiveType.PERSON), TokenMapping("P1", "010-8375-4499", SensitiveType.PHONE))

        val used = vault.save(mappings, now)
        assertNotNull(vault.find(used.vaultId, now))
        assertEquals(2, vault.destroy(used.vaultId))
        assertNull(vault.find(used.vaultId, now))
        assertEquals(0, vault.remainingMappingCount(now))

        val forgotten = vault.save(mappings, now)
        assertNotNull(vault.find(forgotten.vaultId, now.plusMinutes(4)))
        assertNull(vault.find(forgotten.vaultId, now.plusMinutes(5)))
    }
}
