package com.platform.almbackend.event.domain;

import java.time.Instant;

/**
 * 스프린트 변경(서비스 안 도메인 이벤트, 2026-10-24) — 실시간 무효화 신호가 듣는다. 이슈 도메인 이벤트와 같은 규칙:
 * 변경 트랜잭션 안에서 발행하고 커밋 뒤({@code AFTER_COMMIT})에 반응한다. 완료·삭제처럼 이슈를 무더기로 옮기는 변경은
 * 이슈마다 신호를 내지 않고 이 한 건으로 대신한다(받는 쪽이 보드·백로그를 다시 읽는다).
 *
 * @param kind created · updated · started · completed · deleted
 */
public record SprintChangedEvent(
        long sprintId,
        long projectId,
        String kind,
        long version,
        long actorId,
        Instant at) {}
