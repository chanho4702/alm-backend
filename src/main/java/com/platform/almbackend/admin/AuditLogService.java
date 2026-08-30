package com.platform.almbackend.admin;

import com.platform.almbackend.admin.dto.AuditLogResponse;
import com.platform.almbackend.admin.dto.AuditPageResponse;
import com.platform.almbackend.domain.AuditLog;
import com.platform.almbackend.repository.AuditLogRepository;
import com.platform.proto.events.v1.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 감사 로그 — 도메인 이벤트 봉투(EventEnvelope)를 같은 트랜잭션에서 한 줄로 남긴다.
 * 이벤트 발행(Redis)이 실패해도 감사 로그는 커밋과 함께 남는다. 조회는 관리자만.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AuditLogService {
    private static final int MAX_SIZE = 200;

    private final AuditLogRepository logs;

    public void record(EventEnvelope event) {
        String type = event.getPayloadCase().name();
        Long projectId = null;
        String key = null;
        String summary = null;
        switch (event.getPayloadCase()) {
            case PROJECT_CREATED -> {
                projectId = event.getProjectCreated().getProjectId();
                key = event.getProjectCreated().getKey();
                summary = event.getProjectCreated().getName();
            }
            case PROJECT_UPDATED -> {
                projectId = event.getProjectUpdated().getProjectId();
                key = event.getProjectUpdated().getKey();
                summary = event.getProjectUpdated().getName();
            }
            case PROJECT_DELETED -> projectId = event.getProjectDeleted().getProjectId();
            case ISSUE_CREATED -> {
                projectId = event.getIssueCreated().getProjectId();
                key = event.getIssueCreated().getIssueKey();
                summary = event.getIssueCreated().getTitle();
            }
            case ISSUE_UPDATED -> {
                projectId = event.getIssueUpdated().getProjectId();
                key = event.getIssueUpdated().getIssueKey();
                summary = event.getIssueUpdated().getTitle();
            }
            case ISSUE_DELETED -> {
                projectId = event.getIssueDeleted().getProjectId();
                key = event.getIssueDeleted().getIssueKey();
            }
            default -> { }
        }
        logs.save(AuditLog.of(
                event.getEventId(), type, event.getActorId(), projectId, key, summary,
                Instant.ofEpochMilli(event.getOccurredAt()).truncatedTo(ChronoUnit.MICROS)));
    }

    @Transactional(readOnly = true)
    public AuditPageResponse search(String type, Long actorId, Long projectId, Instant since, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_SIZE));
        Page<AuditLog> result = logs.search(
                type == null || type.isBlank() ? null : type, actorId, projectId, since,
                PageRequest.of(Math.max(0, page), safeSize));
        return new AuditPageResponse(
                result.getContent().stream().map(AuditLogResponse::from).toList(),
                result.getNumber(), safeSize, result.getTotalElements());
    }
}
