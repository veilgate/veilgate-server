package com.veilgate.privacyguard.guard

import org.springframework.stereotype.Service

/**
 * 치환 값 모양: 대문자 한 글자 + 숫자 (N1 · P12).
 * 앞뒤가 영문 · 숫자에 붙어 있으면 다른 말의 일부라 보지 않는다 (N1 이 N10 · AN1 에 걸리지 않게)
 */
private val TOKEN_REGEX = Regex("""(?<![A-Za-z0-9])[A-Z]\d+(?![0-9])""")

/** 외부 AI 의 답에 있는 치환 값을 원래 개인정보로 되돌린다 */
@Service
class RestoreService {

    data class RestoreOutcome(val restoredText: String, val restoredSegments: List<TextSegment>)

    /** 장부에 있는 치환 값만 바꾼다. 장부에 없는 글자(예: 비타민 B1)는 그대로 둔다 */
    fun restore(text: String, mappings: List<TokenMapping>): RestoreOutcome {
        val restored = StringBuilder()
        val segments = mutableListOf<TextSegment>()
        var cursor = 0
        for (match in TOKEN_REGEX.findAll(text)) {
            val mapping = mappings.find { it.token == match.value }
            if (mapping == null) {
                continue
            }
            if (match.range.first > cursor) {
                val plain = text.substring(cursor, match.range.first)
                restored.append(plain)
                segments.add(TextSegment(plain, SegmentKind.PLAIN))
            }
            restored.append(mapping.original)
            segments.add(TextSegment(mapping.original, SegmentKind.RESTORED, match.value + " → 재치환", match.value))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            val rest = text.substring(cursor)
            restored.append(rest)
            segments.add(TextSegment(rest, SegmentKind.PLAIN))
        }
        return RestoreOutcome(restored.toString(), segments)
    }

    /** 치환한 글 · 외부 AI 의 답을 화면에 그릴 때 장부에 있는 치환 값을 칠한다 */
    fun segmentsWithTokens(text: String, mappings: List<TokenMapping>): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var cursor = 0
        for (match in TOKEN_REGEX.findAll(text)) {
            val mapping = mappings.find { it.token == match.value }
            if (mapping == null) {
                continue
            }
            if (match.range.first > cursor) {
                segments.add(TextSegment(text.substring(cursor, match.range.first), SegmentKind.PLAIN))
            }
            segments.add(TextSegment(match.value, SegmentKind.TOKEN, mapping.type.label))
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            segments.add(TextSegment(text.substring(cursor), SegmentKind.PLAIN))
        }
        return segments
    }
}
