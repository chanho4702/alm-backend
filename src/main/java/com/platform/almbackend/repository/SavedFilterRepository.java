package com.platform.almbackend.repository;

import com.platform.almbackend.domain.SavedFilter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 저장 필터 — 조회는 언제나 소유자로 좁힌다(id만으로 찾는 길을 두지 않는다).
 *
 * <p>정렬은 여기서 하지 않는다: {@code ORDER BY name}은 DB 콜레이션을 타서 한글·영문이 섞이면
 * 환경마다(H2·Postgres·로케일) 순서가 갈린다. 서비스가 {@code Collator}로 정한다.
 */
public interface SavedFilterRepository extends JpaRepository<SavedFilter, Long> {

    List<SavedFilter> findByOwnerId(long ownerId);

    Optional<SavedFilter> findByIdAndOwnerId(long id, long ownerId);

    boolean existsByOwnerIdAndName(long ownerId, String name);
}
