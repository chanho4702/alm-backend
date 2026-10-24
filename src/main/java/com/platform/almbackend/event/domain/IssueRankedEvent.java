package com.platform.almbackend.event.domain;

import java.time.Instant;

/**
 * 순서만 바뀐 이동(보드 같은 열 안 재정렬·같은 스프린트/백로그 안 랭크) — 필드가 바뀌지 않아 {@link IssueUpdatedEvent}가 없는 경우에만
 * 발행한다. 랭크 그룹(프로젝트 + {@code sprintId}, null=백로그) 전체가 다시 매겨졌다는 뜻이다. 자동화 트리거가 아니다.
 */
public record IssueRankedEvent(
        long issueId,
        long projectId,
        String issueKey,
        Long sprintId,
        long actorId,
        Instant at,
        long version) implements IssueDomainEvent {}
