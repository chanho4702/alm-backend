package com.platform.almbackend.repository;

import com.platform.almbackend.domain.RepositoryWebhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RepositoryWebhookRepository extends JpaRepository<RepositoryWebhook, Long> {
    Optional<RepositoryWebhook> findByPublicId(String publicId);

    Optional<RepositoryWebhook> findByProjectIdAndRepositoryUrl(long projectId, String repositoryUrl);

    List<RepositoryWebhook> findByProjectIdOrderByIdAsc(long projectId);

    /** 저장소 목록 교체에서 빠진 저장소의 웹훅을 지운다(남은 URL 목록 밖). 전달 기록은 FK CASCADE로 함께 지워진다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RepositoryWebhook w where w.projectId = :projectId and w.repositoryUrl not in :keep")
    int deleteByProjectIdAndRepositoryUrlNotIn(long projectId, Collection<String> keep);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RepositoryWebhook w where w.projectId = :projectId")
    int deleteByProjectId(long projectId);

    /** 마지막 수신 결과 한 줄 — 실패 기록은 처리 트랜잭션이 롤백된 뒤 별도 트랜잭션에서 부른다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RepositoryWebhook w set w.lastDeliveryAt = :at, w.lastEvent = :event, w.lastOutcome = :outcome, w.lastMessage = :message
            where w.id = :id
            """)
    int recordLast(long id, Instant at, String event, String outcome, String message);
}
