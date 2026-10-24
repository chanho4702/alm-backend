package com.platform.almbackend.automation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.scheduling.support.CronExpression;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 자동화 규칙 본문 — 트리거와 액션. <b>닫힌 어휘</b>다: 모르는 종류·필드는 저장 때 400으로 막는다(실행 중에 처음 알게 되면
 * 규칙이 조용히 실패를 쌓는다). 저장된 JSON도 이 레코드 모양 그대로다 — 프론트가 같은 모양을 주고받는다.
 */
public final class AutomationSpec {

    private AutomationSpec() {}

    public static final String ISSUE_CREATED = "ISSUE_CREATED";
    public static final String ISSUE_TRANSITIONED = "ISSUE_TRANSITIONED";
    public static final String FIELD_CHANGED = "FIELD_CHANGED";
    public static final String SCHEDULED = "SCHEDULED";
    public static final List<String> TRIGGERS = List.of(ISSUE_CREATED, ISSUE_TRANSITIONED, FIELD_CHANGED, SCHEDULED);

    public static final String SET_FIELD = "SET_FIELD";
    public static final String ASSIGN = "ASSIGN";
    public static final String COMMENT = "COMMENT";
    public static final String TRANSITION = "TRANSITION";
    public static final String LABELS = "LABELS";
    public static final String NOTIFY = "NOTIFY";
    public static final List<String> ACTIONS = List.of(SET_FIELD, ASSIGN, COMMENT, TRANSITION, LABELS, NOTIFY);

    /** SET_FIELD가 바꿀 수 있는 필드 */
    public static final List<String> SETTABLE_FIELDS = List.of("priority", "dueDate", "startDate", "storyPoints", "flagged");
    /** NOTIFY 대상 */
    public static final List<String> RECIPIENTS = List.of("assignee", "reporter", "watchers");

    public static final int MAX_ACTIONS = 10;
    public static final int MAX_COMMENT = 5000;
    public static final int MAX_MESSAGE = 200;
    /** 예약은 이보다 자주 돌 수 없다 */
    public static final Duration MIN_SCHEDULE_GAP = Duration.ofMinutes(5);
    /** cron은 서울 시각으로 읽는다(AQL 날짜와 같은 기준) */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "트리거 — type에 따라 쓰는 항목이 다르다")
    public record Trigger(
            @Schema(description = "ISSUE_CREATED·ISSUE_TRANSITIONED·FIELD_CHANGED·SCHEDULED", example = "ISSUE_TRANSITIONED")
            String type,
            @Schema(description = "ISSUE_TRANSITIONED: 출발 상태(비우면 아무 상태)", example = "[\"inprogress\"]")
            List<String> fromStatuses,
            @Schema(description = "ISSUE_TRANSITIONED: 도착 상태(비우면 아무 상태)", example = "[\"done\"]")
            List<String> toStatuses,
            @Schema(description = "FIELD_CHANGED: 지켜볼 필드(하나 이상) — status·assignee·priority·sprint·dueDate·startDate·storyPoints·labels·flagged…",
                    example = "[\"priority\"]")
            List<String> fields,
            @Schema(description = "SCHEDULED: cron(서울 시각). 5필드(분 시 일 월 요일) 또는 6필드(초 포함). 5분보다 자주 돌 수 없다",
                    example = "0 9 * * MON-FRI")
            String cron) {

        public List<String> fromStatuses() { return fromStatuses == null ? List.of() : fromStatuses; }
        public List<String> toStatuses() { return toStatuses == null ? List.of() : toStatuses; }
        public List<String> fields() { return fields == null ? List.of() : fields; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(description = "액션 하나 — type에 따라 쓰는 항목이 다르다")
    public record Action(
            @Schema(description = "SET_FIELD·ASSIGN·COMMENT·TRANSITION·LABELS·NOTIFY", example = "SET_FIELD") String type,
            @Schema(description = "SET_FIELD: priority·dueDate·startDate·storyPoints·flagged", example = "priority") String field,
            @Schema(description = "SET_FIELD: 값(우선순위 id·yyyy-MM-dd·수·true/false, null이면 비우기 — priority·flagged는 비울 수 없다)",
                    example = "\"high\"") Object value,
            @Schema(description = "ASSIGN: reporter·unassigned·user", example = "user") String assignee,
            @Schema(description = "ASSIGN(user): 담당자 사용자 ID", example = "42") Long userId,
            @Schema(description = "COMMENT: 코멘트 본문(1~5000자)") String body,
            @Schema(description = "TRANSITION: 도착 상태 ID — 워크플로 전환 규칙을 그대로 탄다", example = "done") String toStatus,
            @Schema(description = "LABELS: 더할 라벨") List<String> add,
            @Schema(description = "LABELS: 뺄 라벨") List<String> remove,
            @Schema(description = "NOTIFY: assignee·reporter·watchers", example = "[\"assignee\"]") List<String> recipients,
            @Schema(description = "NOTIFY: 그 밖에 알릴 사용자 ID") List<Long> userIds,
            @Schema(description = "NOTIFY: 알림 문장(1~200자)", example = "마감이 지났습니다") String message) {

        public List<String> add() { return add == null ? List.of() : add; }
        public List<String> remove() { return remove == null ? List.of() : remove; }
        public List<String> recipients() { return recipients == null ? List.of() : recipients; }
        public List<Long> userIds() { return userIds == null ? List.of() : userIds; }
    }

    // ── 검증 ──

    /** 트리거 검증 — 상태 id는 프로젝트 워크플로에 있어야 한다(validStatus) */
    public static Trigger validTrigger(Trigger trigger, Predicate<String> validStatus) {
        if (trigger == null || trigger.type() == null) throw new IllegalArgumentException("트리거를 고르세요");
        String type = trigger.type().trim().toUpperCase(Locale.ROOT);
        if (!TRIGGERS.contains(type)) throw new IllegalArgumentException("모르는 트리거입니다: " + trigger.type());
        return switch (type) {
            case ISSUE_TRANSITIONED -> new Trigger(type,
                    statuses(trigger.fromStatuses(), validStatus), statuses(trigger.toStatuses(), validStatus), null, null);
            case FIELD_CHANGED -> {
                if (trigger.fields().isEmpty()) throw new IllegalArgumentException("지켜볼 필드를 하나 이상 고르세요");
                Set<String> fields = new LinkedHashSet<>();
                for (String field : trigger.fields()) {
                    if (field == null || !IssueDomainEventPublisher.CHANGED_FIELDS.contains(field)) {
                        throw new IllegalArgumentException("지켜볼 수 없는 필드입니다: " + field);
                    }
                    fields.add(field);
                }
                yield new Trigger(type, null, null, List.copyOf(fields), null);
            }
            case SCHEDULED -> new Trigger(type, null, null, null, normalizeCron(trigger.cron()));
            default -> new Trigger(type, null, null, null, null);
        };
    }

    public static List<Action> validActions(List<Action> actions, Predicate<String> validStatus) {
        if (actions == null || actions.isEmpty()) throw new IllegalArgumentException("액션을 하나 이상 넣으세요");
        if (actions.size() > MAX_ACTIONS) throw new IllegalArgumentException("액션은 " + MAX_ACTIONS + "개까지 넣을 수 있습니다");
        List<Action> out = new ArrayList<>();
        for (Action action : actions) out.add(validAction(action, validStatus));
        return List.copyOf(out);
    }

    private static Action validAction(Action action, Predicate<String> validStatus) {
        if (action == null || action.type() == null) throw new IllegalArgumentException("액션 종류를 고르세요");
        String type = action.type().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case SET_FIELD -> {
                String field = action.field() == null ? "" : action.field().trim();
                if (!SETTABLE_FIELDS.contains(field)) throw new IllegalArgumentException("바꿀 수 없는 필드입니다: " + action.field());
                checkValue(field, action.value());
                yield new Action(type, field, action.value(), null, null, null, null, null, null, null, null, null);
            }
            case ASSIGN -> {
                String mode = action.assignee() == null ? "" : action.assignee().trim().toLowerCase(Locale.ROOT);
                if (!List.of("reporter", "unassigned", "user").contains(mode)) {
                    throw new IllegalArgumentException("담당자 지정은 reporter·unassigned·user 중 하나입니다");
                }
                if ("user".equals(mode) && (action.userId() == null || action.userId() <= 0)) {
                    throw new IllegalArgumentException("담당자로 지정할 사용자를 고르세요");
                }
                yield new Action(type, null, null, mode, "user".equals(mode) ? action.userId() : null,
                        null, null, null, null, null, null, null);
            }
            case COMMENT -> {
                String body = action.body() == null ? "" : action.body().trim();
                if (body.isEmpty()) throw new IllegalArgumentException("코멘트 내용을 입력하세요");
                if (body.length() > MAX_COMMENT) throw new IllegalArgumentException("코멘트는 " + MAX_COMMENT + "자 이하여야 합니다");
                yield new Action(type, null, null, null, null, body, null, null, null, null, null, null);
            }
            case TRANSITION -> {
                String to = action.toStatus() == null ? "" : action.toStatus().trim();
                if (to.isEmpty() || !validStatus.test(to)) throw new IllegalArgumentException("이 프로젝트에 없는 상태입니다: " + to);
                yield new Action(type, null, null, null, null, null, to, null, null, null, null, null);
            }
            case LABELS -> {
                List<String> add = labels(action.add());
                List<String> remove = labels(action.remove());
                if (add.isEmpty() && remove.isEmpty()) throw new IllegalArgumentException("더하거나 뺄 라벨을 넣으세요");
                yield new Action(type, null, null, null, null, null, null, add, remove, null, null, null);
            }
            case NOTIFY -> {
                for (String r : action.recipients()) {
                    if (!RECIPIENTS.contains(r)) throw new IllegalArgumentException("모르는 알림 대상입니다: " + r);
                }
                if (action.recipients().isEmpty() && action.userIds().isEmpty()) {
                    throw new IllegalArgumentException("알릴 대상을 고르세요");
                }
                String message = action.message() == null ? "" : action.message().trim();
                if (message.isEmpty()) throw new IllegalArgumentException("알림 문장을 입력하세요");
                if (message.length() > MAX_MESSAGE) throw new IllegalArgumentException("알림 문장은 " + MAX_MESSAGE + "자 이하여야 합니다");
                yield new Action(type, null, null, null, null, null, null, null, null,
                        List.copyOf(new LinkedHashSet<>(action.recipients())), List.copyOf(new LinkedHashSet<>(action.userIds())), message);
            }
            default -> throw new IllegalArgumentException("모르는 액션입니다: " + action.type());
        };
    }

    /** SET_FIELD 값 검증 — 실행 때 같은 해석({@link #dateValue}·{@link #pointsValue})을 쓴다 */
    private static void checkValue(String field, Object value) {
        switch (field) {
            case "priority" -> {
                if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("우선순위 값을 고르세요");
            }
            case "flagged" -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("플래그 값은 true 또는 false입니다");
            }
            case "dueDate", "startDate" -> dateValue(value);
            case "storyPoints" -> pointsValue(value);
            default -> throw new IllegalArgumentException("바꿀 수 없는 필드입니다: " + field);
        }
    }

    public static LocalDate dateValue(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("날짜는 yyyy-MM-dd로 입력하세요");
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("날짜는 yyyy-MM-dd로 입력하세요");
        }
    }

    public static BigDecimal pointsValue(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number number)) throw new IllegalArgumentException("스토리 포인트는 수로 입력하세요");
        BigDecimal points = new BigDecimal(number.toString());
        if (points.signum() < 0 || points.compareTo(BigDecimal.valueOf(9999)) > 0) {
            throw new IllegalArgumentException("스토리 포인트는 0~9999 사이여야 합니다");
        }
        return points;
    }

    private static List<String> statuses(List<String> ids, Predicate<String> validStatus) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            String trimmed = id == null ? "" : id.trim();
            if (trimmed.isEmpty() || !validStatus.test(trimmed)) throw new IllegalArgumentException("이 프로젝트에 없는 상태입니다: " + id);
            out.add(trimmed);
        }
        return List.copyOf(out);
    }

    private static List<String> labels(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String label = value == null ? "" : value.trim();
            if (label.isEmpty()) throw new IllegalArgumentException("빈 라벨은 사용할 수 없습니다");
            if (label.length() > 80) throw new IllegalArgumentException("라벨은 80자 이하여야 합니다");
            out.add(label);
        }
        if (out.size() > 50) throw new IllegalArgumentException("라벨은 최대 50개까지 지정할 수 있습니다");
        return List.copyOf(out);
    }

    /** cron 정규화 — 5필드면 초(0)를 붙인다. 문법이 틀리거나 5분보다 자주 돌면 400 */
    public static String normalizeCron(String cron) {
        String text = cron == null ? "" : cron.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) throw new IllegalArgumentException("예약 시각(cron)을 입력하세요");
        String six = text.split(" ").length == 5 ? "0 " + text : text;
        CronExpression expression;
        try {
            expression = CronExpression.parse(six);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("예약 시각(cron) 문법이 틀렸습니다: " + text);
        }
        ZonedDateTime at = ZonedDateTime.now(ZONE);
        ZonedDateTime previous = expression.next(at);
        if (previous == null) throw new IllegalArgumentException("다시 오지 않는 예약 시각입니다: " + text);
        // 앞으로의 몇 회차 간격을 본다 — "매시 0분·1분"처럼 띄엄띄엄 몰린 식도 잡는다
        for (int i = 0; i < 50; i++) {
            ZonedDateTime next = expression.next(previous);
            if (next == null) break;
            if (Duration.between(previous, next).compareTo(MIN_SCHEDULE_GAP) < 0) {
                throw new IllegalArgumentException("예약은 " + MIN_SCHEDULE_GAP.toMinutes() + "분보다 자주 돌 수 없습니다");
            }
            previous = next;
        }
        return six;
    }

    /** 다음 실행 시각 */
    public static java.time.Instant nextRun(String cron, java.time.Instant after) {
        ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(ZONE));
        return next == null ? null : next.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
