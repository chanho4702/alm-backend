package com.platform.almbackend.issue.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.platform.almbackend.domain.IssueResolution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * V2 확장 필드. 바깥 요청에서 details 자체가 없으면 기존 V1 클라이언트로 보고 값을 보존한다.
 * details가 있으면 nullable 필드는 null로 명시적 해제할 수 있다.
 */
@Schema(description = """
        이슈의 세부 항목. 바깥 요청에서 details 자체를 생략하면 아래 값을 모두 보존하고, \
        details를 주면 null인 필드는 명시적 해제로 본다.""")
public record IssueDetailsRequest(
        @Schema(description = "상위 이슈 ID. 에픽→일반 이슈→하위 작업 2단계까지만 허용한다", example = "1000")
        @Positive(message = "parentId는 양수여야 합니다")
        Long parentId,
        @Schema(description = "옮겨 놓을 스프린트 ID. null이면 백로그", example = "12")
        @Positive(message = "sprintId는 양수여야 합니다")
        Long sprintId,
        @Schema(description = "마감일", example = "2026-09-30")
        LocalDate dueDate,
        @Schema(description = "예상 소요 시간(시간 단위, 소수 둘째 자리까지)", example = "3.50")
        @DecimalMin(value = "0.01", message = "예상 시간은 0보다 커야 합니다")
        @Digits(integer = 8, fraction = 2, message = "예상 시간은 소수 둘째 자리까지 입력하세요")
        BigDecimal estimateHours,
        /** 완료 사유. 정의된 값만 받고(그 외 400), null이면 해제 */
        @Schema(description = "완료 사유. 정의된 값만 받고 null이면 해제한다")
        IssueResolution resolution,
        /** 수정 버전. null이면 해제. 같은 프로젝트의 보관되지 않은 버전만 */
        @Schema(description = "수정 버전 ID. 같은 프로젝트의 보관되지 않은 버전만 지정할 수 있고 null이면 해제", example = "5")
        @Positive(message = "fixVersionId는 양수여야 합니다")
        Long fixVersionId,
        @Schema(description = "라벨. 통째로 교체한다", example = "[\"regression\", \"frontend\"]")
        @Size(max = 50, message = "라벨은 최대 50개까지 지정할 수 있습니다")
        List<@NotBlank(message = "빈 라벨은 사용할 수 없습니다")
                @Size(max = 80, message = "라벨은 80자 이하여야 합니다") String> labels,
        /** 컴포넌트 id 목록. 수정 시 null이면 그대로, 빈 배열이면 전부 해제 */
        @Schema(description = "컴포넌트 ID 목록. null이면 그대로 두고 빈 배열이면 전부 해제한다", example = "[3]")
        List<@Positive(message = "componentId는 양수여야 합니다") Long> componentIds
) {}
