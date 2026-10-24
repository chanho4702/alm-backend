package com.platform.almbackend.repository;

import com.platform.almbackend.domain.AutomationRule;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, Long> {

    List<AutomationRule> findByProjectIdOrderByIdAsc(long projectId);

    /** 이벤트가 오면 — 부분 인덱스 idx_automation_rule_trigger */
    List<AutomationRule> findByProjectIdAndTriggerTypeAndEnabledTrue(long projectId, String triggerType);

    /** 때가 된 예약 규칙 — 부분 인덱스 idx_automation_rule_due */
    @Query("""
            select r from AutomationRule r
             where r.enabled = true and r.triggerType = 'SCHEDULED' and r.nextRunAt <= :now
             order by r.nextRunAt asc, r.id asc
            """)
    List<AutomationRule> findDue(@Param("now") Instant now, Limit limit);

    /**
     * 예약 실행 차지 — 다음 시각으로 옮기는 데 성공한 노드만 이번 회차를 돈다(여러 인스턴스가 같은 회차를 두 번 돌지 않게).
     * 기대한 시각이 이미 바뀌었으면 0이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AutomationRule r set r.nextRunAt = :next
             where r.id = :id and r.enabled = true and r.nextRunAt = :expected
            """)
    int claim(@Param("id") long id, @Param("expected") Instant expected, @Param("next") Instant next);
}
