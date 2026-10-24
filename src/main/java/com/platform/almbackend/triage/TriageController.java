package com.platform.almbackend.triage;

import com.platform.almbackend.issue.IssueTriageService;
import com.platform.almbackend.issue.IssueTriageService.AcceptRequest;
import com.platform.almbackend.issue.IssueTriageService.DeclineRequest;
import com.platform.almbackend.issue.IssueTriageService.DuplicateRequest;
import com.platform.almbackend.issue.IssueTriageService.SnoozeRequest;
import com.platform.almbackend.issue.IssueTriageService.TriageSettings;
import com.platform.almbackend.issue.dto.IssueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/** 트리아지(V35) — 일반 워크플로 밖 대기열과 그 처리(수락·중복·거절·보류) */
@RestController
@RequiredArgsConstructor
@Tag(name = "Triage", description = "트리아지 대기열 — 새로 들어온 이슈를 수락·중복·거절·보류")
public class TriageController {
    private final IssueTriageService triage;

    @Schema(description = "트리아지 켜기·끄기")
    public record SettingsRequest(@Schema(example = "true") @NotNull(message = "enabled가 필요합니다") Boolean enabled) {}

    @Operation(summary = "프로젝트 트리아지 설정과 대기열 크기를 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/triage/settings")
    public TriageSettings settings(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return triage.settings(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트 트리아지를 켜거나 끈다",
            description = "프로젝트 ADMIN. 켜면 편집 권한 없는 열람자도 이슈를 올릴 수 있고 그 이슈는 대기열로 간다. 꺼도 남은 대기열은 처리할 수 있다.")
    @PutMapping("/api/alm/projects/{projectId}/triage/settings")
    public TriageSettings updateSettings(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                         @Valid @RequestBody SettingsRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return triage.updateSettings(userId(jwt), projectId, request.enabled());
    }

    @Operation(summary = "트리아지 대기열을 조회한다", description = "state = pending(기본, 보류가 풀린 것 포함)·snoozed·all. 들어온 순서.")
    @GetMapping("/api/alm/projects/{projectId}/triage")
    public List<IssueResponse> queue(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                     @Parameter(description = "pending·snoozed·all") @RequestParam(required = false) String state,
                                     @AuthenticationPrincipal Jwt jwt) {
        return triage.queue(userId(jwt), projectId, state);
    }

    @Operation(summary = "이슈를 트리아지 대기열로 보낸다", description = "EDIT. 트리아지를 켠 프로젝트의 완료되지 않은 이슈만. 스프린트에서 빠진다.")
    @PostMapping("/api/alm/issues/{issueId}/triage")
    public IssueResponse send(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return triage.send(userId(jwt), issueId);
    }

    @Operation(summary = "트리아지 이슈를 수락한다", description = "지정 상태(기본 첫 '할 일')로 백로그 끝에 들어간다. 완료 의미 상태로는 수락할 수 없다(400).")
    @PostMapping("/api/alm/issues/{issueId}/triage/accept")
    public IssueResponse accept(@Parameter(description = "이슈 ID") @PathVariable long issueId,
                                @RequestBody(required = false) AcceptRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return triage.accept(userId(jwt), issueId, request);
    }

    @Operation(summary = "트리아지 이슈를 중복으로 끝낸다", description = "\"중복\" 링크(이 이슈 → 원본, 원본 VIEW 필요) + 해결 DUPLICATE + 첫 완료 상태.")
    @PostMapping("/api/alm/issues/{issueId}/triage/duplicate")
    public IssueResponse duplicate(@Parameter(description = "이슈 ID") @PathVariable long issueId,
                                   @RequestBody DuplicateRequest request,
                                   @AuthenticationPrincipal Jwt jwt) {
        return triage.duplicate(userId(jwt), issueId, request);
    }

    @Operation(summary = "트리아지 이슈를 거절한다", description = "해결 WONT_DO(하지 않음) + 첫 완료 상태. 이유는 코멘트로 남는다.")
    @PostMapping("/api/alm/issues/{issueId}/triage/decline")
    public IssueResponse decline(@Parameter(description = "이슈 ID") @PathVariable long issueId,
                                 @RequestBody(required = false) DeclineRequest request,
                                 @AuthenticationPrincipal Jwt jwt) {
        return triage.decline(userId(jwt), issueId, request);
    }

    @Operation(summary = "트리아지 이슈를 보류한다", description = "until(미래, 최대 1년)까지 대기열에서 접어 둔다. 지나면 다시 대기로 보인다.")
    @PostMapping("/api/alm/issues/{issueId}/triage/snooze")
    public IssueResponse snooze(@Parameter(description = "이슈 ID") @PathVariable long issueId,
                                @RequestBody SnoozeRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return triage.snooze(userId(jwt), issueId, request);
    }
}
