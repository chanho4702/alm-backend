package com.platform.almbackend.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 완료 처리. 완료 판정은 서버가 프로젝트 워크플로의 complete 의미 상태로 한다. {@code doneStatuses}는
 * 옛 클라이언트 호환으로 받기만 하고 쓰지 않는다(deprecated, 2026-10-18 감사 A-18). 완료가 아닌 상태의 이슈는
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
        Long moveUnfinishedToSprintId,
        /**
         * 스프린트 id 대신 이관 방향(V39 사이클): {@code backlog} · {@code next}(다음 예정 스프린트 — 없고 자동 생성이 켜져
         * 있으면 만들어서). 둘 다 생략하면 프로젝트 계획 설정의 {@code defaultCarryOver}(기본 backlog — 옛 동작). id가 있으면 id가 이긴다.
         */
        @jakarta.validation.constraints.Pattern(regexp = "backlog|next", message = "moveUnfinishedTo는 backlog·next 중 하나여야 합니다")
        String moveUnfinishedTo
) {}
