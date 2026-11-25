package com.veilgate.privacyguard.detect

import com.veilgate.privacyguard.domain.SensitiveType
import org.springframework.stereotype.Component

/** 모양이 정해진 개인정보를 정규식으로 찾는다. 로컬 LLM 이 놓친 것을 받쳐 주는 안전망이다 */
@Component
class RegexDetector {

    private data class TypePattern(val type: SensitiveType, val regex: Regex)

    // 주민번호를 전화번호보다 먼저 본다 (같은 자리에서 겹치면 앞에서 찾은 것이 남는다)
    private val patterns = listOf(
        TypePattern(SensitiveType.RESIDENT_NUMBER, Regex("""\d{6}-[1-4]\d{6}""")),
        TypePattern(SensitiveType.CARD, Regex("""\d{4}-\d{4}-\d{4}-\d{4}""")),
        TypePattern(SensitiveType.PHONE, Regex("""0\d{1,2}-\d{3,4}-\d{4}""")),
        TypePattern(SensitiveType.EMAIL, Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),
        // 33000원 · 3,000원 · 3억 원 · 5천만원
        TypePattern(SensitiveType.AMOUNT, Regex("""\d[\d,]*(?:\.\d+)?\s?(?:조|억|천만|백만|만|천)?\s?원""")),
    )

    fun detect(text: String): List<DetectedSpan> {
        val found = mutableListOf<DetectedSpan>()
        for (pattern in patterns) {
            for (match in pattern.regex.findAll(text)) {
                found.add(
                    DetectedSpan(
                        start = match.range.first,
                        end = match.range.last + 1,
                        text = match.value,
                        type = pattern.type,
                        source = DetectSource.REGEX,
                    )
                )
            }
        }
        return found
    }
}
