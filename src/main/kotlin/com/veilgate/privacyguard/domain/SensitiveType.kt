package com.veilgate.privacyguard.domain

/**
 * 가릴 수 있는 개인정보 종류.
 * tokenLetter 는 치환할 때 쓰는 글자다. 천동민 → N1, 010-8375-4499 → P1
 * protectedByDefault 가 true 인 종류는 처음부터 보호한다 (화면에서 끄고 켤 수 있다)
 */
enum class SensitiveType(val label: String, val tokenLetter: String, val protectedByDefault: Boolean) {
    PERSON("이름", "N", true),
    PHONE("전화번호", "P", true),
    RESIDENT_NUMBER("주민등록번호", "R", true),
    EMAIL("이메일", "E", true),
    ADDRESS("주소", "A", true),
    CARD("카드번호", "K", true),
    COMPANY("회사 · 기관", "C", false),
    AMOUNT("금액", "M", false),
    ;

    companion object {
        fun defaultProtected(): List<SensitiveType> = entries.filter { it.protectedByDefault }
    }
}
