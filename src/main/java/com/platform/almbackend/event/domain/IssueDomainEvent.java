package com.platform.almbackend.event.domain;

import java.time.Instant;

/**
 * 서비스 <b>안</b>의 이슈 도메인 이벤트 — Spring {@code ApplicationEventPublisher}로 발행한다(2026-10-24 워크플로 엔진).
 * 자동화 규칙 엔진처럼 같은 프로세스 안에서 "이슈가 만들어졌다·바뀌었다·전환됐다"에 반응하는 쪽이 붙는다.
 *
 * <p><b>발행 시점</b>: 변경을 일으킨 트랜잭션 <b>안</b>에서 발행한다. 커밋된 사실에만 반응해야 하는 리스너는
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 받는다 — 롤백된 변경에는 불리지 않는다.
 * AFTER_COMMIT 리스너에서 다시 DB를 쓰려면 새 트랜잭션({@code REQUIRES_NEW})이 필요하다(커밋이 끝난 트랜잭션에는
 * 더 쓸 수 없다).
 *
 * <p><b>서비스 간 이벤트와의 관계</b>: 다른 서비스가 듣는 이벤트는 여전히 아웃박스(V26) → Redis Streams
 * ({@code AlmEvents}, proto {@code EventEnvelope})다. 이 인터페이스는 그것을 대체하지 않는다 — 프로세스 안 리스너용이다.
 *
 * <p>본문(제목·설명)은 싣지 않는다. 필요한 리스너가 {@code issueId}로 다시 읽는다(Redis 이벤트와 같은 원칙).
 */
public sealed interface IssueDomainEvent permits IssueCreatedEvent, IssueUpdatedEvent, IssueTransitionedEvent {
    long issueId();

    long projectId();

    String issueKey();

    /** 변경을 일으킨 사용자 id(JWT sub) */
    long actorId();

    /** 변경 시각(마이크로초 절삭) */
    Instant at();
}
