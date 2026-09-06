package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SprintRepository extends JpaRepository<Sprint, Long> {
    List<Sprint> findByProjectIdOrderBySprintNumberAsc(long projectId);

    /** AQL 이름 해석 — 스프린트 이름은 프로젝트 안에서만 유일하다 */
    List<Sprint> findByNameIgnoreCase(String name);

    /** AQL {@code openSprints()} — 진행 중인 스프린트 전부 */
    List<Sprint> findByState(SprintState state);

    Optional<Sprint> findByProjectIdAndState(long projectId, SprintState state);

    long deleteByProjectId(long projectId);

    @Query("select coalesce(max(s.sprintNumber), 0) from Sprint s where s.projectId = :projectId")
    long findMaxSprintNumberByProjectId(@Param("projectId") long projectId);

    /**
     * 권한 확인에 필요한 projectId만 읽는다 — 엔티티를 영속성 컨텍스트에 올리지 않아서
     * 뒤이은 `findByIdForUpdate`가 1차 캐시의 낡은 인스턴스를 되돌려주지 않는다.
     */
    @Query("select s.projectId from Sprint s where s.id = :id")
    Optional<Long> findProjectIdById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sprint s where s.id = :id")
    Optional<Sprint> findByIdForUpdate(@Param("id") long id);
}
