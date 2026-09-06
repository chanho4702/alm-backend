package com.platform.almbackend.search.aql;

import com.platform.almbackend.directory.DirectoryMember;
import com.platform.almbackend.directory.MemberDirectory;
import com.platform.almbackend.domain.Component;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.domain.ProjectVersion;
import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.repository.ComponentRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectVersionRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import com.platform.almbackend.search.aql.AqlAst.Value;
import com.platform.almbackend.search.aql.AqlAst.ValueKind;
import com.platform.common.error.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AQL 값 해석 — 사람이 쓴 이름을 DB에 있는 값으로 바꾼다. 상태·타입·우선순위는 설정 레지스트리,
 * 프로젝트·컴포넌트·스프린트·버전은 이름/키, 사람은 org 디렉터리가 원장이다.
 *
 * <p>규칙 셋:
 * <ol>
 *   <li><b>이름이 여럿에 맞으면 전부</b> 쓴다(IN으로 넓힌다). 컴포넌트·스프린트·버전 이름은 프로젝트
 *       안에서만 유일해서, 프로젝트 조건이 없으면 같은 이름이 여럿일 수 있다.</li>
 *   <li><b>못 찾으면 400</b>이다. 오타를 빈 결과로 답하면 "왜 안 나오지"로 끝난다 — 지라도 같다.</li>
 *   <li><b>사람 이름은 프로젝트 조건과 무관하게</b> 푼다. {@code project = ALM AND 담당자 = 김찬호}에서
 *       김찬호가 ALM 사람인지는 따지지 않는다.</li>
 * </ol>
 *
 * <p>사람 이름 해석의 한계: org의 {@code LookupMembers}는 <b>이메일과 이메일 local-part만</b> 본다 —
 * 표시 이름으로 찾는 창구가 없고, 전원을 훑는 창구도 없다. 그래서 표시 이름은 이슈에 실제로 등장하는
 * 담당자·보고자 id를 {@code GetMembers}로 읽어 그 안에서 정확히(부분일치 아님) 맞춘다. 이슈에 한 번도
 * 안 나온 사람은 어차피 검색 결과에도 없다.
 */
@Service
@RequiredArgsConstructor
public class AqlResolver {

    /**
     * 상대 날짜·{@code startOfDay()} 같은 경계를 계산하는 기준 시간대. 서버가 어디서 돌든 사용자가
     * 보는 "오늘"과 같아야 해서 UTC가 아니라 한국 시간으로 고정한다.
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** {@code -7d} {@code +1M} {@code -2w} {@code +3y} — 단위는 일/주/월/년 */
    private static final Pattern RELATIVE = Pattern.compile("([+-])(\\d+)([dwmyDWMY])");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** 사람 이름 후보를 한 번에 몇 명까지 읽을지 — org 왕복은 200명 단위로 끊긴다 */
    private static final int MAX_PARTICIPANTS = 1000;

    private static final Map<String, IssueResolution> RESOLUTION_ALIASES = Map.of(
            "완료", IssueResolution.DONE,
            "하지않음", IssueResolution.WONT_DO,
            "안함", IssueResolution.WONT_DO,
            "중복", IssueResolution.DUPLICATE,
            "재현불가", IssueResolution.CANNOT_REPRODUCE);

    private final StatusDefRepository statuses;
    private final StatusCategoryRepository statusCategories;
    private final IssueTypeDefRepository issueTypes;
    private final PriorityDefRepository priorities;
    private final ProjectRepository projects;
    private final ComponentRepository components;
    private final SprintRepository sprints;
    private final ProjectVersionRepository versions;
    private final IssueRepository issues;
    private final MemberDirectory directory;

    /**
     * 질의 한 번 동안 쓸 해석기를 연다. 레지스트리 스냅샷은 여기서 한 번만 읽는다 — 같은 질의 안에서
     * 상태 표를 여러 번 읽지 않는다.
     *
     * @param userId    {@code currentUser()}가 가리키는 사람(JWT sub)
     * @param scopeIds  볼 수 있는 프로젝트. {@code null}이면 제한 없음(전역 접근)
     */
    public Session open(long userId, List<Long> scopeIds) {
        return new Session(userId, scopeIds);
    }

    /** 날짜 값 하나 — {@code dateOnly}면 "그 날 하루"를 뜻해 비교가 경계를 쓴다 */
    public record Moment(ZonedDateTime at, boolean dateOnly) {

        public Instant start() {
            return dateOnly ? at.toLocalDate().atStartOfDay(ZONE).toInstant() : at.toInstant();
        }

        /** 그 날(또는 그 순간) 바로 다음 — {@code <=}, {@code >}가 쓰는 열린 끝 */
        public Instant endExclusive() {
            return dateOnly ? at.toLocalDate().plusDays(1).atStartOfDay(ZONE).toInstant() : at.toInstant();
        }

        public LocalDate date() {
            return at.toLocalDate();
        }
    }

    /** 한 질의 동안의 해석기. 스레드 안전하지 않다 — 요청 하나가 처음부터 끝까지 혼자 쓴다. */
    public final class Session {

        private final long userId;
        private final List<Long> scopeIds;
        private final ZonedDateTime now = ZonedDateTime.now(ZONE);

        private List<StatusDef> statusCache;
        private List<StatusCategory> categoryCache;
        private List<IssueTypeDef> typeCache;
        private List<PriorityDef> priorityCache;
        private Map<Long, DirectoryMember> participantCache;

        private Session(long userId, List<Long> scopeIds) {
            this.userId = userId;
            this.scopeIds = scopeIds;
        }

        public long userId() {
            return userId;
        }

        // ── 레지스트리 ──

        public List<StatusDef> statusDefs() {
            if (statusCache == null) statusCache = statuses.findAllByOrderByIdAsc();
            return statusCache;
        }

        public List<StatusCategory> categories() {
            if (categoryCache == null) categoryCache = statusCategories.findAllByOrderBySortOrderAsc();
            return categoryCache;
        }

        public List<IssueTypeDef> typeDefs() {
            if (typeCache == null) typeCache = issueTypes.findAllByOrderBySortOrderAsc();
            return typeCache;
        }

        public List<PriorityDef> priorityDefs() {
            if (priorityCache == null) priorityCache = priorities.findAllByOrderBySortOrderAsc();
            return priorityCache;
        }

        // ── 값 → DB 값 ──

        /** 상태 이름·id → 상태 id 목록 */
        public List<String> statusIds(Value value) {
            String text = text(value);
            List<String> ids = statusDefs().stream()
                    .filter(def -> def.getId().equalsIgnoreCase(text) || def.getName().equalsIgnoreCase(text))
                    .map(StatusDef::getId).toList();
            if (ids.isEmpty()) throw AqlException.at(value.position(), "상태를 모릅니다: " + text);
            return ids;
        }

        /** 상태 분류(new/active/complete·이름·id) → 그 분류에 속한 상태 id 목록 */
        public List<String> statusIdsOfCategory(Value value) {
            String text = text(value);
            List<String> categoryIds = categories().stream()
                    .filter(c -> c.getKind().equalsIgnoreCase(text)
                            || c.getId().equalsIgnoreCase(text)
                            || c.getName().equalsIgnoreCase(text))
                    .map(StatusCategory::getId).toList();
            if (categoryIds.isEmpty()) {
                throw AqlException.at(value.position(), "상태분류를 모릅니다: " + text,
                        "new", "active", "complete");
            }
            List<String> ids = statusDefs().stream()
                    .filter(def -> categoryIds.contains(def.getCategoryId()))
                    .map(StatusDef::getId).toList();
            // 분류는 있는데 상태가 하나도 없으면 "아무것도 아님"이다 — 조건이 전부 걸러내도록 둔다
            return ids;
        }

        public List<String> typeIds(Value value) {
            String text = text(value);
            List<String> ids = typeDefs().stream()
                    .filter(def -> def.getId().equalsIgnoreCase(text) || def.getName().equalsIgnoreCase(text))
                    .map(IssueTypeDef::getId).toList();
            if (ids.isEmpty()) throw AqlException.at(value.position(), "이슈 타입을 모릅니다: " + text);
            return ids;
        }

        public List<String> priorityIds(Value value) {
            return List.of(priorityDef(value).getId());
        }

        public PriorityDef priorityDef(Value value) {
            String text = text(value);
            return priorityDefs().stream()
                    .filter(def -> def.getId().equalsIgnoreCase(text) || def.getName().equalsIgnoreCase(text))
                    .findFirst()
                    .orElseThrow(() -> AqlException.at(value.position(), "우선순위를 모릅니다: " + text));
        }

        /** 프로젝트 키·이름 → 프로젝트 id. 볼 수 없는 프로젝트는 처음부터 없는 것으로 본다 */
        public List<Long> projectIds(Value value) {
            String text = text(value);
            List<Long> ids = new ArrayList<>();
            projects.findByKeyIgnoreCase(text).map(Project::getId).ifPresent(ids::add);
            for (Project project : projects.findByNameIgnoreCase(text)) {
                if (!ids.contains(project.getId())) ids.add(project.getId());
            }
            if (scopeIds != null) ids.removeIf(id -> !scopeIds.contains(id));
            if (ids.isEmpty()) throw AqlException.at(value.position(), "프로젝트를 찾을 수 없습니다: " + text);
            return ids;
        }

        /** 컴포넌트 이름 또는 id — 이름은 프로젝트 안에서만 유일해 여럿일 수 있다 */
        public List<Long> componentIds(Value value) {
            String text = text(value);
            List<Long> ids = new ArrayList<>();
            if (isDigits(text)) ids.add(Long.parseLong(text));
            for (Component component : components.findByNameIgnoreCase(text)) {
                if (!ids.contains(component.getId())) ids.add(component.getId());
            }
            if (ids.isEmpty()) throw AqlException.at(value.position(), "컴포넌트를 찾을 수 없습니다: " + text);
            return ids;
        }

        /** 스프린트 이름·id 또는 {@code openSprints()} */
        public List<Long> sprintIds(Value value) {
            if (value.function("openSprints")) {
                return sprints.findByState(SprintState.ACTIVE).stream().map(Sprint::getId).toList();
            }
            String text = text(value);
            List<Long> ids = new ArrayList<>();
            if (isDigits(text)) ids.add(Long.parseLong(text));
            for (Sprint sprint : sprints.findByNameIgnoreCase(text)) {
                if (!ids.contains(sprint.getId())) ids.add(sprint.getId());
            }
            if (ids.isEmpty()) throw AqlException.at(value.position(), "스프린트를 찾을 수 없습니다: " + text);
            return ids;
        }

        public List<Long> versionIds(Value value) {
            String text = text(value);
            List<Long> ids = new ArrayList<>();
            if (isDigits(text)) ids.add(Long.parseLong(text));
            for (ProjectVersion version : versions.findByNameIgnoreCase(text)) {
                if (!ids.contains(version.getId())) ids.add(version.getId());
            }
            if (ids.isEmpty()) throw AqlException.at(value.position(), "버전을 찾을 수 없습니다: " + text);
            return ids;
        }

        public List<IssueResolution> resolutions(Value value) {
            String text = text(value);
            String normalized = text.replace("-", "_").replace(" ", "").toUpperCase(Locale.ROOT);
            for (IssueResolution resolution : IssueResolution.values()) {
                if (resolution.name().equals(normalized)) return List.of(resolution);
            }
            IssueResolution alias = RESOLUTION_ALIASES.get(text.replace(" ", ""));
            if (alias != null) return List.of(alias);
            throw AqlException.at(value.position(), "해결 값을 모릅니다: " + text,
                    "DONE", "WONT_DO", "DUPLICATE", "CANNOT_REPRODUCE");
        }

        /** 상위 이슈 — 키({@code ALM-3}) 또는 숫자 id */
        public List<Long> parentIds(Value value) {
            String text = text(value);
            if (isDigits(text)) return List.of(Long.parseLong(text));
            return issues.findByKey(text.toUpperCase(Locale.ROOT))
                    .map(issue -> List.of(issue.getId()))
                    .orElseThrow(() -> AqlException.at(value.position(), "이슈를 찾을 수 없습니다: " + text));
        }

        /**
         * 사람 — {@code currentUser()}·숫자 id·이메일·표시 이름. 이름이 둘 이상에 맞으면 전부 쓴다.
         *
         * @throws ServiceUnavailableException org 디렉터리를 못 읽었을 때(503). "그런 사람 없다"로 바꿔
         *         말하면 조용히 다른 결과를 준다.
         */
        public List<Long> userIds(Value value) {
            if (value.function("currentUser")) return List.of(userId);
            String text = text(value);
            if (isDigits(text)) return List.of(Long.parseLong(text));

            List<Long> ids = new ArrayList<>();
            // 이메일·local-part는 org에 바로 물어본다(이슈에 없는 사람도 찾힌다)
            DirectoryMember exact = directory.lookupByEmail(List.of(text)).get(text.toLowerCase(Locale.ROOT));
            // 이메일로 찾혔으면 거기서 끝난다 — 이슈 참가자를 전수 읽을 이유가 없다(리뷰 M2)
            if (exact != null) return List.of(exact.id());

            for (DirectoryMember member : participants().values()) {
                if (matches(member, text)) ids.add(member.id());
            }
            if (ids.isEmpty()) throw AqlException.at(value.position(), "사용자를 찾을 수 없습니다: " + text);
            return ids;
        }

        private boolean matches(DirectoryMember member, String text) {
            if (member.displayName().equalsIgnoreCase(text)) return true;
            if (!member.email().isEmpty()) {
                if (member.email().equalsIgnoreCase(text)) return true;
                int at = member.email().indexOf('@');
                return at > 0 && member.email().substring(0, at).equalsIgnoreCase(text);
            }
            return false;
        }

        private Map<Long, DirectoryMember> participants() {
            if (participantCache != null) return participantCache;
            List<Long> ids = issues.findParticipantUserIds();
            if (ids.size() > MAX_PARTICIPANTS) ids = ids.subList(0, MAX_PARTICIPANTS);
            MemberDirectory.Lookup lookup = directory.lookup(new LinkedHashSet<>(ids));
            if (!lookup.ok()) {
                throw new ServiceUnavailableException("사용자 디렉터리를 읽을 수 없어 이름을 확인하지 못했습니다");
            }
            participantCache = lookup.members();
            return participantCache;
        }

        // ── 날짜·수·참거짓 ──

        public Moment moment(Value value) {
            if (value.kind() == ValueKind.FUNCTION) return functionMoment(value);
            String text = text(value);
            Matcher relative = RELATIVE.matcher(text);
            if (relative.matches()) {
                long amount = Long.parseLong(relative.group(2));
                if ("-".equals(relative.group(1))) amount = -amount;
                ZonedDateTime at = switch (Character.toLowerCase(relative.group(3).charAt(0))) {
                    case 'd' -> now.plusDays(amount);
                    case 'w' -> now.plusWeeks(amount);
                    case 'm' -> now.plusMonths(amount);
                    default -> now.plusYears(amount);
                };
                return new Moment(at, false);
            }
            try {
                return new Moment(LocalDate.parse(text).atStartOfDay(ZONE), true);
            } catch (DateTimeParseException ignored) {
                // 다음 형식을 시도한다
            }
            for (String pattern : List.of(" ", "T")) {
                try {
                    return new Moment(LocalDateTime.parse(text.replace(pattern, "T")).atZone(ZONE), false);
                } catch (DateTimeParseException ignored) {
                    // 다음 형식을 시도한다
                }
            }
            throw AqlException.at(value.position(), "날짜 형식이 아닙니다: " + text,
                    "2026-09-06", "\"2026-09-06 14:00\"", "-7d", "startOfWeek()");
        }

        private Moment functionMoment(Value value) {
            long offset = value.args().isEmpty() ? 0 : longArg(value);
            ZonedDateTime day = now.plusDays(offset);
            return switch (value.text().toLowerCase(Locale.ROOT)) {
                case "now" -> new Moment(now, false);
                case "startofday" -> new Moment(day.toLocalDate().atStartOfDay(ZONE), false);
                case "endofday" -> new Moment(day.toLocalDate().plusDays(1).atStartOfDay(ZONE), false);
                case "startofweek" -> new Moment(startOfWeek(now.plusWeeks(offset)), false);
                case "endofweek" -> new Moment(startOfWeek(now.plusWeeks(offset)).plusWeeks(1), false);
                case "startofmonth" -> new Moment(startOfMonth(now.plusMonths(offset)), false);
                case "endofmonth" -> new Moment(startOfMonth(now.plusMonths(offset)).plusMonths(1), false);
                case "startofyear" -> new Moment(startOfYear(now.plusYears(offset)), false);
                case "endofyear" -> new Moment(startOfYear(now.plusYears(offset)).plusYears(1), false);
                default -> throw AqlException.at(value.position(), "함수를 모릅니다: " + value.text() + "()",
                        "now()", "startOfDay()", "startOfWeek()", "startOfMonth()", "endOfDay()");
            };
        }

        private long longArg(Value value) {
            Value arg = value.args().get(0);
            try {
                return Long.parseLong(arg.text());
            } catch (NumberFormatException e) {
                throw AqlException.at(arg.position(), "정수가 필요합니다: " + arg.text(), "-1", "0", "1");
            }
        }

        public BigDecimal number(Value value) {
            try {
                return new BigDecimal(text(value));
            } catch (NumberFormatException e) {
                throw AqlException.at(value.position(), "숫자가 아닙니다: " + text(value), "3", "3.5");
            }
        }

        public boolean bool(Value value) {
            String text = text(value);
            if (text.equalsIgnoreCase("true") || text.equals("1")) return true;
            if (text.equalsIgnoreCase("false") || text.equals("0")) return false;
            throw AqlException.at(value.position(), "true 또는 false여야 합니다: " + text, "true", "false");
        }

        /** 함수는 값이 아니다 — 여기서 걸러야 {@code status = currentUser()} 같은 걸 조용히 넘기지 않는다 */
        public String text(Value value) {
            if (value.kind() == ValueKind.FUNCTION) {
                throw AqlException.at(value.position(), "이 필드에는 함수를 쓸 수 없습니다: " + value.text() + "()");
            }
            return value.text();
        }
    }

    private static ZonedDateTime startOfWeek(ZonedDateTime at) {
        // 주의 시작은 월요일 — 지라 기본과 같다
        return at.toLocalDate().minusDays(at.getDayOfWeek().getValue() - 1L).atStartOfDay(ZONE);
    }

    private static ZonedDateTime startOfMonth(ZonedDateTime at) {
        return at.toLocalDate().withDayOfMonth(1).atStartOfDay(ZONE);
    }

    private static ZonedDateTime startOfYear(ZonedDateTime at) {
        return at.toLocalDate().withDayOfYear(1).atStartOfDay(ZONE);
    }

    private static boolean isDigits(String text) {
        return DIGITS.matcher(text).matches();
    }

    /** 미사용 경고를 피하려고 남겨 둔 상수 접근자 — 테스트가 경계 계산 기준을 확인할 때 쓴다 */
    public static Set<String> dateFunctions() {
        return Set.of("now", "startOfDay", "endOfDay", "startOfWeek", "endOfWeek",
                "startOfMonth", "endOfMonth", "startOfYear", "endOfYear");
    }
}
