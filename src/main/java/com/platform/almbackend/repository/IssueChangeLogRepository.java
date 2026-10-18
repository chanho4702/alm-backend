package com.platform.almbackend.repository;

import com.platform.almbackend.domain.ChangeField;
import com.platform.almbackend.domain.IssueChangeLog;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface IssueChangeLogRepository extends JpaRepository<IssueChangeLog, Long> {

    /**
     * 이력을 최신부터 상한까지 — 서비스가 시간순으로 뒤집는다(상한에 걸리면 오래된 쪽이 잘린다: 리포트는
     * 최근 창을 그리므로 잘려도 최근 값은 맞다). 같은 트랜잭션에서 만든 줄은 시각이 같을 수 있어 id를 2차 키로 쓴다.
     * {@code until}은 배타 상한이고 항상 값을 받는다(서비스가 비면 먼 미래를 넣는다).
     * 필터는 전부 선택이다 — null이면 걸지 않는다. 시각 필터는 `:since is null` 대신 coalesce로 쓴다 —
     * PostgreSQL JDBC는 시각 null을 타입 미지정으로 보내 `? is null`의 타입을 못 정한다(H2는 통과).
     *
     * 스프린트 필터는 **전이의 양쪽**을 잡는다. 스프린트를 떠난 줄은 `sprint_id`가 이동한 뒤 값이라
     * 소속만 비교하면 원래 스프린트 리포트가 "시작 후 빠진 이슈"를 못 본다.
     */
    @Query("""
            select l from IssueChangeLog l
            where l.projectId = :projectId
              and (:field is null or l.field = :field)
              and (:sprintId is null
                   or l.sprintId = :sprintId
                   or (l.field = com.platform.almbackend.domain.ChangeField.SPRINT
                       and (l.fromValue = :sprintText or l.toValue = :sprintText)))
              and l.changedAt >= coalesce(:since, l.changedAt)
              and l.changedAt < :until
            order by l.changedAt desc, l.id desc
            """)
    List<IssueChangeLog> findHistoryNewestFirst(
            @Param("projectId") long projectId,
            @Param("field") ChangeField field,
            @Param("sprintId") Long sprintId,
            @Param("sprintText") String sprintText,
            @Param("since") Instant since,
            @Param("until") Instant until,
            Limit limit);
}
