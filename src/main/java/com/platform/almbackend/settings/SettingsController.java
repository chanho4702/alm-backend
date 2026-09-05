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
    @GetMapping("/api/alm/settings/categories")
    public List<CategoryResponse> categories() { return registry.categories().stream().map(CategoryResponse::from).toList(); }

    @PostMapping("/api/alm/settings/categories")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@RequestBody CategoryRequest request) { return CategoryResponse.from(registry.createCategory(request)); }

    @PutMapping("/api/alm/settings/categories/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public CategoryResponse updateCategory(@PathVariable String id, @RequestBody CategoryRequest request) { return CategoryResponse.from(registry.updateCategory(id, request)); }

    @PostMapping("/api/alm/settings/categories/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveCategory(@PathVariable String id, @RequestBody MoveRequest request) { registry.moveCategory(id, request.delta()); }

    @DeleteMapping("/api/alm/settings/categories/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable String id) { registry.deleteCategory(id); }

    // ── 상태 ──
    @GetMapping("/api/alm/settings/statuses")
    public List<StatusResponse> statuses() { return registry.statuses().stream().map(StatusResponse::from).toList(); }

    @GetMapping("/api/alm/settings/statuses/usage")
    public Map<String, Long> statusUsage() { return registry.statusUsage(); }

    @PostMapping("/api/alm/settings/statuses")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public StatusResponse createStatus(@RequestBody StatusRequest request) { return StatusResponse.from(registry.createStatus(request)); }

    @PutMapping("/api/alm/settings/statuses/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public StatusResponse updateStatus(@PathVariable String id, @RequestBody StatusRequest request) { return StatusResponse.from(registry.updateStatus(id, request)); }

    @DeleteMapping("/api/alm/settings/statuses/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStatus(@PathVariable String id) { registry.deleteStatus(id); }

    // ── 이슈 타입 ──
    @GetMapping("/api/alm/settings/link-types")
    public List<LinkTypeResponse> linkTypes() { return registry.linkTypes().stream().map(LinkTypeResponse::from).toList(); }

    @GetMapping("/api/alm/settings/link-types/usage")
    public Map<String, Long> linkTypeUsage() { return registry.linkTypeUsage(); }

    @PostMapping("/api/alm/settings/link-types")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public LinkTypeResponse createLinkType(@RequestBody LinkTypeRequest request) { return LinkTypeResponse.from(registry.createLinkType(request)); }

    @PutMapping("/api/alm/settings/link-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public LinkTypeResponse updateLinkType(@PathVariable String id, @RequestBody LinkTypeRequest request) { return LinkTypeResponse.from(registry.updateLinkType(id, request)); }

    @PostMapping("/api/alm/settings/link-types/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveLinkType(@PathVariable String id, @RequestBody MoveRequest request) { registry.moveLinkType(id, request.delta()); }

    @DeleteMapping("/api/alm/settings/link-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLinkType(@PathVariable String id) { registry.deleteLinkType(id); }

    @GetMapping("/api/alm/settings/priorities")
    public List<PriorityResponse> priorities() { return registry.priorities().stream().map(PriorityResponse::from).toList(); }

    @GetMapping("/api/alm/settings/priorities/usage")
    public Map<String, Long> priorityUsage() { return registry.priorityUsage(); }

    @PostMapping("/api/alm/settings/priorities")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public PriorityResponse createPriority(@RequestBody PriorityRequest request) { return PriorityResponse.from(registry.createPriority(request)); }

    @PutMapping("/api/alm/settings/priorities/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public PriorityResponse updatePriority(@PathVariable String id, @RequestBody PriorityRequest request) { return PriorityResponse.from(registry.updatePriority(id, request)); }

    @PostMapping("/api/alm/settings/priorities/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void movePriority(@PathVariable String id, @RequestBody MoveRequest request) { registry.movePriority(id, request.delta()); }

    @DeleteMapping("/api/alm/settings/priorities/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePriority(@PathVariable String id) { registry.deletePriority(id); }

    @GetMapping("/api/alm/settings/issue-types")
    public List<IssueTypeResponse> issueTypes() { return registry.issueTypes().stream().map(IssueTypeResponse::from).toList(); }

    @GetMapping("/api/alm/settings/issue-types/usage")
    public Map<String, Long> issueTypeUsage() { return registry.issueTypeUsage(); }

    @PostMapping("/api/alm/settings/issue-types")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueTypeResponse createIssueType(@RequestBody IssueTypeRequest request) { return IssueTypeResponse.from(registry.createIssueType(request)); }

    @PutMapping("/api/alm/settings/issue-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public IssueTypeResponse updateIssueType(@PathVariable String id, @RequestBody IssueTypeRequest request) { return IssueTypeResponse.from(registry.updateIssueType(id, request)); }

    @PostMapping("/api/alm/settings/issue-types/{id}/move")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveIssueType(@PathVariable String id, @RequestBody MoveRequest request) { registry.moveIssueType(id, request.delta()); }

    @DeleteMapping("/api/alm/settings/issue-types/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIssueType(@PathVariable String id) { registry.deleteIssueType(id); }

    // ── 스킴 ──
    public record SchemeCreateRequest(String name) {}
    public record SchemeUpdateRequest(String name, SettingsBody body) {}

    @GetMapping("/api/alm/settings/schemes")
    public List<SchemeResponse> listSchemes() { return schemes.list(); }

    @GetMapping("/api/alm/settings/schemes/{id}/projects/count")
    public Map<String, Long> schemeProjects(@PathVariable String id) { return Map.of("count", schemes.countProjects(id)); }

    @PostMapping("/api/alm/settings/schemes")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.CREATED)
    public SchemeResponse createScheme(@RequestBody SchemeCreateRequest request) { return schemes.create(request.name()); }

    @PutMapping("/api/alm/settings/schemes/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public SchemeResponse updateScheme(@PathVariable String id, @RequestBody SchemeUpdateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.update(userId(jwt), id, request.name(), request.body());
    }

    @DeleteMapping("/api/alm/settings/schemes/{id}")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteScheme(@PathVariable String id) { schemes.delete(id); }

    @PostMapping("/api/alm/settings/schemes/{id}/default")
    @PreAuthorize("@globalAdmin.check(authentication)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefault(@PathVariable String id) { schemes.setDefault(id); }

    // ── 프로젝트 설정 ──
    public record AssignRequest(String schemeId) {}
    public record CustomRequest(boolean custom) {}

    @GetMapping("/api/alm/projects/{projectId}/settings")
    public ResolvedSettingsResponse resolve(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return schemes.resolve(userId(jwt), projectId);
    }

    @PutMapping("/api/alm/projects/{projectId}/settings/scheme")
    public ResolvedSettingsResponse assign(@PathVariable long projectId, @RequestBody AssignRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.assignScheme(userId(jwt), projectId, request.schemeId());
    }

    @PutMapping("/api/alm/projects/{projectId}/settings/custom")
    public ResolvedSettingsResponse custom(@PathVariable long projectId, @RequestBody CustomRequest request, @AuthenticationPrincipal Jwt jwt) {
        return schemes.setCustom(userId(jwt), projectId, request.custom());
    }

    @PutMapping("/api/alm/projects/{projectId}/settings/custom-body")
    public ResolvedSettingsResponse updateCustom(@PathVariable long projectId, @RequestBody SettingsBody body, @AuthenticationPrincipal Jwt jwt) {
        return schemes.updateCustom(userId(jwt), projectId, body);
    }
}
