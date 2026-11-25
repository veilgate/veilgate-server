package com.veilgate.privacyguard.audit

import com.veilgate.privacyguard.domain.SensitiveType
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** 종류별로 몇 개를 치환했는지. 원래 값은 담지 않는다 */
data class TypeCount(val type: SensitiveType, val count: Int)

/** 요청 하나의 처리 기록 */
data class AuditRecord(
    val id: Long,
    val requestedAt: LocalDateTime,
    val channel: String, // 데모 화면 / API
    val blocked: Boolean,
    val blockReason: String?,
    val protectedCounts: List<TypeCount>,
    val leakedCount: Int, // 외부로 나간 개인정보 수. 나가기 전 검사가 막으므로 늘 0 이어야 한다
    val destroyedCount: Int, // 사용 후 파기한 치환 값 수
    val externalModel: String,
    val usedLocalLlm: Boolean,
    val usedExternalLlm: Boolean,
    val elapsedMillis: Long,
) {
    val protectedTotal: Int get() = protectedCounts.sumOf { it.count }
}

/** 시연용 감사 로그. DB 없이 메모리에 최근 200건만 둔다 */
@Service
class AuditLogService {

    private val records = CopyOnWriteArrayList<AuditRecord>()
    private val sequence = AtomicLong()

    fun nextId(): Long = sequence.incrementAndGet()

    fun save(record: AuditRecord) {
        records.add(0, record)
        while (records.size > 200) {
            records.removeAt(records.size - 1)
        }
    }

    fun findRecent(): List<AuditRecord> = records.toList()
}
