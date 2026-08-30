package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Issue;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, Long>, JpaSpecificationExecutor<Issue> {
    Optional<Issue> findByKey(String key);
    long countByType(String type);

    List<Issue> findByFixVersionId(long fixVersionId);

    /** 버전 삭제 시 — 벌크 UPDATE라 영속성 컨텍스트를 비운다 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Issue i set i.fixVersionId = null where i.fixVersionId = :versionId")
    int clearFixVersion(@Param("versionId") long versionId);

    @EntityGraph(attributePaths = "labels")
    List<Issue> findByProjectIdOrderBySortOrderAscKeyAsc(long projectId);
    List<Issue> findByParentId(long parentId);
    List<Issue> findByIdGreaterThanOrderByIdAsc(long afterId, Limit limit);
    List<Issue> findByProjectIdAndIdGreaterThanOrderByIdAsc(long projectId, long afterId, Limit limit);
    long deleteByProjectId(long projectId);

    @Query("select coalesce(max(i.sortOrder), 0) from Issue i where i.projectId = :projectId")
    long findMaxSortOrderByProjectId(@Param("projectId") long projectId);

    /**
     * 백로그 랭크 그룹 = 프로젝트 + 스프린트(상태 무관). 백로그는 sprintId가 null이라
     * 파생 쿼리로는 표현할 수 없어 명시적으로 null 비교를 쓴다.
     */
    @EntityGraph(attributePaths = "labels")
    @Query("""
            select i from Issue i
             where i.projectId = :projectId
               and ((:sprintId is null and i.sprintId is null) or i.sprintId = :sprintId)
             order by i.sortOrder asc, i.key asc
            """)
    List<Issue> findRankGroup(@Param("projectId") long projectId, @Param("sprintId") Long sprintId);

    /** 보드 컬럼 그룹 = 프로젝트 + 스프린트 + 상태. */
    @EntityGraph(attributePaths = "labels")
    @Query("""
            select i from Issue i
             where i.projectId = :projectId
               and ((:sprintId is null and i.sprintId is null) or i.sprintId = :sprintId)
               and i.status = :status
             order by i.sortOrder asc, i.key asc
            """)
    List<Issue> findBoardColumn(@Param("projectId") long projectId, @Param("sprintId") Long sprintId,
                                @Param("status") String status);

    @Query("""
            select coalesce(max(i.sortOrder), 0) from Issue i
             where i.projectId = :projectId
               and ((:sprintId is null and i.sprintId is null) or i.sprintId = :sprintId)
            """)
    long findMaxSortOrderInRankGroup(@Param("projectId") long projectId, @Param("sprintId") Long sprintId);

    @Modifying(flushAutomatically = true)
    @Query("update Issue i set i.parentId = null where i.parentId = :parentId")
    int clearParentByParentId(@Param("parentId") long parentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Issue i where i.id = :id")
    Optional<Issue> findByIdForUpdate(@Param("id") long id);
}
