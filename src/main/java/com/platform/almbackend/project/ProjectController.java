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

@RestController
@RequestMapping("/api/alm/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projects;

    @GetMapping
    public List<ProjectResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return projects.listAccessible(userId(jwt));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.get(userId(jwt), projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(
            @Valid @RequestBody ProjectCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return projects.create(userId(jwt), request);
    }

    @PutMapping("/{projectId}")
    public ProjectResponse update(
            @PathVariable long projectId,
            @Valid @RequestBody ProjectUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return projects.update(userId(jwt), projectId, request);
    }

    @GetMapping("/trash")
    public List<ProjectResponse> trash(@AuthenticationPrincipal Jwt jwt) {
        return projects.listTrash(userId(jwt));
    }

    @PostMapping("/{projectId}/archive")
    public ProjectResponse archive(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.archive(userId(jwt), projectId);
    }

    @PostMapping("/{projectId}/unarchive")
    public ProjectResponse unarchive(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.unarchive(userId(jwt), projectId);
    }

    @PostMapping("/{projectId}/restore")
    public ProjectResponse restore(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return projects.restoreFromTrash(userId(jwt), projectId);
    }

    @DeleteMapping("/{projectId}/permanent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        projects.purge(userId(jwt), projectId);
    }

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

