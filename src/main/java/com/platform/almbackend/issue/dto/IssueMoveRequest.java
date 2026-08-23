package com.platform.almbackend.issue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 보드 컬럼 이동. 대상 그룹은 같은 프로젝트·같은 스프린트의 `status` 컬럼이며,
 * `beforeId` 앞(없거나 그룹에 없으면 맨 뒤)에 놓고 컬럼 전체 순서를 1..n으로 다시 매긴다.
 */
public record IssueMoveRequest(
        @NotBlank(message = "상태 ID가 필요합니다")
        @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다")
        String status,
        @Positive(message = "beforeId는 양수여야 합니다")
        Long beforeId
) {}
