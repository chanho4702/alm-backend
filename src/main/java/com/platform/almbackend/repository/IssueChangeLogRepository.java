package com.platform.almbackend.repository;

import com.platform.almbackend.domain.ChangeField;
import com.platform.almbackend.domain.IssueChangeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface IssueChangeLogRepository extends JpaRepository<IssueChangeLog, Long> {

    /**
     * 시간순 이력. 같은 트랜잭션에서 만든 줄은 시각이 같을 수 있어 id를 2차 키로 쓴다.
     * 필터는 전부 선택이다 — null이면 걸지 않는다.
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
              and (:since is null or l.changedAt >= :since)
            order by l.changedAt asc, l.id asc
            """)
    List<IssueChangeLog> findHistory(
            @Param("projectId") long projectId,
            @Param("field") ChangeField field,
            @Param("sprintId") Long sprintId,
            @Param("sprintText") String sprintText,
            @Param("since") Instant since);
}
