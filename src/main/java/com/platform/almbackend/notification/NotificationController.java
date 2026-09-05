package com.platform.almbackend.notification;

import com.platform.almbackend.notification.dto.NotificationResponse;
import com.platform.almbackend.notification.dto.WatchersResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.platform.almbackend.config.NoOrgDependency;

@RestController
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "알림함과 이슈 관심 등록")
public class NotificationController {
    private final NotificationService service;

    /** 내 알림 — 최신순 100건 */
    @NoOrgDependency
    @Operation(summary = "내 알림을 최신순으로 조회한다")
    @GetMapping("/api/alm/notifications")
    public List<NotificationResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(userId(jwt), jwt.getClaimAsString("email"));
    }

    @NoOrgDependency
    @Operation(summary = "알림 하나를 읽음 처리한다")
    @PostMapping("/api/alm/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.markRead(userId(jwt), id);
    }

    @NoOrgDependency
    @Operation(summary = "내 알림을 모두 읽음 처리한다")
    @PostMapping("/api/alm/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(userId(jwt));
    }

    @Operation(summary = "이슈의 관심 등록자를 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/watchers")
    public WatchersResponse watchers(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.watchers(userId(jwt), issueId);
    }

    /** 관심 등록 — 멱등 */
    @Operation(summary = "이슈를 관심 등록한다")
    @PutMapping("/api/alm/issues/{issueId}/watchers/me")
    public WatchersResponse watch(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.watch(userId(jwt), issueId);
    }

    @Operation(summary = "이슈 관심 등록을 해제한다")
    @DeleteMapping("/api/alm/issues/{issueId}/watchers/me")
    public WatchersResponse unwatch(@PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.unwatch(userId(jwt), issueId);
    }
}
