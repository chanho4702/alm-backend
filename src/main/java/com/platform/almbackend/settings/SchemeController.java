package com.platform.almbackend.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import com.platform.almbackend.settings.dto.SettingsResponses.SchemeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.util.List;
import java.util.Map;
import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.platform.almbackend.config.NoOrgDependency;
import io.swagger.v3.oas.annotations.Parameter;

/**
 * 설정 스킴 API — 읽기는 로그인, 쓰기는 전역 관리자. 2026-10-18 SettingsController에서 분리(동작 불변).
 */
@RestController
@RequiredArgsConstructor
public class SchemeController {
    private final SchemeService schemes;

    // ── 스킴 ──
    public record SchemeCreateRequest(String name) {}
    public record SchemeUpdateRequest(String name, SettingsBody body) {}

    @NoOrgDependency
    @Tag(name = "Settings Schemes")
    @Operation(summary = "설정 스킴 목록을 조회한다")
    @GetMapping("/api/alm/settings/schemes")
    public List<SchemeResponse> listSchemes() { return schemes.list(); }

    @NoOrgDependency
    @Tag(name = "Settings Schemes")
    @Operation(summary = "이 스킴을 쓰는 프로젝트 수를 조회한다")
    @GetMapping("/api/alm/settings/schemes/{id}/projects/count")
    public Map<String, Long> schemeProjects(@Parameter(description = "설정 스킴 ID") @PathVariable String id) { return Map.of("count", schemes.countProjects(id)); }

    @Tag(name = "Settings Schemes")
    @Operation(summary = "설정 스킴을 만든다")
    @PostMapping("/api/alm/settings/schemes")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public SchemeResponse createScheme(@RequestBody SchemeCreateRequest request) { return schemes.create(request.name()); }

    @Tag(name = "Settings Schemes")
    @Operation(summary = "설정 스킴의 이름과 내용을 수정한다")
    @PutMapping("/api/alm/settings/schemes/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public SchemeResponse updateScheme(@Parameter(description = "설정 스킴 ID") @PathVariable String id, @RequestBody SchemeUpdateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.update(userId(jwt), id, request.name(), request.body());
    }

    @Tag(name = "Settings Schemes")
    @Operation(summary = "설정 스킴을 삭제한다")
    @DeleteMapping("/api/alm/settings/schemes/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScheme(@Parameter(description = "설정 스킴 ID") @PathVariable String id) { schemes.delete(id); }

    @Tag(name = "Settings Schemes")
    @Operation(summary = "기본 설정 스킴을 지정한다")
    @PostMapping("/api/alm/settings/schemes/{id}/default")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefault(@Parameter(description = "설정 스킴 ID") @PathVariable String id) { schemes.setDefault(id); }
}
