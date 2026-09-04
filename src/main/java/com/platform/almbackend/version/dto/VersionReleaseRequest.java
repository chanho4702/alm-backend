package com.platform.almbackend.version.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 릴리스 처리. 스프린트 완료와 같은 규칙 — {@code doneStatuses}는 선택값이고 비우면 서버가 워크플로의
 * complete 의미로 판단한다. 미완료 이슈는 지정한 버전으로 옮기고, 지정이 없으면 그 버전에 그대로 둔다(지라와 동일).
 */
public record VersionReleaseRequest(
        @Size(max = 100, message = "완료 상태는 최대 100개까지 지정할 수 있습니다")
        List<@NotBlank(message = "빈 상태 ID는 사용할 수 없습니다")
                @Size(max = 80, message = "상태 ID는 80자 이하여야 합니다") String> doneStatuses,
        @Positive(message = "moveUnresolvedToVersionId는 양수여야 합니다")
        Long moveUnresolvedToVersionId
) {}
