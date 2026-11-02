package com.platform.almbackend.repository;

import com.platform.almbackend.domain.ProjectDevSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

/**
 * 개발 설정 쓰기는 조건부 한 문장으로만 — 읽고 비교하고 저장하면 같은 version의 동시 PUT 둘이 모두 통과한다(lost update).
 * 반환값 0 = 경합에서 졌다(호출자가 409).
 */
public interface ProjectDevSettingsRepository extends JpaRepository<ProjectDevSettings, Long> {

    /** 읽은 version이 그대로일 때만 바꾸고 version을 올린다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProjectDevSettings s set s.branchPrefix = :prefix, s.version = s.version + 1, s.updatedAt = :now"
            + " where s.projectId = :projectId and s.version = :expectedVersion")
    int updateIfVersion(long projectId, int expectedVersion, String prefix, Instant now);

    /** 행이 없을 때의 첫 저장(version 1) — 동시에 처음 저장하면 하나만 들어간다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO project_dev_settings (project_id, branch_prefix, version, updated_at)"
            + " VALUES (:projectId, :prefix, 1, :now) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIfAbsent(long projectId, String prefix, Instant now);
}
