package com.platform.almbackend.repository;

import com.platform.almbackend.domain.SavedView;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 저장된 보기 — 정렬은 서비스가 한국어 Collator로 한다(저장 필터와 같은 이유) */
public interface SavedViewRepository extends JpaRepository<SavedView, Long> {

    List<SavedView> findByOwnerIdAndScope(long ownerId, String scope);

    List<SavedView> findByProjectIdAndScope(long projectId, String scope);

    List<SavedView> findByOwnerIdAndProjectIdAndScope(long ownerId, long projectId, String scope);
}
