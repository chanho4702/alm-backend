package com.platform.almbackend.estimation;

import com.platform.almbackend.domain.Issue;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 추정 합계 질의 — 이슈를 메모리에 올리지 않고 DB가 스프린트별로 센다(백로그는 sprintId null 묶음).
 * 트리아지 대기열(V35)은 계획 밖이라 넣지 않는다.
 */
interface EstimationQueries extends Repository<Issue, Long> {

    /** 행: sprintId, 이슈 수, 완료 수, 포인트 합, 완료 포인트 합, 미추정 수, 예상 시간 합, 완료 예상 시간 합 */
    @Query("""
            select i.sprintId,
                   count(i),
                   sum(case when i.status in :done then 1 else 0 end),
                   sum(i.storyPoints),
                   sum(case when i.status in :done then i.storyPoints else 0 end),
                   sum(case when i.storyPoints is null then 1 else 0 end),
                   sum(i.estimateHours),
                   sum(case when i.status in :done then i.estimateHours else 0 end)
              from Issue i
             where i.projectId = :projectId and i.triageState is null
             group by i.sprintId
            """)
    List<Object[]> totalsBySprint(@Param("projectId") long projectId, @Param("done") Collection<String> done);

    /** 행: sprintId, 완료 수, 완료 포인트, 완료 예상 시간 — 완료된 스프린트에 남은 완료 이슈 */
    @Query("""
            select i.sprintId,
                   count(i),
                   sum(i.storyPoints),
                   sum(i.estimateHours)
              from Issue i
             where i.projectId = :projectId and i.sprintId in :sprintIds and i.status in :done
             group by i.sprintId
            """)
    List<Object[]> completedIn(@Param("projectId") long projectId, @Param("sprintIds") Collection<Long> sprintIds,
                               @Param("done") Collection<String> done);

    /**
     * 스프린트 완료 처리로 옮겨진(이월된) 이슈 — 완료 처리는 이력 시각을 스프린트 {@code completedAt}과 같게 남긴다
     * ({@code IssueChangeLogService#recordChanges}). 그래서 "그 순간 그 스프린트에서 나간 이슈"가 완료 시점의 미완료분이다.
     * 행: 이슈 포인트, 예상 시간
     */
    @Query("""
            select i.storyPoints, i.estimateHours
              from Issue i
             where i.id in (select l.issueId from IssueChangeLog l
                             where l.projectId = :projectId
                               and l.field = com.platform.almbackend.domain.ChangeField.SPRINT
                               and l.fromValue = :sprintText
                               and l.changedAt = :completedAt)
            """)
    List<Object[]> carriedOver(@Param("projectId") long projectId, @Param("sprintText") String sprintText,
                               @Param("completedAt") Instant completedAt);
}
