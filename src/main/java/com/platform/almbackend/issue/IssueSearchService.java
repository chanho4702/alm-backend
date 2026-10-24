package com.platform.almbackend.issue;

import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import com.platform.almbackend.issue.dto.IssueAggregateResponse;
import com.platform.almbackend.issue.dto.IssuePageResponse;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.settings.StatusCategories;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 서버 검색·페이징 — 클라이언트 전량 필터(BACKLOG #5)를 대체한다. 조건은 전부 선택이고 AND로 묶는다.
 * 프로젝트를 지정하지 않으면 접근 가능한 프로젝트 전체를 뒤진다(교차 프로젝트 검색).
 *
 * <p>같은 조건으로 집계(GROUP BY)와 라벨 후보도 낸다 — 홈·대시보드가 통계 하나 그리려고 이슈 전량을
 * 페이지로 끌어오지 않게 한다(2026-10-16).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueSearchService {
    public static final String UNASSIGNED = "unassigned";
    /** 레지스트리에 없는 상태(정리 전 잔여값)를 분류 집계에서 모으는 키 */
    public static final String UNCATEGORIZED = "uncategorized";
    private static final int MAX_SIZE = 200;
    private static final Set<String> SORTS = Set.of("updated", "created", "due", "priority", "key", "title", "status", "type");
    private static final Set<String> GROUPS = Set.of("status", "priority", "assignee", "type", "statusCategory");

    private final IssueRepository issues;
    private final PriorityDefRepository priorityDefs;
    private final StatusDefRepository statusDefs;
    private final StatusCategoryRepository statusCategories;
    private final ProjectService projectService;
    private final PermissionClient permissions;
    private final IssueResponses responses;

    @PersistenceContext
    private EntityManager em;

    public record Criteria(
            List<Long> projectIds,
            String text,
            /** 상태 id 그대로 */
            List<String> statuses,
            /** 상태 분류(id·kind·이름) — 레지스트리로 상태 id로 풀어 statuses와 OR로 합친다 */
            List<String> statusCategories,
            /** 완료(kind=complete) 분류에 속한 상태를 뺀다 */
            boolean openOnly,
            List<String> priorities,
            List<String> types,
            /** 사용자 id 문자열 목록. "unassigned"는 미지정 */
            List<String> assignees,
            List<String> labels,
            List<Long> componentIds,
            Long sprintId,
            Long parentId,
            Long fixVersionId,
            /** 마감일 &lt; dueBefore(배타) */
            LocalDate dueBefore,
            /** dueFrom ≤ 마감일 ≤ dueTo(포함) */
            LocalDate dueFrom,
            LocalDate dueTo,
            /** 수정 시각 ≥ updatedSince(포함) */
            Instant updatedSince,
            String sort,
            String dir,
            /**
             * 트리아지 대기열(V35) — null이면 조건 없음(옛 동작), true면 대기열 이슈만, false면 대기열 밖만(보드·백로그 목록).
             * AQL {@code triage = true|false}와 같은 판정(triage_state 유무 — 보류 중인 SNOOZED도 대기열이다).
             */
            Boolean triage) {
        /** 트리아지 조건 없는 옛 생성자 */
        public Criteria(List<Long> projectIds, String text, List<String> statuses, List<String> statusCategories,
                        boolean openOnly, List<String> priorities, List<String> types, List<String> assignees,
                        List<String> labels, List<Long> componentIds, Long sprintId, Long parentId, Long fixVersionId,
                        LocalDate dueBefore, LocalDate dueFrom, LocalDate dueTo, Instant updatedSince, String sort, String dir) {
            this(projectIds, text, statuses, statusCategories, openOnly, priorities, types, assignees, labels, componentIds,
                    sprintId, parentId, fixVersionId, dueBefore, dueFrom, dueTo, updatedSince, sort, dir, null);
        }
    }

    public IssuePageResponse search(long userId, Criteria criteria, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_SIZE));
        int safePage = Math.max(0, page);
        String sort = criteria.sort() == null || criteria.sort().isBlank() ? "updated" : criteria.sort();
        if (!SORTS.contains(sort)) throw new IllegalArgumentException("지원하지 않는 정렬 기준입니다: " + sort);
        Scope scope = scope(userId, criteria.projectIds());
        if (scope.nothing()) return IssuePageResponse.of(List.of(), safePage, safeSize, 0);
        Registry registry = new Registry();
        Specification<Issue> spec = (root, query, cb) -> {
            // count 질의에는 정렬이 필요 없다 — 정렬용 레지스트리 조회도 아낀다
            if (!Long.class.equals(query.getResultType())) {
                query.orderBy(order(root, cb, sort, "asc".equalsIgnoreCase(criteria.dir()), registry));
            }
            return where(root, query, cb, criteria, scope, registry);
        };
        Page<Issue> result = issues.findAll(spec, PageRequest.of(safePage, safeSize));
        return IssuePageResponse.of(responses.of(result.getContent()), safePage, safeSize, result.getTotalElements());
    }

    /**
     * 조건에 맞는 이슈를 한 축으로 센다 — GROUP BY 한 번, 엔티티는 읽지 않는다. 권한 범위는 검색과 같다.
     * 상태 분류 집계도 DB에서는 상태로 묶고(분류 매핑을 SQL CASE로 바인딩하면 Postgres가 GROUP BY 식이
     * 다르다고 거부한다) 레지스트리로 합친다.
     */
    public IssueAggregateResponse aggregate(long userId, Criteria criteria, String groupBy) {
        if (groupBy == null || !GROUPS.contains(groupBy)) {
            throw new IllegalArgumentException("집계 기준은 status·priority·assignee·type·statusCategory 중 하나여야 합니다");
        }
        Scope scope = scope(userId, criteria.projectIds());
        if (scope.nothing()) return new IssueAggregateResponse(0, List.of());
        Registry registry = new Registry();

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Issue> root = query.from(Issue.class);
        Expression<?> key = switch (groupBy) {
            case "priority" -> root.get("priority");
            case "assignee" -> root.get("assigneeId");
            case "type" -> root.get("type");
            default -> root.get("status");
        };
        query.multiselect(key, cb.count(root))
                .where(where(root, query, cb, criteria, scope, registry))
                .groupBy(key);

        Map<String, Long> counts = new HashMap<>();
        Map<String, String> categoryOf = "statusCategory".equals(groupBy)
                ? StatusCategories.categoryOfStatus(registry.defs()) : Map.of();
        long total = 0;
        for (Tuple row : em.createQuery(query).getResultList()) {
            Object raw = row.get(0);
            long count = row.get(1, Long.class);
            String bucket = switch (groupBy) {
                case "assignee" -> raw == null ? UNASSIGNED : String.valueOf(raw);
                case "statusCategory" -> categoryOf.getOrDefault(String.valueOf(raw), UNCATEGORIZED);
                default -> String.valueOf(raw);
            };
            counts.merge(bucket, count, Long::sum);
            total += count;
        }
        List<IssueAggregateResponse.Bucket> buckets = counts.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .map(e -> new IssueAggregateResponse.Bucket(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(IssueAggregateResponse.Bucket::count).reversed()
                        .thenComparing(IssueAggregateResponse.Bucket::key))
                .toList();
        return new IssueAggregateResponse(total, buckets);
    }

    /** 프로젝트 라벨 후보 — 보관되지 않은 이슈의 라벨을 DISTINCT 한 번으로. 대소문자 무시 후 원문 순 */
    public List<String> labels(long userId, long projectId) {
        projectService.requireProject(projectId);
        projectService.require(userId, projectId, AlmAction.VIEW);
        return issues.findDistinctLabelsByProjectId(projectId).stream()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    public IssueResponse byKey(long userId, String key) {
        Issue issue = issues.findByKey(key.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + key));
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        return IssueResponse.from(issue);
    }

    /** 검색·집계 공통 WHERE. 라벨·컴포넌트는 EXISTS라 행이 불지 않는다 — DISTINCT 없이 count·정렬이 맞는다 */
    private Predicate where(Root<Issue> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                            Criteria c, Scope scope, Registry registry) {
        List<Predicate> where = new ArrayList<>();
        if (scope.include() != null) where.add(root.get("projectId").in(scope.include()));
        if (!scope.exclude().isEmpty()) where.add(cb.not(root.get("projectId").in(scope.exclude())));
        if (c.text() != null && !c.text().isBlank()) {
            // 사용자가 친 %·_는 글자 그대로다 — AQL의 ~와 같은 규칙(이스케이프 없으면 "%"가 전체를 잡는다)
            String like = "%" + LikePatterns.escape(c.text().trim().toLowerCase(Locale.ROOT)) + "%";
            where.add(cb.or(
                    cb.like(cb.lower(root.get("title")), like, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("description")), like, LikePatterns.ESCAPE),
                    cb.like(cb.lower(root.get("key")), like, LikePatterns.ESCAPE)));
        }
        if (notEmpty(c.statuses()) || notEmpty(c.statusCategories())) {
            // 상태 id ∪ 분류에 속한 상태 id — 분류에 상태가 없거나 모르는 분류면 그 몫은 아무것도 아니다
            Set<String> ids = new LinkedHashSet<>();
            if (notEmpty(c.statuses())) ids.addAll(c.statuses());
            if (notEmpty(c.statusCategories())) {
                for (String category : c.statusCategories()) {
                    ids.addAll(StatusCategories.statusIdsIn(registry.defs(),
                            StatusCategories.matchingCategoryIds(registry.categories(), category)));
                }
            }
            where.add(ids.isEmpty() ? cb.disjunction() : root.get("status").in(ids));
        }
        if (c.openOnly()) {
            List<String> complete = StatusCategories.statusIdsOfKind(
                    registry.categories(), registry.defs(), StatusCategories.KIND_COMPLETE);
            if (!complete.isEmpty()) where.add(cb.not(root.get("status").in(complete)));
        }
        if (notEmpty(c.priorities())) where.add(root.get("priority").in(c.priorities().stream().map(v -> v.toLowerCase(Locale.ROOT)).toList()));
        // 타입 id는 소문자 레지스트리 id — 옛 클라이언트의 enum 이름(BUG)도 받는다
        if (notEmpty(c.types())) where.add(root.get("type").in(c.types().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList()));
        if (notEmpty(c.assignees())) {
            List<Long> ids = c.assignees().stream()
                    .filter(a -> !UNASSIGNED.equals(a))
                    .map(Long::valueOf).toList();
            boolean unassigned = c.assignees().contains(UNASSIGNED);
            Predicate byIds = ids.isEmpty() ? null : root.get("assigneeId").in(ids);
            Predicate isNull = unassigned ? cb.isNull(root.get("assigneeId")) : null;
            if (byIds != null && isNull != null) where.add(cb.or(byIds, isNull));
            else if (byIds != null) where.add(byIds);
            else if (isNull != null) where.add(isNull);
        }
        if (notEmpty(c.labels())) {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Issue> correlated = sub.correlate(root);
            sub.select(cb.literal(1)).where(correlated.join("labels").in(c.labels()));
            where.add(cb.exists(sub));
        }
        if (notEmpty(c.componentIds())) {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<Issue> correlated = sub.correlate(root);
            sub.select(cb.literal(1)).where(correlated.join("componentIds").in(c.componentIds()));
            where.add(cb.exists(sub));
        }
        if (c.sprintId() != null) where.add(cb.equal(root.get("sprintId"), c.sprintId()));
        if (c.parentId() != null) where.add(cb.equal(root.get("parentId"), c.parentId()));
        if (c.fixVersionId() != null) where.add(cb.equal(root.get("fixVersionId"), c.fixVersionId()));
        // 마감일 조건은 마감일 없는 이슈를 절대 잡지 않는다(비교가 null이면 거짓)
        if (c.dueBefore() != null) where.add(cb.lessThan(root.get("dueDate"), c.dueBefore()));
        if (c.dueFrom() != null) where.add(cb.greaterThanOrEqualTo(root.get("dueDate"), c.dueFrom()));
        if (c.dueTo() != null) where.add(cb.lessThanOrEqualTo(root.get("dueDate"), c.dueTo()));
        if (c.updatedSince() != null) where.add(cb.greaterThanOrEqualTo(root.get("updatedAt"), c.updatedSince()));
        if (c.triage() != null) {
            where.add(c.triage() ? cb.isNotNull(root.get("triageState")) : cb.isNull(root.get("triageState")));
        }
        return cb.and(where.toArray(Predicate[]::new));
    }

    /** 정렬 — 기준마다 끝에 id asc를 붙여 페이지 경계가 흔들리지 않게 한다 */
    private List<Order> order(Root<Issue> root, CriteriaBuilder cb, String sort, boolean asc, Registry registry) {
        List<Order> orders = new ArrayList<>();
        switch (sort) {
            case "created" -> orders.add(direction(cb, root.get("createdAt"), asc));
            case "due" -> {
                // 마감일 없음은 방향과 무관하게 맨 뒤
                orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(root.get("dueDate")), 1).otherwise(0)));
                orders.add(direction(cb, root.get("dueDate"), asc));
            }
            case "key" -> orders.add(direction(cb, root.get("issueNumber"), asc));
            case "priority" -> orders.add(direction(cb, priorityRank(cb, root, registry), asc));
            case "title" -> orders.add(direction(cb, cb.lower(root.get("title")), asc));
            case "type" -> orders.add(direction(cb, root.get("type"), asc));
            case "status" -> {
                orders.add(direction(cb, statusRank(cb, root, registry), asc));
                orders.add(direction(cb, root.get("status"), asc));
            }
            default -> orders.add(direction(cb, root.get("updatedAt"), asc));
        }
        orders.add(cb.asc(root.get("id")));
        return orders;
    }

    private static Order direction(CriteriaBuilder cb, Expression<?> expression, boolean asc) {
        return asc ? cb.asc(expression) : cb.desc(expression);
    }

    /**
     * 검색 범위 — include: 볼 프로젝트(null = 전역 접근이라 제한 없음), exclude: 휴지통 프로젝트.
     * 휴지통 이동은 grant를 회수하지 않으므로 접근 범위만으로는 휴지통이 빠지지 않는다(감사 A-02).
     */
    record Scope(List<Long> include, List<Long> exclude) {
        boolean nothing() { return include != null && include.isEmpty(); }
    }

    /** 요청한 프로젝트 ∩ 접근 범위 − 휴지통 */
    private Scope scope(long userId, List<Long> requested) {
        if (notEmpty(requested)) {
            // 명시한 프로젝트는 하나씩 권한·상태를 본다 — 휴지통이면 require가 404
            for (long projectId : requested) projectService.require(userId, projectId, AlmAction.VIEW);
            return new Scope(requested, List.of());
        }
        AccessScope access = permissions.accessibleProjects(userId);
        List<Long> trashed = projectService.trashedProjectIds();
        if (access.all()) return new Scope(null, trashed);
        Set<Long> ids = new LinkedHashSet<>(access.projectIds());
        trashed.forEach(ids::remove);
        return new Scope(List.copyOf(ids), List.of());
    }

    /** 레지스트리 sort_order(높음→낮음)로 정렬 — 모르는 값은 맨 뒤 */
    private Expression<Integer> priorityRank(CriteriaBuilder cb, Root<Issue> root, Registry registry) {
        List<PriorityDef> defs = registry.priorities();
        var cases = cb.<Integer>selectCase();
        for (PriorityDef def : defs) cases = cases.when(cb.equal(root.get("priority"), def.getId()), def.getSortOrder());
        return cases.otherwise(defs.size() + 1);
    }

    /** 상태 분류 kind 순(할 일 → 진행 → 완료) — 레지스트리에 없는 상태는 맨 뒤 */
    private Expression<Integer> statusRank(CriteriaBuilder cb, Root<Issue> root, Registry registry) {
        Map<String, String> kindOfCategory = new HashMap<>();
        for (StatusCategory category : registry.categories()) kindOfCategory.put(category.getId(), category.getKind());
        var cases = cb.<Integer>selectCase();
        for (StatusDef def : registry.defs()) {
            cases = cases.when(cb.equal(root.get("status"), def.getId()),
                    StatusCategories.kindRank(kindOfCategory.get(def.getCategoryId())));
        }
        return cases.otherwise(StatusCategories.kindRank(null));
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /** 요청 한 번 동안의 상태 레지스트리 스냅샷 — 필요할 때만 읽는다(분류 조건·openOnly·상태/우선순위 정렬·분류 집계) */
    private final class Registry {
        private List<StatusDef> defs;
        private List<StatusCategory> categories;
        private List<PriorityDef> priorities;

        List<StatusDef> defs() {
            if (defs == null) defs = statusDefs.findAllByOrderByIdAsc();
            return defs;
        }

        List<StatusCategory> categories() {
            if (categories == null) categories = statusCategories.findAllByOrderBySortOrderAsc();
            return categories;
        }

        List<PriorityDef> priorities() {
            if (priorities == null) priorities = priorityDefs.findAllByOrderBySortOrderAsc();
            return priorities;
        }
    }
}
