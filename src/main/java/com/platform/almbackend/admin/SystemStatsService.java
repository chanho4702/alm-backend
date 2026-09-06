package com.platform.almbackend.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.almbackend.admin.dto.SystemStatsResponse;
import com.platform.almbackend.repository.AuditLogRepository;
import com.platform.almbackend.repository.IssueAttachmentRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 관리 콘솔 시스템 현황. 세는 것은 그대로고(응답 계약 불변), 60초 캐시만 앞에 둔다.
 *
 * <p>왜: 이 엔드포인트는 COUNT 다섯 개인데 그중 하나는 첨부 용량 합계(SUM)다. 관리자 대시보드가
 * 이걸 폴링하기 시작하면 화면 수만큼 집계가 돈다. 부하 원칙(스펙 §0)대로 서버에서 한 번만 세고
 * 분당 한 번만 갱신한다. 대가는 최대 60초의 지연이며, 총량 지표에는 그 정도면 충분하다.
 *
 * <p>권한 판정은 여기 없다 — 컨트롤러의 {@code @PreAuthorize}가 매 호출 판정하므로
 * 캐시가 인가를 건너뛰게 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class SystemStatsService {

    /** 전역 총량이라 사용자별로 갈리지 않는다 — 항목이 하나뿐인 캐시. */
    private static final String KEY = "alm-stats";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final ProjectRepository projects;
    private final IssueRepository issues;
    private final IssueAttachmentRepository attachments;
    private final AuditLogRepository auditLogs;

    private final Cache<String, SystemStatsResponse> cache = Caffeine.newBuilder()
            .expireAfterWrite(TTL)
            .maximumSize(1)
            .build();

    public SystemStatsResponse stats() {
        return cache.get(KEY, key -> new SystemStatsResponse(
                projects.count(), issues.count(), attachments.count(),
                attachments.totalBytes(), auditLogs.count()));
    }

    /** 테스트 격리용. 운영에서는 TTL로만 비운다. */
    public void evictAll() {
        cache.invalidateAll();
    }
}
