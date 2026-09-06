package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Component;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ComponentRepository extends JpaRepository<Component, Long> {
    List<Component> findByProjectIdOrderByNameAsc(long projectId);

    /** AQL 이름 해석 — 컴포넌트 이름은 프로젝트 안에서만 유일하라 전 프로젝트에서 찾으면 여럿일 수 있다 */
    List<Component> findByNameIgnoreCase(String name);
    boolean existsByProjectIdAndName(long projectId, String name);
    boolean existsByProjectIdAndNameAndIdNot(long projectId, String name, long id);

    @Query(value = "select count(distinct issue_id) from issue_component where component_id = :componentId", nativeQuery = true)
    long countIssues(@Param("componentId") long componentId);

    /** 컴포넌트를 지우면 이슈에서 떼어낸다 — FK cascade가 없는 테스트 DB에서도 같은 결과 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from issue_component where component_id = :componentId", nativeQuery = true)
    int detachFromIssues(@Param("componentId") long componentId);
}
