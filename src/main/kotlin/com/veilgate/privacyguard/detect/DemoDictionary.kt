package com.veilgate.privacyguard.detect

import com.veilgate.privacyguard.domain.SensitiveType

/**
 * 로컬 LLM 을 부를 수 없을 때 시연이 멈추지 않게 쓰는 예비 사전.
 * 데모 예시 문장에 나오는 이름 · 회사만 담았다.
 */
object DemoDictionary {

    val entries = listOf(
        LlmDetector.FoundText(SensitiveType.PERSON, "천동민"),
        LlmDetector.FoundText(SensitiveType.PERSON, "장세한"),
        LlmDetector.FoundText(SensitiveType.PERSON, "김철수"),
        LlmDetector.FoundText(SensitiveType.COMPANY, "삼성전자"),
    )
}
