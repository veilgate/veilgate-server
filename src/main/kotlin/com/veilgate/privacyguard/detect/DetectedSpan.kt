package com.veilgate.privacyguard.detect

import com.veilgate.privacyguard.domain.SensitiveType

/** 어디서 찾았는지 (화면에 보여 주려고 남긴다) */
enum class DetectSource(val label: String) {
    REGEX("정규식"),
    LLM("로컬 LLM"),
    DICTIONARY("예비 사전"),
}

/** 원문에서 찾은 민감정보 한 군데. start 는 포함, end 는 포함하지 않는다 */
data class DetectedSpan(
    val start: Int,
    val end: Int,
    val text: String,
    val type: SensitiveType,
    val source: DetectSource,
) {
    // 화면 스크립트에 보여 줄 이름
    val typeLabel: String get() = type.label
    val sourceLabel: String get() = source.label
}
