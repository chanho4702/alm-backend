package com.platform.almbackend.automation;

import com.platform.almbackend.automation.AutomationRuleService.RuleRequest;
import com.platform.almbackend.automation.AutomationRuleService.RuleResponse;
import com.platform.almbackend.automation.AutomationRuleService.RuleUpdateRequest;
import com.platform.almbackend.automation.AutomationRuleService.RunResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/** 자동화 규칙(V36·V37) — 프로젝트 ADMIN 전용 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Automation", description = "프로젝트 자동화 규칙(트리거 → 조건 → 액션)과 실행 이력")
public class AutomationController {
    private final AutomationRuleService automation;

    @Operation(summary = "프로젝트의 자동화 규칙을 조회한다", description = "프로젝트 ADMIN. 만든 순서.")
    @GetMapping("/api/alm/projects/{projectId}/automation/rules")
    public List<RuleResponse> list(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return automation.list(userId(jwt), projectId);
    }

    @Operation(summary = "자동화 규칙을 만든다",
            description = "프로젝트 ADMIN. 저장한 사람이 실행 주체가 된다. 트리거·액션은 닫힌 어휘라 모르는 값은 400, 조건 AQL은 문법 검사(400 `{error, position, expected}`).")
    @PostMapping("/api/alm/projects/{projectId}/automation/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleResponse create(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                               @RequestBody RuleRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return automation.create(userId(jwt), projectId, request);
    }

    @Operation(summary = "자동화 규칙 하나를 조회한다")
    @GetMapping("/api/alm/automation/rules/{ruleId}")
    public RuleResponse get(@Parameter(description = "규칙 ID") @PathVariable long ruleId, @AuthenticationPrincipal Jwt jwt) {
        return automation.get(userId(jwt), ruleId);
    }

    @Operation(summary = "자동화 규칙을 고친다 — expectedVersion이 어긋나면 409",
            description = "통째로 바꾼다. 켜서 저장하면 자동 비활성 기록(이유·연속 실패)이 지워진다.")
    @PutMapping("/api/alm/automation/rules/{ruleId}")
    public RuleResponse update(@Parameter(description = "규칙 ID") @PathVariable long ruleId,
                               @Valid @RequestBody RuleUpdateRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return automation.update(userId(jwt), ruleId, request);
    }

    @Operation(summary = "자동화 규칙을 지운다", description = "실행 이력도 함께 지워진다.")
    @DeleteMapping("/api/alm/automation/rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "규칙 ID") @PathVariable long ruleId, @AuthenticationPrincipal Jwt jwt) {
        automation.delete(userId(jwt), ruleId);
    }

    @Operation(summary = "규칙의 최근 실행 이력을 조회한다", description = "최신 순, limit 기본 50·최대 200. 보존 30일.")
    @GetMapping("/api/alm/automation/rules/{ruleId}/runs")
    public List<RunResponse> runs(@Parameter(description = "규칙 ID") @PathVariable long ruleId,
                                  @Parameter(description = "최대 건수(1~200)") @RequestParam(required = false) Integer limit,
                                  @AuthenticationPrincipal Jwt jwt) {
        return automation.runs(userId(jwt), ruleId, limit);
    }

    @Operation(summary = "프로젝트의 최근 자동화 실행 이력(감사 로그)을 조회한다", description = "최신 순, limit 기본 50·최대 200.")
    @GetMapping("/api/alm/projects/{projectId}/automation/runs")
    public List<RunResponse> projectRuns(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                         @Parameter(description = "최대 건수(1~200)") @RequestParam(required = false) Integer limit,
                                         @AuthenticationPrincipal Jwt jwt) {
        return automation.projectRuns(userId(jwt), projectId, limit);
    }
}
