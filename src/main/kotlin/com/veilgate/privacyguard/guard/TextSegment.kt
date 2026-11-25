package com.veilgate.privacyguard.guard

/** 화면에서 글자 조각을 어떻게 칠할지 */
enum class SegmentKind(val cssClass: String) {
    PLAIN("seg-plain"),
    MASKED("seg-masked"), // 원본: 치환한 개인정보 (빨강)
    KEPT("seg-kept"), // 원본: 찾았지만 보호 대상이 아니라 그대로 보낸 값 (노랑)
    TOKEN("seg-token"), // 치환 값: N1 · P1 (파랑)
    RESTORED("seg-restored"), // 재치환 결과: 되돌린 개인정보 (초록)
}

/**
 * 화면에 그릴 글자 조각 하나. label 은 마우스를 올리면 보이는 설명.
 * token 은 바뀌는 모습을 보여 줄 때 쓴다: 원본의 MASKED 조각은 바뀔 치환 값, RESTORED 조각은 되돌리기 전 치환 값
 */
data class TextSegment(val text: String, val kind: SegmentKind, val label: String = "", val token: String = "") {
    // 화면 스크립트가 바로 칠할 수 있게 css 클래스도 같이 내려 준다
    val cssClass: String get() = kind.cssClass
}
