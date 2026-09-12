package com.platform.almbackend.repository;

import com.platform.almbackend.domain.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 저장 필터 — 조회는 언제나 소유자로 좁힌다(id만으로 찾는 길을 두지 않는다) */
public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {

    List<SavedFilter> findByOwnerIdOrderByNameAscIdAsc(long ownerId);

    Optional<SavedFilter> findByIdAndOwnerId(long id, long ownerId);

    boolean existsByOwnerIdAndName(long ownerId, String name);
}
