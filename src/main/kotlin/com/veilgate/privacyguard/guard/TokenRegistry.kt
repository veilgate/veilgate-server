package com.veilgate.privacyguard.guard

import com.veilgate.privacyguard.domain.SensitiveType

/** 치환 값 하나가 어떤 개인정보인지. 예: N1 → 천동민 */
data class TokenMapping(val token: String, val original: String, val type: SensitiveType)

/**
 * 요청 하나를 치환하는 동안 쓰는 장부. 같은 값은 같은 치환 값을 받는다.
 * 종류마다 1 부터 센다: 이름 N1 · N2, 전화번호 P1 …
 */
class TokenRegistry {

    val mappings = mutableListOf<TokenMapping>()

    fun tokenFor(original: String, type: SensitiveType): String {
        val existing = mappings.find { it.original == original && it.type == type }
        if (existing != null) {
            return existing.token
        }
        val sameTypeCount = mappings.count { it.type == type }
        val token = type.tokenLetter + (sameTypeCount + 1)
        mappings.add(TokenMapping(token, original, type))
        return token
    }
}
