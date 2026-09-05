package com.platform.almbackend.settings;

import com.platform.almbackend.settings.dto.RegistryRequests.CategoryRequest;
import com.platform.almbackend.settings.dto.RegistryRequests.IssueTypeRequest;
import com.platform.almbackend.settings.dto.RegistryRequests.PriorityRequest;
import com.platform.almbackend.settings.dto.RegistryRequests.LinkTypeRequest;
import com.platform.almbackend.settings.dto.SettingsResponses.LinkTypeResponse;
import com.platform.almbackend.settings.dto.SettingsResponses.PriorityResponse;
import com.platform.almbackend.settings.dto.RegistryRequests.MoveRequest;
import com.platform.almbackend.settings.dto.RegistryRequests.StatusRequest;
import com.platform.almbackend.settings.dto.SettingsResponses.CategoryResponse;
import com.platform.almbackend.settings.dto.SettingsResponses.IssueTypeResponse;
import com.platform.almbackend.settings.dto.SettingsResponses.ResolvedSettingsResponse;
import com.platform.almbackend.settings.dto.SettingsResponses.SchemeResponse;
import com.platform.almbackend.settings.dto.SettingsResponses.StatusResponse;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.platform.almbackend.config.NoOrgDependency;

/**
 * 설정 API. 읽기는 로그인이면 되고, 전역(레지스트리·스킴) 쓰기는 전역 관리자, 프로젝트 설정 쓰기는
 * 프로젝트 관리자다. 둘 다 판정은 org-service gRPC 하나로 한다(Keycloak 역할 아님, 2026-09-05).
 */
@RestController
@RequiredArgsConstructor
public class SettingsController {
    private final RegistryService registry;
    private final SchemeService schemes;

    // ── 상태 카테고리 ──
    @NoOrgDependency
    @Tag(name = "Status Categories")
    @Operation(summary = "상태 카테고리를 조회한다")
    @GetMapping("/api/alm/settings/categories")
    public List<CategoryResponse> categories() { return registry.categories().stream().map(CategoryResponse::from).toList(); }

    @Tag(name = "Status Categories")
    @Operation(summary = "상태 카테고리를 만든다")
    @PostMapping("/api/alm/settings/categories")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@RequestBody CategoryRequest request) { return CategoryResponse.from(registry.createCategory(request)); }

    @Tag(name = "Status Categories")
    @Operation(summary = "상태 카테고리를 수정한다")
    @PutMapping("/api/alm/settings/categories/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public CategoryResponse updateCategory(@PathVariable String id, @RequestBody CategoryRequest request) { return CategoryResponse.from(registry.updateCategory(id, request)); }

    @Tag(name = "Status Categories")
    @Operation(summary = "상태 카테고리 순서를 옮긴다")
    @PostMapping("/api/alm/settings/categories/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveCategory(@PathVariable String id, @RequestBody MoveRequest request) { registry.moveCategory(id, request.delta()); }

    @Tag(name = "Status Categories")
    @Operation(summary = "상태 카테고리를 삭제한다")
    @DeleteMapping("/api/alm/settings/categories/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable String id) { registry.deleteCategory(id); }

    // ── 상태 ──
    @NoOrgDependency
    @Tag(name = "Statuses")
    @Operation(summary = "상태 목록을 조회한다")
    @GetMapping("/api/alm/settings/statuses")
    public List<StatusResponse> statuses() { return registry.statuses().stream().map(StatusResponse::from).toList(); }

    @NoOrgDependency
    @Tag(name = "Statuses")
    @Operation(summary = "상태별로 쓰이는 이슈 수를 조회한다")
    @GetMapping("/api/alm/settings/statuses/usage")
    public Map<String, Long> statusUsage() { return registry.statusUsage(); }

    @Tag(name = "Statuses")
    @Operation(summary = "상태를 만든다")
    @PostMapping("/api/alm/settings/statuses")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public StatusResponse createStatus(@RequestBody StatusRequest request) { return StatusResponse.from(registry.createStatus(request)); }

    @Tag(name = "Statuses")
    @Operation(summary = "상태를 수정한다")
    @PutMapping("/api/alm/settings/statuses/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public StatusResponse updateStatus(@PathVariable String id, @RequestBody StatusRequest request) { return StatusResponse.from(registry.updateStatus(id, request)); }

    @Tag(name = "Statuses")
    @Operation(summary = "상태를 삭제한다")
    @DeleteMapping("/api/alm/settings/statuses/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatus(@PathVariable String id) { registry.deleteStatus(id); }

    // ── 이슈 타입 ──
    @NoOrgDependency
    @Tag(name = "Link Types")
    @Operation(summary = "이슈 연결 타입을 조회한다")
    @GetMapping("/api/alm/settings/link-types")
    public List<LinkTypeResponse> linkTypes() { return registry.linkTypes().stream().map(LinkTypeResponse::from).toList(); }

    @NoOrgDependency
    @Tag(name = "Link Types")
    @Operation(summary = "연결 타입별로 쓰이는 연결 수를 조회한다")
    @GetMapping("/api/alm/settings/link-types/usage")
    public Map<String, Long> linkTypeUsage() { return registry.linkTypeUsage(); }

    @Tag(name = "Link Types")
    @Operation(summary = "연결 타입을 만든다")
    @PostMapping("/api/alm/settings/link-types")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkTypeResponse createLinkType(@RequestBody LinkTypeRequest request) { return LinkTypeResponse.from(registry.createLinkType(request)); }

    @Tag(name = "Link Types")
    @Operation(summary = "연결 타입을 수정한다")
    @PutMapping("/api/alm/settings/link-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public LinkTypeResponse updateLinkType(@PathVariable String id, @RequestBody LinkTypeRequest request) { return LinkTypeResponse.from(registry.updateLinkType(id, request)); }

    @Tag(name = "Link Types")
    @Operation(summary = "연결 타입 순서를 옮긴다")
    @PostMapping("/api/alm/settings/link-types/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveLinkType(@PathVariable String id, @RequestBody MoveRequest request) { registry.moveLinkType(id, request.delta()); }

    @Tag(name = "Link Types")
    @Operation(summary = "연결 타입을 삭제한다")
    @DeleteMapping("/api/alm/settings/link-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLinkType(@PathVariable String id) { registry.deleteLinkType(id); }

    @NoOrgDependency
    @Tag(name = "Priorities")
    @Operation(summary = "우선순위 목록을 조회한다")
    @GetMapping("/api/alm/settings/priorities")
    public List<PriorityResponse> priorities() { return registry.priorities().stream().map(PriorityResponse::from).toList(); }

    @NoOrgDependency
    @Tag(name = "Priorities")
    @Operation(summary = "우선순위별로 쓰이는 이슈 수를 조회한다")
    @GetMapping("/api/alm/settings/priorities/usage")
    public Map<String, Long> priorityUsage() { return registry.priorityUsage(); }

    @Tag(name = "Priorities")
    @Operation(summary = "우선순위를 만든다")
    @PostMapping("/api/alm/settings/priorities")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public PriorityResponse createPriority(@RequestBody PriorityRequest request) { return PriorityResponse.from(registry.createPriority(request)); }

    @Tag(name = "Priorities")
    @Operation(summary = "우선순위를 수정한다")
    @PutMapping("/api/alm/settings/priorities/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public PriorityResponse updatePriority(@PathVariable String id, @RequestBody PriorityRequest request) { return PriorityResponse.from(registry.updatePriority(id, request)); }

    @Tag(name = "Priorities")
    @Operation(summary = "우선순위 순서를 옮긴다")
    @PostMapping("/api/alm/settings/priorities/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void movePriority(@PathVariable String id, @RequestBody MoveRequest request) { registry.movePriority(id, request.delta()); }

    @Tag(name = "Priorities")
    @Operation(summary = "우선순위를 삭제한다")
    @DeleteMapping("/api/alm/settings/priorities/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePriority(@PathVariable String id) { registry.deletePriority(id); }

    @NoOrgDependency
    @Tag(name = "Issue Types")
    @Operation(summary = "이슈 타입 목록을 조회한다")
    @GetMapping("/api/alm/settings/issue-types")
    public List<IssueTypeResponse> issueTypes() { return registry.issueTypes().stream().map(IssueTypeResponse::from).toList(); }

    @NoOrgDependency
    @Tag(name = "Issue Types")
    @Operation(summary = "이슈 타입별로 쓰이는 이슈 수를 조회한다")
    @GetMapping("/api/alm/settings/issue-types/usage")
    public Map<String, Long> issueTypeUsage() { return registry.issueTypeUsage(); }

    @Tag(name = "Issue Types")
    @Operation(summary = "이슈 타입을 만든다")
    @PostMapping("/api/alm/settings/issue-types")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueTypeResponse createIssueType(@RequestBody IssueTypeRequest request) { return IssueTypeResponse.from(registry.createIssueType(request)); }

    @Tag(name = "Issue Types")
    @Operation(summary = "이슈 타입을 수정한다")
    @PutMapping("/api/alm/settings/issue-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public IssueTypeResponse updateIssueType(@PathVariable String id, @RequestBody IssueTypeRequest request) { return IssueTypeResponse.from(registry.updateIssueType(id, request)); }

    @Tag(name = "Issue Types")
    @Operation(summary = "이슈 타입 순서를 옮긴다")
    @PostMapping("/api/alm/settings/issue-types/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveIssueType(@PathVariable String id, @RequestBody MoveRequest request) { registry.moveIssueType(id, request.delta()); }

    @Tag(name = "Issue Types")
    @Operation(summary = "이슈 타입을 삭제한다")
    @DeleteMapping("/api/alm/settings/issue-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueType(@PathVariable String id) { registry.deleteIssueType(id); }

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
    public Map<String, Long> schemeProjects(@PathVariable String id) { return Map.of("count", schemes.countProjects(id)); }

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
    public SchemeResponse updateScheme(@PathVariable String id, @RequestBody SchemeUpdateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.update(userId(jwt), id, request.name(), request.body());
    }

    @Tag(name = "Settings Schemes")
    @Operation(summary = "설정 스킴을 삭제한다")
    @DeleteMapping("/api/alm/settings/schemes/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScheme(@PathVariable String id) { schemes.delete(id); }

    @Tag(name = "Settings Schemes")
    @Operation(summary = "기본 설정 스킴을 지정한다")
    @PostMapping("/api/alm/settings/schemes/{id}/default")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefault(@PathVariable String id) { schemes.setDefault(id); }

    // ── 프로젝트 설정 ──
    public record AssignRequest(String schemeId) {}
    public record CustomRequest(boolean custom) {}

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트에 실제로 적용된 설정을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/settings")
    public ResolvedSettingsResponse resolve(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return schemes.resolve(userId(jwt), projectId);
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트에 설정 스킴을 지정한다")
    @PutMapping("/api/alm/projects/{projectId}/settings/scheme")
    public ResolvedSettingsResponse assign(@PathVariable long projectId, @RequestBody AssignRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.assignScheme(userId(jwt), projectId, request.schemeId());
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트 설정 재정의를 켜고 끈다")
    @PutMapping("/api/alm/projects/{projectId}/settings/custom")
    public ResolvedSettingsResponse custom(@PathVariable long projectId, @RequestBody CustomRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.setCustom(userId(jwt), projectId, request.custom());
    }

    @Tag(name = "Project Settings")
    @Operation(summary = "프로젝트 설정 재정의 내용을 저장한다")
    @PutMapping("/api/alm/projects/{projectId}/settings/custom-body")
    public ResolvedSettingsResponse updateCustom(@PathVariable long projectId, @RequestBody SettingsBody body, @AuthenticationPrincipal Jwt jwt) {
        return schemes.updateCustom(userId(jwt), projectId, body);
    }
}
