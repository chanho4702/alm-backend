package com.platform.almbackend.event.domain;

import java.time.Instant;

/**
 * 워크플로 전환 — 이슈의 상태가 실제로 바뀌었을 때 발행한다. 경로와 무관하다: 상세 수정(PUT)·보드 이동(move)·
 * 일괄 편집(bulk)·전환 실행(POST .../transitions). 스킴 변경에 따른 상태 이관(설정 저장)은 사용자의 전환이
 * 아니라 발행하지 않는다.
 *
 * @param transitionId 쓰인 워크플로 전환 id. 전환 목록이 비어 "모두 허용"인 워크플로면 {@code null}
 * @param fromStatus   이전 상태 id
 * @param toStatus     새 상태 id
 * @param fromKind     이전 상태의 의미(new/active/complete)
 * @param toKind       새 상태의 의미(new/active/complete)
 */
public record IssueTransitionedEvent(
        long issueId,
        long projectId,
        String issueKey,
        String fromStatus,
        String toStatus,
        String fromKind,
        String toKind,
        String transitionId,
        long actorId,
        Instant at) implements IssueDomainEvent {}
