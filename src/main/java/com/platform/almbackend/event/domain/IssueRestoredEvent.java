package com.platform.almbackend.event.domain;

import java.time.Instant;

/** 보관함에서 되돌린 이슈 — 다시 나타난다. 새 이슈가 아니라 자동화 ISSUE_CREATED를 깨우지 않는다 */
public record IssueRestoredEvent(
        long issueId,
        long projectId,
        String issueKey,
        long actorId,
        Instant at,
        long version) implements IssueDomainEvent {}
