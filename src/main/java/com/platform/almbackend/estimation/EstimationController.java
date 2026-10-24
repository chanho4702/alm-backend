package com.platform.almbackend.estimation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.almbackend.issue.IssueController.userId;

@RestController
@RequiredArgsConstructor
@Tag(name = "Estimation", description = "스토리 포인트·예상 시간 합계와 속도 리포트")
public class EstimationController {
    private final EstimationService estimation;

    @Operation(summary = "백로그·스프린트별 추정 합계를 조회한다",
            description = "진행 중·예정 스프린트(번호 순) + 백로그. 포인트·예상 시간·이슈 수와 완료분을 함께 준다. 트리아지 대기열은 빠진다.")
    @GetMapping("/api/alm/projects/{projectId}/estimation/summary")
    public EstimationService.Summary summary(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                             @AuthenticationPrincipal Jwt jwt) {
        return estimation.summary(userId(jwt), projectId);
    }

    @Operation(summary = "완료 스프린트의 속도(velocity)를 조회한다",
            description = "unit = points(기본)·hours·count. committed = 완료 시점 범위(완료 + 그 순간 이월된 이슈), "
                    + "completed = 완료분. 값은 지금의 추정치로 센다(추정 변경 이력은 남기지 않는다). limit 기본 7, 최대 20.")
    @GetMapping("/api/alm/projects/{projectId}/reports/velocity")
    public EstimationService.Velocity velocity(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Parameter(description = "추정 단위 points·hours·count") @RequestParam(required = false) String unit,
            @Parameter(description = "최근 완료 스프린트 몇 개(1~20)") @RequestParam(required = false) Integer limit,
            @AuthenticationPrincipal Jwt jwt) {
        return estimation.velocity(userId(jwt), projectId, unit, limit);
    }
}
