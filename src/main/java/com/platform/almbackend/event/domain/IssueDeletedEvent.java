package com.platform.almbackend.event.domain;

import java.time.Instant;

/** 이슈 삭제(또는 보관함으로 이동 — {@code archived}) — 커밋 뒤 화면에서 사라진다. 자동화 트리거가 아니다 */
public record IssueDeletedEvent(
        long issueId,
        long projectId,
        String issueKey,
        boolean archived,
        long actorId,
        Instant at) implements IssueDomainEvent {}
