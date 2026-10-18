package com.platform.almbackend.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import com.platform.almbackend.settings.dto.SettingsResponses.ResolvedSettingsResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;
import java.util.Map;
import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * 프로젝트 설정 API — 읽기는 프로젝트 VIEW, 쓰기는 프로젝트 ADMIN(org-service 판정).
 * 2026-10-18 SettingsController에서 분리(동작 불변).
 */
@RestController
@RequiredArgsConstructor
public class ProjectSettingsController {
    private final SchemeService schemes;

    // ── 프로젝트 설정 ──
    public record AssignRequest(String schemeId) {}
    public record CustomRequest(boolean custom) {}

    @Tag(name = "Project Settings")
    @Operation(summary = "접근 가능한 모든 프로젝트의 상태 목록을 한 번에 조회한다",
            description = "키는 프로젝트 ID(문자열), 값은 그 프로젝트에 적용된 워크플로 상태(order 순, kind·color·icon 해석 포함)다. "
                    + "프로젝트마다 /projects/{id}/settings를 부르지 않게 한다. 휴지통 프로젝트는 빠진다.")
    @GetMapping("/api/alm/projects/statuses")
    public Map<String, List<SettingsBody.WorkflowStatus>> statusesByProject(@AuthenticationPrincipal Jwt jwt) {
        return schemes.statusesByProject(userId(jwt));
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트에 실제로 적용된 설정을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/settings")
    public ResolvedSettingsResponse resolve(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return schemes.resolve(userId(jwt), projectId);
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트에 설정 스킴을 지정한다")
    @PutMapping("/api/alm/projects/{projectId}/settings/scheme")
    public ResolvedSettingsResponse assign(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @RequestBody AssignRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.assignScheme(userId(jwt), projectId, request.schemeId());
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트 설정 재정의를 켜고 끈다")
    @PutMapping("/api/alm/projects/{projectId}/settings/custom")
    public ResolvedSettingsResponse custom(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @RequestBody CustomRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.setCustom(userId(jwt), projectId, request.custom());
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트 설정 재정의 내용을 저장한다")
    @PutMapping("/api/alm/projects/{projectId}/settings/custom-body")
    public ResolvedSettingsResponse updateCustom(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @RequestBody SettingsBody body, @AuthenticationPrincipal Jwt jwt) {
        return schemes.updateCustom(userId(jwt), projectId, body);
    }
}
