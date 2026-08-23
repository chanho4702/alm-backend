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

    Optional<Sprint> findByProjectIdAndState(long projectId, SprintState state);

    long deleteByProjectId(long projectId);

    @Query("select coalesce(max(s.sprintNumber), 0) from Sprint s where s.projectId = :projectId")
    long findMaxSprintNumberByProjectId(@Param("projectId") long projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sprint s where s.id = :id")
    Optional<Sprint> findByIdForUpdate(@Param("id") long id);
}
