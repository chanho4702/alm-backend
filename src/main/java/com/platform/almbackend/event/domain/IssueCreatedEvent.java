package com.platform.almbackend.event.domain;

import java.time.Instant;

/** 이슈 생성 — 단건 생성·CSV 가져오기·복제가 모두 같은 생성 경로를 탄다 */
public record IssueCreatedEvent(
        long issueId,
        long projectId,
        String issueKey,
        /** 이슈 타입 레지스트리 id */
        String type,
        /** 생성된 상태 id */
        String status,
        long actorId,
        Instant at,
        /** 생성 직후 이슈 version(실시간 무효화 신호용, 2026-10-24) */
        long version) implements IssueDomainEvent {}
