package com.platform.almbackend.event.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 이슈 필드 변경 — 바뀐 필드가 하나라도 있을 때만 발행한다. 상태가 바뀌었으면 {@link IssueTransitionedEvent}가
 * <b>함께</b> 발행되고 여기의 {@code changedFields}에도 {@code "status"}가 들어 있다.
 *
 * <p>{@code changedFields} 어휘(필드 구성 id와 같다): title · description · type · status · priority · assignee ·
 * parent · sprint · dueDate · estimate · resolution · fixVersion · labels · components.
 * 보드 안 순서 변경(sortOrder)만 있는 이동은 필드 변경이 아니라 발행하지 않는다.
 */
public record IssueUpdatedEvent(
        long issueId,
        long projectId,
        String issueKey,
        Set<String> changedFields,
        long actorId,
        Instant at) implements IssueDomainEvent {
    public IssueUpdatedEvent {
        changedFields = Set.copyOf(changedFields);
    }
}
