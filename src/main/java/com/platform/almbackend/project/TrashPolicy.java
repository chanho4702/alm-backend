package com.platform.almbackend.project;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 휴지통 보존 정책 — 삭제한 프로젝트를 며칠 뒤에 영구히 지우는가.
 *
 * 기본 60일은 지라 휴지통과 같다. 프로젝트 응답의 {@code purgeAt}은 이 정책으로 계산한 예정 시각이고,
 * 실제로 지우는 것은 {@link TrashPurgeJob}이다 — 둘이 같은 계산을 쓰게 여기에 모아 둔다.
 */
@Component
public class TrashPolicy {

    private final int retentionDays;

    public TrashPolicy(@Value("${platform.alm.trash.retention-days:60}") int retentionDays) {
        if (retentionDays < 1) {
            // 0이나 음수면 휴지통에 넣는 즉시 다음 회차에 영구 삭제된다 — 복원 창이 없는 설정은 부팅에서 거부
            throw new IllegalArgumentException("platform.alm.trash.retention-days는 1 이상이어야 합니다: " + retentionDays);
        }
        this.retentionDays = retentionDays;
    }

    public int retentionDays() {
        return retentionDays;
    }

    /** 휴지통에 없는 프로젝트(deletedAt == null)에는 예정 시각이 없다 */
    public Instant purgeAt(Instant deletedAt) {
        return deletedAt == null ? null : deletedAt.plus(Duration.ofDays(retentionDays));
    }

    /** 이 시각보다 먼저 삭제된 것이 비우기 대상 */
    public Instant purgeThreshold(Instant now) {
        return now.minus(Duration.ofDays(retentionDays));
    }
}
