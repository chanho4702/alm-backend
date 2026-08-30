package com.platform.almbackend.admin;

import com.platform.almbackend.admin.dto.AuditPageResponse;
import com.platform.almbackend.admin.dto.SystemStatsResponse;
import com.platform.almbackend.repository.AuditLogRepository;
import com.platform.almbackend.repository.IssueAttachmentRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** 관리 콘솔 — 감사 로그·시스템 현황. 토큰의 roles에 ADMIN이 있어야 한다. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AuditLogService audit;
    private final ProjectRepository projects;
    private final IssueRepository issues;
    private final IssueAttachmentRepository attachments;
    private final AuditLogRepository auditLogs;

    @GetMapping("/api/alm/admin/audit")
    public AuditPageResponse audit(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Instant since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audit.search(type, actorId, projectId, since, page, size);
    }

    @GetMapping("/api/alm/admin/stats")
    public SystemStatsResponse stats() {
        return new SystemStatsResponse(
                projects.count(), issues.count(), attachments.count(),
                attachments.totalBytes(), auditLogs.count());
    }
}
