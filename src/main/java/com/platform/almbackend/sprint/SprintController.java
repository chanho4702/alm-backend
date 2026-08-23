package com.platform.almbackend.sprint;

import com.platform.almbackend.sprint.dto.SprintCompleteRequest;
import com.platform.almbackend.sprint.dto.SprintCreateRequest;
import com.platform.almbackend.sprint.dto.SprintResponse;
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
public class SprintController {
    private final SprintService sprints;

    @GetMapping("/api/alm/projects/{projectId}/sprints")
    public List<SprintResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return sprints.list(userId(jwt), projectId);
    }

    @PostMapping("/api/alm/projects/{projectId}/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    public SprintResponse create(
            @PathVariable long projectId,
            @Valid @RequestBody(required = false) SprintCreateRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return sprints.create(userId(jwt), projectId, request);
    }

    @PostMapping("/api/alm/sprints/{sprintId}/start")
    public SprintResponse start(@PathVariable long sprintId, @AuthenticationPrincipal Jwt jwt) {
        return sprints.start(userId(jwt), sprintId);
    }

    @PostMapping("/api/alm/sprints/{sprintId}/complete")
    public SprintResponse complete(
            @PathVariable long sprintId,
            @Valid @RequestBody(required = false) SprintCompleteRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return sprints.complete(userId(jwt), sprintId, request);
    }
}
