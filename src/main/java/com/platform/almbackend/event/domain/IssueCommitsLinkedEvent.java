package com.platform.almbackend.event.domain;

import java.time.Instant;

/**
 * 커밋이 이슈에 <b>새로</b> 이어졌다(V41 커밋 연결) — 이미 이어져 있던 커밋을 다시 보낸 것은 내지 않는다. 이슈 필드가 바뀐 것이
 * 아니라 {@link IssueUpdatedEvent}가 아니다: 자동화는 {@code COMMIT_LINKED} 트리거로, 실시간 스트림은
 * {@code issue.updated changedFields:["development"]}로 받는다. version은 오르지 않는다.
 *
 * @param commits 이번에 새로 이어진 커밋 수
 */
public record IssueCommitsLinkedEvent(
        long issueId,
        long projectId,
        String issueKey,
        int commits,
        long actorId,
        Instant at,
        long version) implements IssueDomainEvent {}
