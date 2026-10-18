package com.platform.almbackend.history;

import com.platform.almbackend.domain.ChangeField;
import com.platform.almbackend.history.dto.IssueChangeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
     * `since`·`until`은 ISO-8601 인스턴트(예: 2026-08-01T00:00:00Z). 응답 본문은 배열 그대로(하위 호환)이고,
     * 상한에 걸려 오래된 쪽이 잘렸으면 `X-Result-Truncated: true` 헤더를 붙인다(`X-Result-Limit`은 적용된 상한).
     */
    @Operation(summary = "프로젝트의 이슈 변경 이력을 조회한다",
            description = "기간(since 포함 ~ until 배타)과 상한(limit, 기본·최대 20000) 안의 이력을 시간 오름차순으로 준다. "
                    + "상한을 넘으면 최신 쪽만 남기고 `X-Result-Truncated: true` 헤더를 붙인다.")
    @GetMapping("/api/alm/projects/{projectId}/changes")
    public ResponseEntity<List<IssueChangeResponse>> changes(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Parameter(description = "바뀐 필드로 거른다(상태·담당자 등)") @RequestParam(required = false) ChangeField field,
            @Parameter(description = "해당 스프린트에 속한 이슈만 본다") @RequestParam(required = false) Long sprintId,
            @Parameter(description = "이 시각 이후 변경만 본다(포함). ISO-8601 인스턴트(예: 2026-08-01T00:00:00Z)")
            @RequestParam(required = false) Instant since,
            @Parameter(description = "이 시각 이전 변경만 본다(배타). ISO-8601 인스턴트")
            @RequestParam(required = false) Instant until,
            @Parameter(description = "최대 행 수(기본·최대 20000)") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        IssueChangeLogService.HistorySlice slice = history.history(userId(jwt), projectId, field, sprintId, since, until, limit);
        return ResponseEntity.ok()
                .header("X-Result-Truncated", String.valueOf(slice.truncated()))
                .header("X-Result-Limit", String.valueOf(slice.limit()))
                .body(slice.items());
    }
}
