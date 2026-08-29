package com.platform.almbackend.repository;

import com.platform.almbackend.domain.ProjectVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectVersionRepository extends JpaRepository<ProjectVersion, Long> {
    List<ProjectVersion> findByProjectIdOrderByCreatedAtAscIdAsc(long projectId);

    boolean existsByProjectIdAndName(long projectId, String name);

    long deleteByProjectId(long projectId);

    /** 권한 확인용 projectId만 — 엔티티를 1차 캐시에 올리지 않아 뒤이은 잠금 조회가 낡은 인스턴스를 주지 않는다 */
    @Query("select v.projectId from ProjectVersion v where v.id = :id")
    Optional<Long> findProjectIdById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ProjectVersion v where v.id = :id")
    Optional<ProjectVersion> findByIdForUpdate(@Param("id") long id);
}
