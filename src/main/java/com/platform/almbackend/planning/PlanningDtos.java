package com.platform.almbackend.planning;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 프로젝트 계획 설정(V39) 요청·응답 */
public final class PlanningDtos {
    private PlanningDtos() {}

    @Schema(description = "사이클(스프린트 자동화)")
    public record SprintAutomation(
            @Schema(description = "스프린트를 완료할 때 다음(예정) 스프린트가 없으면 만든다", example = "true")
            Boolean autoCreateNext,
            @Schema(description = "자동 생성 스프린트 길이(일, 1~90)", example = "14")
            Integer lengthDays,
            @Schema(description = "완료일과 다음 스프린트 시작일 사이 쉬는 날(0~30)", example = "0")
            Integer cooldownDays,
            @Schema(description = "완료 요청이 이관 대상을 지정하지 않았을 때 미완료 이슈가 갈 곳: next · backlog", example = "next")
            String defaultCarryOver) {}

    @Schema(description = "프로젝트 계획 설정 수정 — 보낸 항목만 바뀐다(sprintAutomation 안도 키별로)")
    public record UpdateRequest(
            @Valid SprintAutomation sprintAutomation,
            @Schema(description = "fibonacci · tshirt · linear · custom", example = "tshirt")
            String estimationScale,
            @Schema(description = "custom 척도 값(1~20개, 0~9999, 소수 첫째 자리, 중복 불가) — 오름차순으로 저장한다")
            List<BigDecimal> customValues,
            @Schema(description = "T셔츠 크기 → 포인트(XS·S·M·L·XL 다섯 개 전부, 크기 순으로 커져야 한다)")
            Map<String, BigDecimal> tshirtPoints,
            @Schema(description = "읽은 설정의 version(처음이면 0). 어긋나면 409", example = "0")
            @NotNull(message = "expectedVersion이 필요합니다")
            Integer expectedVersion) {}

    @Schema(description = "프로젝트 계획 설정")
    public record Response(
            long projectId,
            SprintAutomation sprintAutomation,
            String estimationScale,
            @Schema(description = "저장된 custom 값(다른 척도로 바꿔도 남아 있어 되돌리면 그대로다)")
            List<BigDecimal> customValues,
            @Schema(description = "T셔츠 매핑(척도가 tshirt가 아니어도 늘 다섯 개 전부)")
            Map<String, BigDecimal> tshirtPoints,
            @Schema(description = "고를 수 있는 포인트(오름차순). null이면 제한 없음(linear)")
            List<BigDecimal> allowedPoints,
            @Schema(description = "낙관적 락 버전. 행이 없으면 0", example = "1")
            int version) {}
}
