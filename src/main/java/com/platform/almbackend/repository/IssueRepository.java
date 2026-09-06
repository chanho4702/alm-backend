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
    long countByPriority(String priority);

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

    /** 보관함 — @SQLRestriction 밖이라 네이티브로 읽는다 */
    @Query(value = "select * from issue where project_id = :projectId and archived_at is not null order by archived_at desc, id desc", nativeQuery = true)
    List<Issue> findArchivedByProject(@Param("projectId") long projectId);

    @Query(value = "select * from issue where id = :id and archived_at is not null", nativeQuery = true)
    Optional<Issue> findArchivedById(@Param("id") long id);

    @Query(value = "select count(*) from issue where project_id = :projectId and archived_at is not null", nativeQuery = true)
    long countArchivedByProject(@Param("projectId") long projectId);

    /** 영구 삭제 — 보관된 이슈까지 포함해 지운다(JPQL delete는 restriction을 안 탈 수 있어 네이티브) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from issue where project_id = :projectId", nativeQuery = true)
    int purgeByProjectId(@Param("projectId") long projectId);

    /**
     * AQL이 사람 이름을 풀 때 쓰는 후보 id — org에는 "전원 조회" 창구가 없어서(GetMembers는 id로만 읽는다)
     * 이슈에 실제로 등장하는 담당자·보고자를 후보로 삼는다. 어차피 이슈에 없는 사람은 검색 결과에도 없다.
     * 보관된 행까지 보려고 네이티브로 읽는다.
     */
    @Query(value = """
            select distinct u from (
                select assignee_id as u from issue where assignee_id is not null
                union
                select reporter_id as u from issue
            ) participants
            """, nativeQuery = true)
    List<Long> findParticipantUserIds();

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

    /** @SQLRestriction 밖의 행(보관·휴지통)까지 전부 — 테스트 초기화용. JPQL 벌크 삭제는 restriction을 탄다 */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from issue_label", nativeQuery = true)
    void purgeAllLabels();

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from issue", nativeQuery = true)
    void purgeAllIssues();

    @Override
    @org.springframework.transaction.annotation.Transactional
    default void deleteAllInBatch() {
        purgeAllLabels();
        purgeAllIssueComponents();
        purgeAllIssues();
    }

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from issue_component", nativeQuery = true)
    void purgeAllIssueComponents();

    /** 영구 삭제 전 라벨 테이블(ElementCollection) 정리 — 네이티브 삭제는 컬렉션을 모른다 */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from issue_label where issue_id in (select id from issue where project_id = :projectId)", nativeQuery = true)
    int purgeLabelsByProjectId(@Param("projectId") long projectId);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from issue_component where issue_id in (select id from issue where project_id = :projectId)", nativeQuery = true)
    int purgeComponentsByProjectId(@Param("projectId") long projectId);
}
