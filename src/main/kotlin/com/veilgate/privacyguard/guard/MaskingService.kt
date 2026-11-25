package com.veilgate.privacyguard.guard

import com.veilgate.privacyguard.detect.DetectedSpan
import com.veilgate.privacyguard.domain.SensitiveType
import org.springframework.stereotype.Service

/** 찾은 개인정보 중 보호할 종류를 치환 값(N1 · P1 …)으로 바꾼다 */
@Service
class MaskingService {

    /** 찾은 값 하나를 어떻게 했는지. token 이 null 이면 보호 대상이 아니라 그대로 보냈다 */
    data class SpanDecision(val span: DetectedSpan, val token: String?)

    data class MaskOutcome(
        val maskedText: String,
        val originalSegments: List<TextSegment>,
        val decisions: List<SpanDecision>,
    )

    /** spans 는 앞에서부터 정렬돼 있고 서로 겹치지 않아야 한다 */
    fun mask(
        text: String,
        spans: List<DetectedSpan>,
        protectedTypes: List<SensitiveType>,
        registry: TokenRegistry,
    ): MaskOutcome {
        val masked = StringBuilder()
        val originalSegments = mutableListOf<TextSegment>()
        val decisions = mutableListOf<SpanDecision>()
        var cursor = 0
        for (span in spans) {
            if (span.start > cursor) {
                val plain = text.substring(cursor, span.start)
                masked.append(plain)
                originalSegments.add(TextSegment(plain, SegmentKind.PLAIN))
            }
            val typeLabel = span.type.label + " · " + span.source.label
            val isProtected = protectedTypes.contains(span.type)
            if (isProtected) {
                val token = registry.tokenFor(span.text, span.type)
                masked.append(token)
                originalSegments.add(TextSegment(span.text, SegmentKind.MASKED, "$typeLabel → $token", token))
                decisions.add(SpanDecision(span, token))
            } else {
                masked.append(span.text)
                originalSegments.add(TextSegment(span.text, SegmentKind.KEPT, "$typeLabel → 보호 대상이 아니라 그대로 보냄"))
                decisions.add(SpanDecision(span, null))
            }
            cursor = span.end
        }
        if (cursor < text.length) {
            val rest = text.substring(cursor)
            masked.append(rest)
            originalSegments.add(TextSegment(rest, SegmentKind.PLAIN))
        }
        return MaskOutcome(masked.toString(), originalSegments, decisions)
    }

    /** 나가기 전 검사: 치환한 개인정보가 외부로 보낼 글에 남아 있으면 그 값을 돌려준다. 비어 있어야 보낸다 */
    fun findLeakedValues(maskedText: String, decisions: List<SpanDecision>): List<String> {
        val hiddenValues = decisions.filter { it.token != null }.map { it.span.text }.distinct()
        return hiddenValues.filter { maskedText.contains(it) }
    }
}
