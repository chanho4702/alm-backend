package com.platform.almbackend.project;

import com.platform.almbackend.project.dto.ProjectCreateRequest;
import com.platform.almbackend.project.dto.ProjectResponse;
import com.platform.almbackend.project.dto.ProjectUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/alm/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "프로젝트 생성·수정·보관·휴지통")
public class ProjectController {
    private final ProjectService projects;

    @Operation(summary = "접근 가능한 프로젝트를 조회한다")
    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return projects.listAccessible(userId(jwt));
    }

    @Operation(summary = "프로젝트 하나를 조회한다")
    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.get(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트를 만든다 — 키는 만든 뒤 바꿀 수 없다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(
            @Valid @RequestBody ProjectCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return projects.create(userId(jwt), request);
    }

    @Operation(summary = "프로젝트를 수정한다 — expectedVersion이 어긋나면 409")
    @PutMapping("/{projectId}")
    public ProjectResponse update(
            @PathVariable long projectId,
            @Valid @RequestBody ProjectUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return projects.update(userId(jwt), projectId, request);
    }

    @Operation(summary = "휴지통의 프로젝트를 조회한다")
    @GetMapping("/trash")
    public List<ProjectResponse> trash(@AuthenticationPrincipal Jwt jwt) {
        return projects.listTrash(userId(jwt));
    }

    @Operation(summary = "프로젝트를 보관한다")
    @PostMapping("/{projectId}/archive")
    public ProjectResponse archive(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.archive(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트 보관을 해제한다")
    @PostMapping("/{projectId}/unarchive")
    public ProjectResponse unarchive(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.unarchive(userId(jwt), projectId);
    }

    @Operation(summary = "휴지통의 프로젝트를 되돌린다")
    @PostMapping("/{projectId}/restore")
    public ProjectResponse restore(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.restoreFromTrash(userId(jwt), projectId);
    }

    @Operation(summary = "휴지통의 프로젝트를 영구 삭제한다")
    @DeleteMapping("/{projectId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        projects.purge(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트를 휴지통으로 옮긴다")
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        projects.delete(userId(jwt), projectId);
    }

    private static long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("JWT sub는 숫자 사용자 ID여야 합니다", e); }
    }
}

