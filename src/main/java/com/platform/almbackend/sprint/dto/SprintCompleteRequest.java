package com.platform.almbackend.sprint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 완료 처리에서 "무엇을 완료로 볼지"는 클라이언트가 알려준다 — 상태 카테고리를 정하는 워크플로
 * 스킴이 아직 프론트 소유이기 때문이다. 여기 없는 상태의 이슈는 백로그로 되돌린다.
 * 스킴이 서버로 넘어오면 이 필드는 선택값이 되고 서버 판단이 우선한다.
 */
public record SprintCompleteRequest(
        @Size(max = 100, message = "완료 상태는 최대 100개까지 지정할 수 있습니다")
        List<@NotBlank(message = "빈 상태 ID는 사용할 수 없습니다")
                @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다") String> doneStatuses,
        /**
         * 미완료 이슈를 옮길 스프린트. 생략하면 백로그로 되돌린다 — 지라와 같은 선택지다.
         * 같은 프로젝트의 끝나지 않은 다른 스프린트만 지정할 수 있다.
         */
        Long moveUnfinishedToSprintId
) {}
