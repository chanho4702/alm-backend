package com.platform.almbackend.issue;

import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.issue.dto.IssuePlanningRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 계획 필드(시작일·스토리 포인트, V30) 규칙 — 생성·수정이 같은 판정을 쓰게 한 곳에 둔다.
 *
 * <p>요청의 {@code planning}은 "키를 보낸 것만" 바뀐다({@link IssuePlanningRequest}). 시작일이 마감일보다 늦으면
 * 400이다 — 최종 값(요청 값이 없으면 지금 값)끼리 비교하므로 마감일만 앞당겨도 걸린다.
 */
public final class IssuePlanning {

    public static final String START_AFTER_DUE = "시작일은 마감일보다 늦을 수 없습니다";

    private IssuePlanning() {}

    /** 최종 계획 값 */
    public record Values(LocalDate startDate, BigDecimal storyPoints) {}

    /** 요청과 지금 값을 합친다 — 보낸 키만 바뀐다 */
    public static Values merge(IssuePlanningRequest request, LocalDate currentStart, BigDecimal currentPoints) {
        LocalDate start = currentStart;
        BigDecimal points = currentPoints;
        if (request != null) {
            if (request.has("startDate")) start = request.getStartDate();
            if (request.has("storyPoints")) points = request.getStoryPoints();
        }
        return new Values(start, normalizePoints(points));
    }

    public static void requireOrder(LocalDate start, LocalDate due) {
        if (start != null && due != null && start.isAfter(due)) throw new IllegalArgumentException(START_AFTER_DUE);
    }

    /** 3.0과 3을 같은 값으로 저장한다(소수 한 자리 고정) */
    private static BigDecimal normalizePoints(BigDecimal points) {
        return points == null ? null : points.setScale(1, java.math.RoundingMode.HALF_UP);
    }

    /** 바뀐 계획 필드를 활동에 남긴다(상세의 활동 탭) */
    public static void recordActivity(IssueActivityService activity, long actorId, Issue issue,
                                      LocalDate previousStart, BigDecimal previousPoints) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (!Objects.equals(previousStart, issue.getStartDate())) {
            activity.record(issue.getId(), actorId, "startdate",
                    text(previousStart) + " → " + text(issue.getStartDate()), now);
        }
        if (!samePoints(previousPoints, issue.getStoryPoints())) {
            activity.record(issue.getId(), actorId, "storypoints",
                    points(previousPoints) + " → " + points(issue.getStoryPoints()), now);
        }
    }

    private static boolean samePoints(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return a == b;
        return a.compareTo(b) == 0;
    }

    private static String text(LocalDate date) {
        return date == null ? "미지정" : date.toString();
    }

    private static String points(BigDecimal value) {
        return value == null ? "미추정" : value.stripTrailingZeros().toPlainString();
    }
}
