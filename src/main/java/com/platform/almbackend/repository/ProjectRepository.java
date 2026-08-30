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
    List<Project> findAllByIdInOrderByNameAsc(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Project p where p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") long id);

    /** 휴지통 — @SQLRestriction 밖이라 네이티브로 읽는다 */
    @org.springframework.data.jpa.repository.Query(value = "select * from project where deleted_at is not null order by deleted_at desc", nativeQuery = true)
    List<Project> findTrashed();

    @org.springframework.data.jpa.repository.Query(value = "select * from project where id = :id and deleted_at is not null", nativeQuery = true)
    Optional<Project> findTrashedById(@org.springframework.data.repository.query.Param("id") long id);

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
