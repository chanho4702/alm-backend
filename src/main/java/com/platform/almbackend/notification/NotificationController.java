package com.platform.almbackend.notification;

import com.platform.almbackend.notification.dto.NotificationBulkRequest;
import com.platform.almbackend.notification.dto.NotificationBulkResponse;
import com.platform.almbackend.notification.dto.NotificationInboxResponse;
import com.platform.almbackend.notification.dto.NotificationResponse;
import com.platform.almbackend.notification.dto.NotificationSnoozeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
import io.swagger.v3.oas.annotations.Parameter;

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
    public void markRead(@Parameter(description = "알림 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.markRead(userId(jwt), id);
    }

    @NoOrgDependency
    @Operation(summary = "내 알림을 모두 읽음 처리한다")
    @PostMapping("/api/alm/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(userId(jwt));
    }

    // ── 인박스(V40) ──

    @NoOrgDependency
    @Operation(summary = "알림 인박스를 커서로 넘겨 본다",
            description = "filter = all(기본)·unread·mentions·assigned·automation·snoozed·archived. 보관한 것과 지금 미뤄 둔 것은 "
                    + "all·unread·종류 필터에서 빠지고, 미루기 해제 시각이 지나면 그 시각 자리(위쪽)에 읽지 않은 알림으로 돌아온다.")
    @GetMapping("/api/alm/notifications/inbox")
    public NotificationInboxResponse inbox(
            @Parameter(description = "필터") @RequestParam(required = false) String filter,
            @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
            @Parameter(description = "쪽 크기(1~100, 기본 50)") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        return service.inbox(userId(jwt), filter, cursor, limit);
    }

    @NoOrgDependency
    @Operation(summary = "알림을 일괄 처리한다 — 읽음·읽지 않음·보관·보관 해제")
    @PostMapping("/api/alm/notifications/bulk")
    public NotificationBulkResponse bulk(@Valid @RequestBody NotificationBulkRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.bulk(userId(jwt), request);
    }

    @NoOrgDependency
    @Operation(summary = "알림을 미룬다 — 해제 시각에 읽지 않은 알림으로 다시 위에 뜬다")
    @PostMapping("/api/alm/notifications/{id}/snooze")
    public NotificationResponse snooze(@Parameter(description = "알림 ID") @PathVariable long id,
                                       @Valid @RequestBody NotificationSnoozeRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.snooze(userId(jwt), id, request.until());
    }

    @NoOrgDependency
    @Operation(summary = "알림 미루기를 취소한다")
    @DeleteMapping("/api/alm/notifications/{id}/snooze")
    public NotificationResponse unsnooze(@Parameter(description = "알림 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.unsnooze(userId(jwt), id);
    }

    @NoOrgDependency
    @Operation(summary = "알림을 보관한다(삭제 대신)")
    @PostMapping("/api/alm/notifications/{id}/archive")
    public NotificationResponse archive(@Parameter(description = "알림 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.archive(userId(jwt), id);
    }

    @NoOrgDependency
    @Operation(summary = "알림 보관을 해제한다")
    @PostMapping("/api/alm/notifications/{id}/unarchive")
    public NotificationResponse unarchive(@Parameter(description = "알림 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.unarchive(userId(jwt), id);
    }

    @Operation(summary = "이슈의 관심 등록자를 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/watchers")
    public WatchersResponse watchers(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.watchers(userId(jwt), issueId);
    }

    /** 관심 등록 — 멱등 */
    @Operation(summary = "이슈를 관심 등록한다")
    @PutMapping("/api/alm/issues/{issueId}/watchers/me")
    public WatchersResponse watch(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.watch(userId(jwt), issueId);
    }

    @Operation(summary = "이슈 관심 등록을 해제한다")
    @DeleteMapping("/api/alm/issues/{issueId}/watchers/me")
    public WatchersResponse unwatch(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.unwatch(userId(jwt), issueId);
    }
}
