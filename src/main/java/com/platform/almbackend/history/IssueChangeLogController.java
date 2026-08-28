package com.platform.almbackend.history;

import com.platform.almbackend.domain.ChangeField;
import com.platform.almbackend.history.dto.IssueChangeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

@RestController
@RequiredArgsConstructor
public class IssueChangeLogController {
    private final IssueChangeLogService history;

    /**
     * 프로젝트 변경 이력 — 리포트가 집계하는 원천이다. 필터는 전부 선택이며 시간 오름차순이다.
     * `since`는 ISO-8601 인스턴트(예: 2026-08-01T00:00:00Z).
     */
    @GetMapping("/api/alm/projects/{projectId}/changes")
    public List<IssueChangeResponse> changes(
            @PathVariable long projectId,
            @RequestParam(required = false) ChangeField field,
            @RequestParam(required = false) Long sprintId,
            @RequestParam(required = false) Instant since,
            @AuthenticationPrincipal Jwt jwt) {
        return history.history(userId(jwt), projectId, field, sprintId, since);
    }
}
