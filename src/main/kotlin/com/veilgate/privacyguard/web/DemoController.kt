package com.veilgate.privacyguard.web

import com.veilgate.privacyguard.audit.AuditLogService
import com.veilgate.privacyguard.domain.SensitiveType
import com.veilgate.privacyguard.guard.MappingVault
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

private const val SAMPLE_INPUT = "천동민 고객이 어제 결제가 두 번 됐다고 문의했어. 최근 결제 내역 확인해서 중복 결제가 맞는지 판단하고, 고객에게 보낼 안내 문자도 써 줘."

/** 시연 화면. 단계 진행은 화면의 스크립트가 /api/run 을 하나씩 불러 그린다 */
@Controller
class DemoController(
    private val auditLogService: AuditLogService,
    private val mappingVault: MappingVault,
) {

    @GetMapping("/")
    fun demo(model: Model): String {
        model.addAttribute("types", SensitiveType.entries)
        model.addAttribute("protectedTypes", SensitiveType.defaultProtected())
        model.addAttribute("input", SAMPLE_INPUT)
        return "demo"
    }

    @GetMapping("/dashboard")
    fun dashboard(model: Model): String {
        val records = auditLogService.findRecent()
        model.addAttribute("records", records)
        model.addAttribute("totalCount", records.size)
        model.addAttribute("protectedTotal", records.sumOf { it.protectedTotal })
        model.addAttribute("leakedTotal", records.sumOf { it.leakedCount })
        model.addAttribute("vaultRemaining", mappingVault.remainingMappingCount())
        return "dashboard"
    }
}
