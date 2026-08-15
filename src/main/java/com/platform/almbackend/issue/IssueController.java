package com.platform.almbackend.issue;

import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class IssueController {
    private final IssueService issues;

    @GetMapping("/api/alm/projects/{projectId}/issues")
    public List<IssueResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return issues.list(userId(jwt), projectId);
    }

    @PostMapping("/api/alm/projects/{projectId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueResponse create(
            @PathVariable long projectId,
            @Valid @RequestBody IssueCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.create(userId(jwt), projectId, request);
    }

    @GetMapping("/api/alm/issues/{issueId}")
    public IssueResponse get(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return issues.get(userId(jwt), issueId);
    }

    @PutMapping("/api/alm/issues/{issueId}")
    public IssueResponse update(
            @PathVariable long issueId,
            @Valid @RequestBody IssueUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return issues.update(userId(jwt), issueId, request);
    }

    @DeleteMapping("/api/alm/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        issues.delete(userId(jwt), issueId);
    }

    private static long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("JWT sub는 숫자 사용자 ID여야 합니다", e); }
    }
}

