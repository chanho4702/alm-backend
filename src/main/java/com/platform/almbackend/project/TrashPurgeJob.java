package com.platform.almbackend.project;

import com.platform.almbackend.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 휴지통 자동 비우기 — 보존 기간(기본 60일)이 지난 프로젝트를 영구 삭제한다.
 *
 * 휴지통이 영원히 쌓이면 첨부 오브젝트와 이슈 정본이 계속 남는다. 지라도 같은 기간을 두고 스스로 비운다.
 * 삭제 순서는 손으로 하는 영구 삭제와 같다 — {@code ProjectService.purgeExpired}가 한 곳에 모아 둔 경로를
 * 그대로 탄다. 프로젝트마다 트랜잭션이 따로라, 한 건이 실패해도 나머지는 지워진다.
 *
 * 인스턴스가 여럿이면 같은 시각에 겹쳐 돌 수 있다. 두 번째 인스턴스는 이미 지워진 행을 찾지 못해 건너뛰므로
 * 결과는 같지만, 불필요한 경합을 피하려면 한 인스턴스에서만 켜 둔다({@code ALM_TRASH_PURGE_ENABLED}).
 */
@Component
@ConditionalOnProperty(prefix = "platform.alm.trash", name = "purge-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class TrashPurgeJob {

    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final TrashPolicy policy;

    /** 회차당 상한 — 만료 대상이 대량이어도 메모리와 트랜잭션 수를 묶는다. 남은 것은 다음 회차 */
    static final int BATCH_LIMIT = 500;

    @Scheduled(cron = "${platform.alm.trash.purge-cron:0 0 3 * * *}")
    public void tick() {
        purgeExpired(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 스케줄과 분리해 둔 본체 — 테스트는 기준 시각을 주고 이 메서드를 직접 부른다. 지운 건수를 돌려준다 */
    public int purgeExpired(Instant now) {
        Instant threshold = policy.purgeThreshold(now);
        List<Long> expired = projects.findTrashedIdsDeletedBefore(threshold, BATCH_LIMIT);
        if (expired.isEmpty()) return 0;

        int purged = 0;
        for (Long projectId : expired) {
            try {
                projectService.purgeExpired(projectId, threshold);
                purged++;
            } catch (com.platform.common.error.NotFoundException e) {
                // 선정 뒤 복원됐거나(또는 재삭제로 보존 기간이 새로 시작) 다른 인스턴스가 먼저 지웠다 — 정상 경합
                log.info("휴지통 자동 비우기 건너뜀 — 더 이상 대상이 아님: project={}", projectId);
            } catch (RuntimeException e) {
                // 한 건의 실패(첨부 저장소 장애·잠금 등)가 나머지를 막지 않는다 — 다음 회차에 다시 시도한다
                log.error("휴지통 자동 비우기 실패 — 건너뜀: project={}", projectId, e);
            }
        }
        log.info("휴지통 자동 비우기: 대상 {}건 중 {}건 영구 삭제 (보존 {}일, 기준 {})",
                expired.size(), purged, policy.retentionDays(), threshold);
        return purged;
    }
}
