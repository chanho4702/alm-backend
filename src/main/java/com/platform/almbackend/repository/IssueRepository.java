package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Issue;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, Long> {
    @EntityGraph(attributePaths = "labels")
    List<Issue> findByProjectIdOrderBySortOrderAscKeyAsc(long projectId);
    List<Issue> findByParentId(long parentId);
    List<Issue> findByIdGreaterThanOrderByIdAsc(long afterId, Limit limit);
    List<Issue> findByProjectIdAndIdGreaterThanOrderByIdAsc(long projectId, long afterId, Limit limit);
    long deleteByProjectId(long projectId);

    @Query("select coalesce(max(i.sortOrder), 0) from Issue i where i.projectId = :projectId")
    long findMaxSortOrderByProjectId(@Param("projectId") long projectId);

    @Modifying(flushAutomatically = true)
    @Query("update Issue i set i.parentId = null where i.parentId = :parentId")
    int clearParentByParentId(@Param("parentId") long parentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Issue i where i.id = :id")
    Optional<Issue> findByIdForUpdate(@Param("id") long id);
}
