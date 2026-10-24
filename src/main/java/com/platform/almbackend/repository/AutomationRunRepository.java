package com.platform.almbackend.repository;

import com.platform.almbackend.domain.AutomationRun;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, Long> {

    List<AutomationRun> findByRuleIdOrderByStartedAtDescIdDesc(long ruleId, Limit limit);

    List<AutomationRun> findByProjectIdOrderByStartedAtDescIdDesc(long projectId, Limit limit);

    /** 분당 실행 상한 — 실제로 돈 실행(성공·실패)만 센다 */
    long countByRuleIdAndStartedAtAfterAndOutcomeIn(long ruleId, Instant since, Collection<String> outcomes);

    /** 보존 기한이 지난 이력 */
    @Modifying
    @Query("delete from AutomationRun r where r.startedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
