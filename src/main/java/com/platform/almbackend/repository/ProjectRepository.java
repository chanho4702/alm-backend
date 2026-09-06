package com.platform.almbackend.repository;

import com.platform.almbackend.domain.Project;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    boolean existsByKey(String key);
    List<Project> findAllByOrderByNameAsc();

    /** AQL 이름 해석 — 키는 유일하고 이름은 유일하지 않다 */
    Optional<Project> findByKeyIgnoreCase(String key);

    List<Project> findByNameIgnoreCase(String name);
    List<Project> findAllByIdInOrderByNameAsc(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") long id);

    /** 휴지통 — @SQLRestriction 밖이라 네이티브로 읽는다 */
    @org.springframework.data.jpa.repository.Query(value = "select * from project where deleted_at is not null order by deleted_at desc", nativeQuery = true)
    List<Project> findTrashed();

    @org.springframework.data.jpa.repository.Query(value = "select * from project where id = :id and deleted_at is not null", nativeQuery = true)
    Optional<Project> findTrashedById(@org.springframework.data.repository.query.Param("id") long id);

    /** 보존 기간이 지난 휴지통 프로젝트의 id — 자동 비우기 대상. 엔티티를 붙들지 않게 id만 읽는다 */
    @org.springframework.data.jpa.repository.Query(
            value = "select id from project where deleted_at is not null and deleted_at < :threshold order by deleted_at limit :limit",
            nativeQuery = true)
    List<Long> findTrashedIdsDeletedBefore(
            @org.springframework.data.repository.query.Param("threshold") java.time.Instant threshold,
            @org.springframework.data.repository.query.Param("limit") int limit);

    /**
     * 삭제 직전 재확인 — 대상 선정과 실제 삭제 사이에 복원 후 다시 버려진 프로젝트는 deleted_at이 새로
     * 찍혀 보존 기간이 다시 시작된다. 임계값을 함께 보지 않으면 오늘 버린 것이 영구 삭제될 수 있다.
     */
    @org.springframework.data.jpa.repository.Query(
            value = "select * from project where id = :id and deleted_at is not null and deleted_at < :threshold",
            nativeQuery = true)
    Optional<Project> findTrashedByIdDeletedBefore(
            @org.springframework.data.repository.query.Param("id") long id,
            @org.springframework.data.repository.query.Param("threshold") java.time.Instant threshold);

    /** @SQLRestriction 밖의 행(보관·휴지통)까지 전부 — 테스트 초기화용. JPQL 벌크 삭제는 restriction을 탄다 */
    @Override
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from project", nativeQuery = true)
    void deleteAllInBatch();

    /** 영구 삭제 — SimpleJpaRepository.delete는 em.find(restriction 적용)로 존재 확인해 휴지통 행을 못 지운다 */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(value = "delete from project where id = :id and deleted_at is not null", nativeQuery = true)
    int purgeTrashedById(@org.springframework.data.repository.query.Param("id") long id);
}
