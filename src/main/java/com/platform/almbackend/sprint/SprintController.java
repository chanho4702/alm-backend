package com.platform.almbackend.sprint;

import com.platform.almbackend.sprint.dto.SprintCompleteRequest;
import com.platform.almbackend.sprint.dto.SprintCreateRequest;
import com.platform.almbackend.sprint.dto.SprintResponse;
import com.platform.almbackend.sprint.dto.SprintUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.platform.almbackend.config.ConflictResponse;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequiredArgsConstructor
@Tag(name = "Sprints", description = "스프린트 계획·시작·완료")
public class SprintController {
    private final SprintService sprints;

    @Operation(summary = "프로젝트의 스프린트를 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/sprints")
    public List<SprintResponse> list(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return sprints.list(userId(jwt), projectId);
    }

    @Operation(summary = "스프린트를 만든다 — 이름을 비우면 서버가 자동으로 붙인다")
    @PostMapping("/api/alm/projects/{projectId}/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    public SprintResponse create(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Valid @RequestBody(required = false) SprintCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return sprints.create(userId(jwt), projectId, request);
    }

    @Operation(summary = "스프린트 하나를 조회한다")
    @GetMapping("/api/alm/sprints/{sprintId}")
    public SprintResponse get(@Parameter(description = "스프린트 ID") @PathVariable long sprintId, @AuthenticationPrincipal Jwt jwt) {
        return sprints.get(userId(jwt), sprintId);
    }

    @Operation(summary = "스프린트 계획을 수정한다 — expectedVersion이 어긋나면 409")
    @PutMapping("/api/alm/sprints/{sprintId}")
    public SprintResponse update(
            @Parameter(description = "스프린트 ID") @PathVariable long sprintId,
            @Valid @RequestBody SprintUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return sprints.update(userId(jwt), sprintId, request);
    }

    @ConflictResponse("계획 상태의 스프린트만 시작할 수 있습니다 / 이미 진행 중인 스프린트가 있습니다")
    @Operation(summary = "스프린트를 시작한다")
    @PostMapping("/api/alm/sprints/{sprintId}/start")
    public SprintResponse start(@Parameter(description = "스프린트 ID") @PathVariable long sprintId, @AuthenticationPrincipal Jwt jwt) {
        return sprints.start(userId(jwt), sprintId);
    }

    @ConflictResponse("진행 중인 스프린트만 완료할 수 있습니다")
    @Operation(summary = "스프린트를 완료하고 미완료 이슈를 옮긴다")
    @PostMapping("/api/alm/sprints/{sprintId}/complete")
    public SprintResponse complete(
            @Parameter(description = "스프린트 ID") @PathVariable long sprintId,
            @Valid @RequestBody(required = false) SprintCompleteRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return sprints.complete(userId(jwt), sprintId, request);
    }
}
