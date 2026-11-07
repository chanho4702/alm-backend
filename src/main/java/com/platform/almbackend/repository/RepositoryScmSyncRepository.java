package com.platform.almbackend.repository;

import com.platform.almbackend.domain.RepositoryScmSync;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RepositoryScmSyncRepository extends JpaRepository<RepositoryScmSync, Long> {
    Optional<RepositoryScmSync> findByProjectIdAndRepositoryUrl(long projectId, String repositoryUrl);

    List<RepositoryScmSync> findByProjectIdOrderByIdAsc(long projectId);

    /** 때가 된 후보 — 실제로 도는 것은 claimDue로 임대를 잡은 행뿐이다 */
    @Query("""
            select s.id from RepositoryScmSync s
            where s.enabled = true and s.nextRunAt <= :now and (s.leaseUntil is null or s.leaseUntil < :now)
            order by s.nextRunAt asc
            """)
    List<Long> findDueIds(Instant now, Limit limit);

    /**
     * 주기 실행 임대 — 켜져 있고 때가 됐고 레이트 리밋 대기가 끝났고 아무도 쥐고 있지 않을(또는 임대가 만료된) 때만 1.
     * 조건부 UPDATE 한 줄이라 인스턴스가 여럿이어도 한 곳만 잡는다(PostgreSQL 행 잠금이 직렬화한다).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RepositoryScmSync s set s.leaseOwner = :owner, s.leaseUntil = :until
            where s.id = :id and s.enabled = true and s.nextRunAt <= :now
              and (s.rateLimitedUntil is null or s.rateLimitedUntil <= :now)
              and (s.leaseUntil is null or s.leaseUntil < :now)
            """)
    int claimDue(long id, String owner, Instant now, Instant until);

    /** "지금 동기화" 임대 — 주기·켜짐과 무관하지만 레이트 리밋 대기와 다른 실행 중이면 잡지 못한다 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update RepositoryScmSync s set s.leaseOwner = :owner, s.leaseUntil = :until
            where s.id = :id and (s.rateLimitedUntil is null or s.rateLimitedUntil <= :now)
              and (s.leaseUntil is null or s.leaseUntil < :now)
            """)
    int claimNow(long id, String owner, Instant now, Instant until);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RepositoryScmSync s where s.projectId = :projectId and s.repositoryUrl not in :keep")
    int deleteByProjectIdAndRepositoryUrlNotIn(long projectId, Collection<String> keep);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RepositoryScmSync s where s.projectId = :projectId")
    int deleteByProjectId(long projectId);
}
