package com.platform.almbackend.version;

import com.platform.almbackend.version.dto.VersionCreateRequest;
import com.platform.almbackend.version.dto.VersionReleaseRequest;
import com.platform.almbackend.version.dto.VersionResponse;
import com.platform.almbackend.version.dto.VersionUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

@RestController
@RequiredArgsConstructor
public class VersionController {
    private final VersionService versions;

    @GetMapping("/api/alm/projects/{projectId}/versions")
    public List<VersionResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return versions.list(userId(jwt), projectId);
    }

    @PostMapping("/api/alm/projects/{projectId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(
            @PathVariable long projectId,
            @Valid @RequestBody VersionCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return versions.create(userId(jwt), projectId, request);
    }

    @PutMapping("/api/alm/versions/{versionId}")
    public VersionResponse update(
            @PathVariable long versionId,
            @Valid @RequestBody VersionUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return versions.update(userId(jwt), versionId, request);
    }

    @PostMapping("/api/alm/versions/{versionId}/release")
    public VersionResponse release(
            @PathVariable long versionId,
            @Valid @RequestBody(required = false) VersionReleaseRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return versions.release(userId(jwt), versionId, request);
    }

    @PostMapping("/api/alm/versions/{versionId}/archive")
    public VersionResponse archive(@PathVariable long versionId, @AuthenticationPrincipal Jwt jwt) {
        return versions.archive(userId(jwt), versionId);
    }

    @DeleteMapping("/api/alm/versions/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long versionId, @AuthenticationPrincipal Jwt jwt) {
        versions.delete(userId(jwt), versionId);
    }
}
