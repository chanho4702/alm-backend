package com.platform.almbackend.repository;

import com.platform.almbackend.domain.CodeRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CodeRepositoryRepository extends JpaRepository<CodeRepository, Long> {
    List<CodeRepository> findByProjectIdOrderByPositionAscIdAsc(long projectId);

    /** 이 저장소를 등록한 프로젝트들(휴지통 프로젝트 포함 — 호출자가 권한·상태로 거른다) */
    List<CodeRepository> findByUrl(String url);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CodeRepository r where r.projectId = :projectId")
    int deleteByProjectId(long projectId);
}
