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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
@Tag(name = "Issue History", description = "이슈 변경 이력과 활동 피드")
public class IssueChangeLogController {
    private final IssueChangeLogService history;

    /**
     * 프로젝트 변경 이력 — 리포트가 집계하는 원천이다. 필터는 전부 선택이며 시간 오름차순이다.
     * `since`는 ISO-8601 인스턴트(예: 2026-08-01T00:00:00Z).
     */
    @Operation(summary = "프로젝트의 이슈 변경 이력을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/changes")
    public List<IssueChangeResponse> changes(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Parameter(description = "바뀐 필드로 거른다(상태·담당자 등)") @RequestParam(required = false) ChangeField field,
            @Parameter(description = "해당 스프린트에 속한 이슈만 본다") @RequestParam(required = false) Long sprintId,
            @Parameter(description = "이 시각 이후 변경만 본다. ISO-8601 인스턴트(예: 2026-08-01T00:00:00Z)")
            @RequestParam(required = false) Instant since,
            @AuthenticationPrincipal Jwt jwt) {
        return history.history(userId(jwt), projectId, field, sprintId, since);
    }
}
