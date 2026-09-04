package com.platform.almbackend.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 완료 처리. {@code doneStatuses}는 선택값 — 비우면 서버가 프로젝트 워크플로의 complete 의미 상태로
 * 판단한다(V11부터 서버가 상태 카테고리를 안다). 프론트 어댑터는 보내지 않는다. 여기 없는 상태의 이슈는
 * 백로그(또는 {@code moveUnfinishedToSprintId})로 되돌린다.
 */
public record SprintCompleteRequest(
        @Size(max = 100, message = "완료 상태는 최대 100개까지 지정할 수 있습니다")
        List<@NotBlank(message = "빈 상태 ID는 사용할 수 없습니다")
                @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다") String> doneStatuses,
        /**
         * 미완료 이슈를 옮길 스프린트. 생략하면 백로그로 되돌린다 — 지라와 같은 선택지다.
         * 같은 프로젝트의 끝나지 않은 다른 스프린트만 지정할 수 있다.
         */
        @Positive(message = "moveUnfinishedToSprintId는 양수여야 합니다")
        Long moveUnfinishedToSprintId
) {}
