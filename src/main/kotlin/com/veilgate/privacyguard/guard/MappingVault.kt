package com.veilgate.privacyguard.guard

import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** 저장소에 넣은 요청 하나의 치환 장부 */
data class VaultEntry(
    val vaultId: String,
    val mappings: List<TokenMapping>,
    val savedAt: LocalDateTime,
    val expiresAt: LocalDateTime,
)

/**
 * 치환 장부를 잠깐 보관하는 메모리 저장소 (실서비스에서는 Redis 로 바꿀 자리).
 * 재치환이 끝나면 바로 파기하고, 혹시 남아도 5분이 지나면 꺼낼 수 없다.
 */
@Component
class MappingVault {

    private val entries = CopyOnWriteArrayList<VaultEntry>()

    fun save(mappings: List<TokenMapping>, now: LocalDateTime = LocalDateTime.now()): VaultEntry {
        // 5분 동안만 둔다 (파기를 못 했을 때의 안전장치)
        val entry = VaultEntry(UUID.randomUUID().toString(), mappings.toList(), now, now.plusMinutes(5))
        entries.add(entry)
        return entry
    }

    /** 장부를 꺼낸다. 없거나 시간이 지났으면 null */
    fun find(vaultId: String, now: LocalDateTime = LocalDateTime.now()): VaultEntry? {
        removeExpired(now)
        return entries.find { it.vaultId == vaultId }
    }

    /** 도구 결과를 치환하다 치환 값이 늘었으면 장부를 바꿔 넣는다 (만료 시각은 그대로) */
    fun updateMappings(vaultId: String, mappings: List<TokenMapping>) {
        val entry = entries.find { it.vaultId == vaultId }
        if (entry == null) {
            return
        }
        entries.remove(entry)
        entries.add(entry.copy(mappings = mappings.toList()))
    }

    /** 사용이 끝난 장부를 지운다. 지운 치환 값 개수를 돌려준다 */
    fun destroy(vaultId: String): Int {
        val entry = entries.find { it.vaultId == vaultId }
        if (entry == null) {
            return 0
        }
        entries.remove(entry)
        return entry.mappings.size
    }

    /** 지금 저장소에 남아 있는 치환 값 개수 (대시보드용) */
    fun remainingMappingCount(now: LocalDateTime = LocalDateTime.now()): Int {
        removeExpired(now)
        return entries.sumOf { it.mappings.size }
    }

    private fun removeExpired(now: LocalDateTime) {
        entries.removeIf { !it.expiresAt.isAfter(now) }
    }
}
