package com.platform.almbackend.planning;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.almbackend.issue.IssueController.userId;

@RestController
@RequiredArgsConstructor
@Tag(name = "Planning", description = "프로젝트 계획 설정 — 사이클(스프린트 자동화)·추정 척도")
public class ProjectPlanningController {
    private final ProjectPlanningService service;

    @Operation(summary = "프로젝트 계획 설정을 조회한다 — 행이 없으면 기본값(version 0)")
    @GetMapping("/api/alm/projects/{projectId}/planning-settings")
    public PlanningDtos.Response get(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                     @AuthenticationPrincipal Jwt jwt) {
        return service.get(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트 계획 설정을 수정한다 — 보낸 항목만, expectedVersion이 어긋나면 409")
    @PutMapping("/api/alm/projects/{projectId}/planning-settings")
    public PlanningDtos.Response update(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                        @Valid @RequestBody PlanningDtos.UpdateRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return service.update(userId(jwt), projectId, request);
    }
}
