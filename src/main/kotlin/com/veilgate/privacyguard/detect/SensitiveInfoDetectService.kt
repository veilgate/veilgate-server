package com.veilgate.privacyguard.detect

import org.springframework.stereotype.Service

/** 정규식과 로컬 LLM 의 결과를 합쳐, 원문에서 가릴 후보 위치를 겹치지 않게 돌려준다 */
@Service
class SensitiveInfoDetectService(
    private val regexDetector: RegexDetector,
    private val llmDetector: LlmDetector,
) {

    data class DetectionResult(
        val spans: List<DetectedSpan>,
        val usedLocalLlm: Boolean, // false 면 로컬 LLM 대신 예비 사전을 썼다
    )

    fun detect(text: String): DetectionResult {
        // 로컬 LLM 이 찾은 것을 먼저 넣는다. 같은 자리를 정규식도 찾으면 로컬 LLM 쪽이 남는다
        val candidates = mutableListOf<DetectedSpan>()

        val llmFound = llmDetector.detect(text)
        val usedLocalLlm = llmFound != null
        val foundTexts = llmFound ?: DemoDictionary.entries.filter { text.contains(it.text) }
        val source = if (usedLocalLlm) DetectSource.LLM else DetectSource.DICTIONARY
        for (found in foundTexts) {
            // 같은 값이 여러 번 나오면 모두 가린다
            var from = text.indexOf(found.text)
            while (from >= 0) {
                candidates.add(DetectedSpan(from, from + found.text.length, found.text, found.type, source))
                from = text.indexOf(found.text, from + found.text.length)
            }
        }
        // 정규식은 로컬 LLM 이 놓친 것을 받쳐 준다
        candidates.addAll(regexDetector.detect(text))

        // 앞에서부터, 같은 자리면 긴 것부터 고르고 겹치는 것은 버린다
        val sorted = candidates.sortedWith(compareBy<DetectedSpan> { it.start }.thenByDescending { it.end - it.start })
        val chosen = mutableListOf<DetectedSpan>()
        for (span in sorted) {
            val overlapsChosen = chosen.any { span.start < it.end && it.start < span.end }
            if (!overlapsChosen) {
                chosen.add(span)
            }
        }
        return DetectionResult(chosen, usedLocalLlm)
    }
}
