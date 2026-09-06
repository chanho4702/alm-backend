package com.platform.almbackend.admin;

import com.platform.almbackend.admin.dto.AuditPageResponse;
import com.platform.almbackend.admin.dto.SystemStatsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 관리 콘솔 — 감사 로그·시스템 현황. 전역 관리자(org-service GLOBAL/ADMIN)만 읽는다. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("@globalAdmin.check(authentication)")
@Tag(name = "Admin", description = "감사 로그와 시스템 현황 — 전역 관리자 전용")
public class AdminController {
    private final AuditLogService audit;
    private final SystemStatsService systemStats;

    @Operation(summary = "감사 로그를 조건별로 조회한다")
    @GetMapping("/api/alm/admin/audit")
    public AuditPageResponse audit(
            @Parameter(description = "감사 항목 종류로 거른다") @RequestParam(required = false) String type,
            @Parameter(description = "행위자 사용자 ID로 거른다") @RequestParam(required = false) Long actorId,
            @Parameter(description = "프로젝트 ID로 거른다") @RequestParam(required = false) Long projectId,
            @Parameter(description = "이 시각 이후만 본다. ISO-8601 인스턴트(예: 2026-08-01T00:00:00Z)")
            @RequestParam(required = false) Instant since,
            @Parameter(description = "0부터 세는 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "한 페이지 항목 수") @RequestParam(defaultValue = "50") int size) {
        return audit.search(type, actorId, projectId, since, page, size);
    }

    @Operation(summary = "프로젝트·이슈·첨부 총량 등 시스템 현황을 조회한다. 서버에서 60초 캐시한다")
    @GetMapping("/api/alm/admin/stats")
    public SystemStatsResponse stats() {
        return systemStats.stats();
    }
}
