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

    /** 키 점유 확인 — 보관된 이슈도 키를 쥐고 있어 @SQLRestriction 밖까지 본다(가져오기의 키 보존 검사) */
    @Query(value = "select count(*) > 0 from issue where issue_key = :key", nativeQuery = true)
    boolean existsKeyIncludingArchived(@Param("key") String key);

    // ── 목록 응답의 컬렉션 일괄 읽기(N+1 방지) — 보관 행(보관함·AQL archived)도 읽어야 해서 네이티브 ──

    /** [issue_id, label] — 이슈별 라벨 순서(label_order) 그대로 */
    @Query(value = "select issue_id, label from issue_label where issue_id in (:ids) order by issue_id, label_order",
            nativeQuery = true)
    List<Object[]> findLabelRows(@Param("ids") java.util.Collection<Long> ids);

    /** [issue_id, component_id] — 이슈별 컴포넌트 순서(component_order) 그대로 */
    @Query(value = "select issue_id, component_id from issue_component where issue_id in (:ids) order by issue_id, component_order",
            nativeQuery = true)
    List<Object[]> findComponentRows(@Param("ids") java.util.Collection<Long> ids);

    /**
     * 프로젝트 라벨 후보 — 보관되지 않은 이슈의 라벨만. JPQL이라 Issue의 @SQLRestriction(archived_at is null)이
     * 그대로 걸린다. 정렬은 대소문자 무시 규칙이라 서비스가 한다.
     */
    @Query("select distinct l from Issue i join i.labels l where i.projectId = :projectId")
    List<String> findDistinctLabelsByProjectId(@Param("projectId") long projectId);

    /**
     * startId의 조상 체인(자기 자신 포함, 위로) 어디엔가 ancestorId가 부모로 나오는가 — 순환 검사용.
     * 재귀 CTE 한 번. 데이터가 이미 순환해 있어도 깊이 상한(1000)에서 멈춘다. 보관된 이슈도 체인에 넣는다(네이티브).
     */
    @Query(value = """
            with recursive chain(id, parent_id, depth) as (
                select id, parent_id, 0 from issue where id = :startId
                union all
                select i.id, i.parent_id, c.depth + 1 from issue i join chain c on i.id = c.parent_id
                 where c.depth < 1000
            )
            select count(*) > 0 from chain where parent_id = :ancestorId
            """, nativeQuery = true)
    boolean ancestorChainContains(@Param("startId") long startId, @Param("ancestorId") long ancestorId);
}
