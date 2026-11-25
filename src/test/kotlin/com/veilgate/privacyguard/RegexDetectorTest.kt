package com.veilgate.privacyguard

import com.veilgate.privacyguard.detect.RegexDetector
import com.veilgate.privacyguard.domain.SensitiveType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RegexDetectorTest {

    private val detector = RegexDetector()

    @Test
    fun `주민번호 · 카드번호 · 전화번호 · 이메일 · 금액을 찾는다`() {
        val text = "주민 900101-1234567, 카드 1234-5678-9012-3456, 전화 010-8375-4499, 메일 a.b@sirloin.co.kr, 3억 원"
        val found = detector.detect(text).map { Pair(it.type, it.text) }
        assertEquals(
            listOf(
                Pair(SensitiveType.RESIDENT_NUMBER, "900101-1234567"),
                Pair(SensitiveType.CARD, "1234-5678-9012-3456"),
                Pair(SensitiveType.PHONE, "010-8375-4499"),
                Pair(SensitiveType.EMAIL, "a.b@sirloin.co.kr"),
                Pair(SensitiveType.AMOUNT, "3억 원"),
            ),
            found,
        )
    }

    @Test
    fun `약 이름 · 날짜 · 용량은 찾지 않는다`() {
        val found = detector.detect("11월 20일부터 콘서타 18mg, 웰정을 먹는다")
        assertEquals(emptyList(), found)
    }
}
