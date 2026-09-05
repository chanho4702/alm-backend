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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequiredArgsConstructor
@Tag(name = "Versions", description = "릴리스 버전 관리와 배포 표시")
public class VersionController {
    private final VersionService versions;

    @Operation(summary = "프로젝트의 버전을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/versions")
    public List<VersionResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return versions.list(userId(jwt), projectId);
    }

    @Operation(summary = "버전을 만든다")
    @PostMapping("/api/alm/projects/{projectId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(
            @PathVariable long projectId,
            @Valid @RequestBody VersionCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return versions.create(userId(jwt), projectId, request);
    }

    @Operation(summary = "버전을 수정한다 — expectedVersion이 어긋나면 409")
    @PutMapping("/api/alm/versions/{versionId}")
    public VersionResponse update(
            @PathVariable long versionId,
            @Valid @RequestBody VersionUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return versions.update(userId(jwt), versionId, request);
    }

    @Operation(summary = "버전을 릴리스로 표시하고 미완료 이슈를 옮긴다")
    @PostMapping("/api/alm/versions/{versionId}/release")
    public VersionResponse release(
            @PathVariable long versionId,
            @Valid @RequestBody(required = false) VersionReleaseRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return versions.release(userId(jwt), versionId, request);
    }

    @Operation(summary = "버전을 보관한다")
    @PostMapping("/api/alm/versions/{versionId}/archive")
    public VersionResponse archive(@PathVariable long versionId, @AuthenticationPrincipal Jwt jwt) {
        return versions.archive(userId(jwt), versionId);
    }

    @Operation(summary = "버전을 삭제한다")
    @DeleteMapping("/api/alm/versions/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long versionId, @AuthenticationPrincipal Jwt jwt) {
        versions.delete(userId(jwt), versionId);
    }
}
