package com.platform.almbackend.event.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 이슈 필드 변경 — 바뀐 필드가 하나라도 있을 때만 발행한다. 상태가 바뀌었으면 {@link IssueTransitionedEvent}가
 * <b>함께</b> 발행되고 여기의 {@code changedFields}에도 {@code "status"}가 들어 있다.
 *
 * <p>{@code changedFields} 어휘(필드 구성 id와 같다): title · description · type · status · priority · assignee ·
 * parent · sprint · dueDate · estimate · resolution · fixVersion · labels · components · startDate · storyPoints ·
 * flagged · triage(V30·V35). 전체 목록은 {@link IssueDomainEventPublisher#CHANGED_FIELDS}.
 * 보드 안 순서 변경(sortOrder)만 있는 이동은 필드 변경이 아니라 발행하지 않는다.
 */
public record IssueUpdatedEvent(
        long issueId,
        long projectId,
        String issueKey,
        Set<String> changedFields,
        long actorId,
        Instant at,
        /** 변경 뒤 이슈 version — 플래그·보드 이동처럼 version을 올리지 않는 변경이면 그대로다(실시간 신호용, 2026-10-24) */
        long version) implements IssueDomainEvent {
    public IssueUpdatedEvent {
        changedFields = Set.copyOf(changedFields);
    }
}
