package com.veilgate.privacyguard.tool

import org.springframework.stereotype.Component

/** 사내 DB 의 고객 한 명 (시연용 가상 데이터) */
data class Customer(
    val name: String,
    val phone: String,
    val email: String,
    val grade: String,
    val joinedAt: String,
)

/** 사내 DB 의 결제 한 건 (시연용 가상 데이터) */
data class Payment(
    val customerName: String,
    val paidAt: String,
    val merchant: String,
    val amount: Int,
    val status: String,
    val cardNumber: String,
)

/**
 * 시연용 사내 DB. 외부 AI 는 이 DB 에 직접 닿지 않고, VeilGate 가 사내에서 대신 조회한다.
 * 실서비스에서는 사내 DB · 사내 API 를 부르는 자리다.
 */
@Component
class SampleCommerceDb {

    val customers = listOf(
        Customer("천동민", "010-8375-4499", "dongmin.chun@example.com", "VIP", "2021-03-14"),
        Customer("최유진", "010-3381-9054", "yujin.choi@example.com", "일반", "2024-07-02"),
        Customer("박지훈", "010-2948-7731", "jihoon.park@example.com", "골드", "2022-11-30"),
    )

    val payments = listOf(
        Payment("천동민", "2026-11-24 12:31:05", "무신사스토어", 39800, "승인", "4518-2273-9904-1186"),
        Payment("천동민", "2026-11-24 12:31:48", "무신사스토어", 39800, "승인", "4518-2273-9904-1186"),
        Payment("천동민", "2026-11-20 10:12:31", "쿠팡", 23500, "승인", "4518-2273-9904-1186"),
        Payment("천동민", "2026-11-02 18:40:09", "스타벅스 강남점", 6100, "승인", "9410-6612-3087-5524"),
        Payment("천동민", "2026-10-28 09:05:44", "쿠팡", 23500, "취소", "4518-2273-9904-1186"),
        Payment("최유진", "2026-11-23 21:14:02", "배달의민족", 27000, "승인", "5276-1180-4432-9017"),
        Payment("최유진", "2026-11-18 14:40:51", "올리브영", 41200, "승인", "5276-1180-4432-9017"),
        Payment("박지훈", "2026-11-22 08:30:10", "SRT", 52900, "승인", "3561-9920-1475-6683"),
    )

    fun findCustomer(name: String): Customer? = customers.find { it.name == name }

    /** 최근 결제부터 */
    fun findPayments(customerName: String, limit: Int): List<Payment> {
        return payments
            .filter { it.customerName == customerName }
            .sortedByDescending { it.paidAt }
            .take(limit)
    }
}
