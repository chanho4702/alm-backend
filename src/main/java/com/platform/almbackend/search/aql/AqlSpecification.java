package com.platform.almbackend.search.aql;

import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.search.aql.AqlAst.Node;
import com.platform.almbackend.search.aql.AqlAst.Value;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AST → JPA Specification. 루트는 {@link AqlIssueRow}다 — {@code Issue}에 걸린
 * {@code @SQLRestriction("archived_at is null")} 때문에 보관함을 볼 수 없어서다(이유는 그 클래스 주석).
 *
 * <p>세 가지를 항상 지킨다.
 * <ul>
 *   <li><b>접근 범위</b>: 볼 수 있는 프로젝트 조건을 언제나 AND로 더한다. 질의에 뭘 쓰든 못 보는 건 안 나온다.</li>
 *   <li><b>보관 제외 기본</b>: {@code archived}를 쓰지 않은 질의는 보관된 이슈를 뺀다.</li>
 *   <li><b>NULL 안전</b>: 각 잎 술어는 참/거짓만 낸다(NULL을 내지 않는다). 그래야 {@code NOT}이
 *       정확한 여집합이 된다 — SQL 3값 논리로는 {@code NOT (assignee = 3)}이 미지정 이슈를 조용히 버린다.</li>
 * </ul>
 */
final class AqlSpecification {

    /** LIKE 패턴의 이스케이프 문자 — 질의에 그대로 실려 보낸다 */
    private static final char LIKE_ESCAPE = '\\';

    private final AqlResolver.Session session;
    private final List<Long> scopeIds;

    private Root<AqlIssueRow> root;
    private CriteriaQuery<?> query;
    private CriteriaBuilder cb;

    AqlSpecification(AqlResolver.Session session, List<Long> scopeIds) {
        this.session = session;
        this.scopeIds = scopeIds;
    }

    Specification<AqlIssueRow> build(AqlAst.Query parsed) {
        return (root, query, cb) -> {
            this.root = root;
            this.query = query;
            this.cb = cb;

            List<Predicate> where = new ArrayList<>();
            if (scopeIds != null) where.add(root.get("projectId").in(scopeIds));
            if (!mentionsArchived(parsed.where())) where.add(cb.isNull(root.get("archivedAt")));
            if (parsed.where() != null) where.add(node(parsed.where()));

            query.orderBy(orders(parsed.orderBy()));
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** {@code archived}를 한 번이라도 쓰면 기본 제외를 끈다 — 조건이 스스로 말하게 둔다 */
    private static boolean mentionsArchived(Node node) {
        return switch (node) {
            case null -> false;
            case AqlAst.And and -> and.children().stream().anyMatch(AqlSpecification::mentionsArchived);
            case AqlAst.Or or -> or.children().stream().anyMatch(AqlSpecification::mentionsArchived);
            case AqlAst.Not not -> mentionsArchived(not.child());
            case AqlAst.Compare compare -> isArchived(compare.field());
            case AqlAst.InList in -> isArchived(in.field());
            case AqlAst.EmptyCheck empty -> isArchived(empty.field());
        };
    }

    private static boolean isArchived(String written) {
        String lower = written.trim().toLowerCase(Locale.ROOT);
        return lower.equals("archived") || lower.equals("보관");
    }

    // ── 트리 ──

    private Predicate node(Node node) {
        return switch (node) {
            case AqlAst.And and -> cb.and(and.children().stream().map(this::node).toArray(Predicate[]::new));
            case AqlAst.Or or -> cb.or(or.children().stream().map(this::node).toArray(Predicate[]::new));
            case AqlAst.Not not -> cb.not(node(not.child()));
            case AqlAst.Compare compare -> compare(compare);
            case AqlAst.InList in -> {
                AqlFields.Field field = AqlFields.require(in.field(), in.fieldPosition());
                AqlFields.requireOperator(field, in.negated() ? "NOT IN" : "IN", in.operatorPosition());
                Predicate matches = matches(field, in.values(), in.fieldPosition());
                yield in.negated() ? excludeEmpty(field, matches) : matches;
            }
            case AqlAst.EmptyCheck empty -> {
                AqlFields.Field field = AqlFields.require(empty.field(), empty.fieldPosition());
                AqlFields.requireOperator(field, empty.negated() ? "IS NOT EMPTY" : "IS EMPTY",
                        empty.operatorPosition());
                Predicate isEmpty = isEmpty(field, empty.fieldPosition());
                yield empty.negated() ? cb.not(isEmpty) : isEmpty;
            }
        };
    }

    private Predicate compare(AqlAst.Compare compare) {
        AqlFields.Field field = AqlFields.require(compare.field(), compare.fieldPosition());
        String operator = compare.operator();
        AqlFields.requireOperator(field, operator, compare.operatorPosition());
        Value value = compare.value();
        return switch (operator) {
            case "=" -> matches(field, List.of(value), compare.fieldPosition());
            case "!=" -> excludeEmpty(field, matches(field, List.of(value), compare.fieldPosition()));
            case "~" -> contains(field, value, compare.fieldPosition());
            case "!~" -> excludeEmpty(field, contains(field, value, compare.fieldPosition()));
            default -> ordered(field, operator, value, compare.fieldPosition());
        };
    }

    // ── 같음(=, IN) ──

    private Predicate matches(AqlFields.Field field, List<Value> values, int position) {
        return switch (field.name()) {
            case "project" -> in(root.get("projectId"), flat(values, session::projectIds), false);
            case "key" -> in(root.get("key"), values.stream()
                    .map(v -> (Object) session.text(v).toUpperCase(Locale.ROOT)).toList(), false);
            case "type" -> in(root.get("type"), flat(values, session::typeIds), false);
            case "status" -> in(root.get("status"), flat(values, session::statusIds), false);
            case "statusCategory" -> in(root.get("status"), flat(values, session::statusIdsOfCategory), false);
            case "priority" -> in(root.get("priority"), flat(values, session::priorityIds), false);
            case "assignee" -> in(root.get("assigneeId"), flat(values, session::userIds), true);
            case "reporter" -> in(root.get("reporterId"), flat(values, session::userIds), false);
            // 라벨은 지라처럼 정확히·대소문자를 가려서 맞춘다(의도) — 레지스트리가 없는 자유 문자열이라
            // 접어서 맞추면 서로 다른 라벨이 한 덩어리로 보인다
            case "labels" -> existsIn("labels", values.stream().map(session::text).map(t -> (Object) t).toList());
            case "component" -> existsIn("componentIds", flat(values, session::componentIds));
            case "sprint" -> in(root.get("sprintId"), flat(values, session::sprintIds), true);
            case "fixVersion" -> in(root.get("fixVersionId"), flat(values, session::versionIds), true);
            case "resolution" -> in(root.get("resolution"), flat(values, session::resolutions), true);
            case "parent" -> in(root.get("parentId"), flat(values, session::parentIds), true);
            case "summary" -> anyOf(values, v -> cb.equal(cb.lower(root.get("title")),
                    session.text(v).toLowerCase(Locale.ROOT)));
            case "archived" -> anyOf(values, v -> session.bool(v)
                    ? cb.isNotNull(root.get("archivedAt"))
                    : cb.isNull(root.get("archivedAt")));
            case "due" -> anyOf(values, v -> cb.and(cb.isNotNull(root.get("dueDate")),
                    cb.equal(root.<LocalDate>get("dueDate"), session.moment(v).date())));
            case "estimate" -> anyOf(values, v -> cb.and(cb.isNotNull(root.get("estimateHours")),
                    cb.equal(root.<BigDecimal>get("estimateHours"), session.number(v))));
            case "created", "updated" -> anyOf(values, v -> instantWindow(instantPath(field), session.moment(v)));
            default -> throw AqlException.at(position, "'='를 쓸 수 없는 필드입니다: " + field.name());
        };
    }

    /** 하루짜리 값이면 그 날 전체, 시각이면 정확히 그 순간 */
    private Predicate instantWindow(Path<Instant> path, AqlResolver.Moment moment) {
        if (!moment.dateOnly()) return cb.equal(path, moment.start());
        return cb.and(
                cb.greaterThanOrEqualTo(path, moment.start()),
                cb.lessThan(path, moment.endExclusive()));
    }

    // ── 포함(~) ──

    private Predicate contains(AqlFields.Field field, Value value, int position) {
        String like = "%" + escapeLike(session.text(value).trim().toLowerCase(Locale.ROOT)) + "%";
        return switch (field.name()) {
            case "text" -> cb.or(
                    like(root.get("title"), like),
                    like(root.get("description"), like));
            case "summary" -> like(root.get("title"), like);
            case "key" -> like(root.get("key"), like);
            default -> throw AqlException.at(position, "'~'는 텍스트 필드에만 쓸 수 있습니다 (" + field.name() + ")");
        };
    }

    private Predicate like(Path<String> path, String pattern) {
        return cb.like(cb.lower(path), pattern, LIKE_ESCAPE);
    }

    /**
     * 사용자가 친 {@code %}·{@code _}는 글자 그대로다. 안 바꾸면 {@code text ~ "%"}가
     * 전체를 매치하고 {@code _}가 아무 한 글자로 번진다(리뷰 I2).
     */
    private static String escapeLike(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == LIKE_ESCAPE || c == '%' || c == '_') escaped.append(LIKE_ESCAPE);
            escaped.append(c);
        }
        return escaped.toString();
    }

    // ── 순서 비교(<, <=, >, >=) ──

    private Predicate ordered(AqlFields.Field field, String operator, Value value, int position) {
        switch (field.name()) {
            case "priority" -> {
                // sort_order는 1이 가장 중요하다 — `priority >= high`는 "high 이상으로 중요"라 rank는 작거나 같다
                PriorityDef target = session.priorityDef(value);
                Expression<Integer> rank = priorityRank();
                return switch (operator) {
                    case ">=" -> cb.lessThanOrEqualTo(rank, target.getSortOrder());
                    case ">" -> cb.lessThan(rank, target.getSortOrder());
                    case "<=" -> cb.greaterThanOrEqualTo(rank, target.getSortOrder());
                    default -> cb.greaterThan(rank, target.getSortOrder());
                };
            }
            case "created", "updated" -> {
                Path<Instant> path = instantPath(field);
                AqlResolver.Moment moment = session.moment(value);
                return switch (operator) {
                    case "<" -> cb.lessThan(path, moment.start());
                    case "<=" -> moment.dateOnly()
                            ? cb.lessThan(path, moment.endExclusive())
                            : cb.lessThanOrEqualTo(path, moment.start());
                    case ">" -> moment.dateOnly()
                            ? cb.greaterThanOrEqualTo(path, moment.endExclusive())
                            : cb.greaterThan(path, moment.start());
                    default -> cb.greaterThanOrEqualTo(path, moment.start());
                };
            }
            case "due" -> {
                LocalDate date = session.moment(value).date();
                Path<LocalDate> path = root.get("dueDate");
                Predicate present = cb.isNotNull(path);
                return cb.and(present, switch (operator) {
                    case "<" -> cb.lessThan(path, date);
                    case "<=" -> cb.lessThanOrEqualTo(path, date);
                    case ">" -> cb.greaterThan(path, date);
                    default -> cb.greaterThanOrEqualTo(path, date);
                });
            }
            case "estimate" -> {
                BigDecimal amount = session.number(value);
                Path<BigDecimal> path = root.get("estimateHours");
                return cb.and(cb.isNotNull(path), switch (operator) {
                    case "<" -> cb.lessThan(path, amount);
                    case "<=" -> cb.lessThanOrEqualTo(path, amount);
                    case ">" -> cb.greaterThan(path, amount);
                    default -> cb.greaterThanOrEqualTo(path, amount);
                });
            }
            default -> throw AqlException.at(position,
                    "'" + operator + "'는 날짜·숫자 필드에만 쓸 수 있습니다 (" + field.name() + ")");
        }
    }

    // ── 비어 있음 ──

    private Predicate isEmpty(AqlFields.Field field, int position) {
        return switch (field.name()) {
            case "assignee" -> cb.isNull(root.get("assigneeId"));
            case "sprint" -> cb.isNull(root.get("sprintId"));
            case "fixVersion" -> cb.isNull(root.get("fixVersionId"));
            case "resolution" -> cb.isNull(root.get("resolution"));
            case "parent" -> cb.isNull(root.get("parentId"));
            case "due" -> cb.isNull(root.get("dueDate"));
            case "estimate" -> cb.isNull(root.get("estimateHours"));
            case "labels" -> cb.isEmpty(root.get("labels"));
            case "component" -> cb.isEmpty(root.get("componentIds"));
            default -> throw AqlException.at(position, "EMPTY를 쓸 수 없는 필드입니다: " + field.name());
        };
    }

    // ── 정렬 ──

    private List<Order> orders(List<AqlAst.Order> requested) {
        List<Order> orders = new ArrayList<>();
        if (requested.isEmpty()) {
            orders.add(cb.desc(root.get("updatedAt")));
        } else {
            for (AqlAst.Order order : requested) {
                AqlFields.Field field = AqlFields.require(order.field(), order.position());
                if (!field.sortable()) {
                    throw AqlException.at(order.position(), "정렬할 수 없는 필드입니다: " + field.name(),
                            AqlFields.sortable().toArray(String[]::new));
                }
                boolean asc = !"desc".equals(order.direction());
                for (Expression<?> key : sortKeys(field)) orders.add(asc ? cb.asc(key) : cb.desc(key));
            }
        }
        // 같은 값끼리의 순서가 페이지마다 흔들리지 않게 마지막 기준을 하나 둔다
        orders.add(cb.asc(root.get("id")));
        return orders;
    }

    private List<Expression<?>> sortKeys(AqlFields.Field field) {
        return switch (field.name()) {
            case "created" -> List.of(root.get("createdAt"));
            case "updated" -> List.of(root.get("updatedAt"));
            case "due" -> List.of(root.get("dueDate"));
            case "priority" -> List.of(priorityRank());
            case "status" -> List.of(statusRank());
            case "summary" -> List.of(root.get("title"));
            case "assignee" -> List.of(root.get("assigneeId"));
            case "estimate" -> List.of(root.get("estimateHours"));
            // 키는 문자열로 정렬하면 ALM-10이 ALM-9보다 앞이라 프로젝트+번호로 센다
            default -> List.of(root.get("projectId"), root.get("issueNumber"));
        };
    }

    /** 레지스트리 sort_order(1=가장 중요) — 모르는 값은 맨 뒤 */
    private Expression<Integer> priorityRank() {
        List<PriorityDef> defs = session.priorityDefs();
        CriteriaBuilder.Case<Integer> cases = cb.selectCase();
        for (PriorityDef def : defs) {
            cases = cases.when(cb.equal(root.get("priority"), def.getId()), def.getSortOrder());
        }
        return cases.otherwise(defs.size() + 1);
    }

    /** 상태는 분류(할 일→진행 중→완료) 순, 같은 분류 안에서는 id 순 */
    private Expression<Integer> statusRank() {
        Map<String, Integer> categoryOrder = new LinkedHashMap<>();
        for (StatusCategory category : session.categories()) {
            categoryOrder.put(category.getId(), category.getSortOrder());
        }
        List<StatusDef> defs = session.statusDefs();
        CriteriaBuilder.Case<Integer> cases = cb.selectCase();
        int index = 0;
        for (StatusDef def : defs) {
            int rank = categoryOrder.getOrDefault(def.getCategoryId(), 999) * 1000 + index++;
            cases = cases.when(cb.equal(root.get("status"), def.getId()), rank);
        }
        return cases.otherwise(999_999);
    }

    /**
     * 부정 연산자({@code !=}·{@code NOT IN}·{@code !~})는 <b>빈 값을 제외</b>한다 — JQL 그대로다.
     * {@code assignee != 2}에 담당자 미지정 이슈는 안 들어간다; 넣으려면
     * {@code OR assignee IS EMPTY}를 명시한다.
     *
     * <p>집합 여집합인 {@code NOT (…)}은 다르다 — 그쪽은 빈 값을 포함한다.
     * JQL도 필드 연산자와 {@code NOT} 연산자를 이렇게 가른다.
     */
    private Predicate excludeEmpty(AqlFields.Field field, Predicate positive) {
        return cb.and(present(field), cb.not(positive));
    }

    /** 이 필드에 값이 있는가. 비지 않는 컬럼은 항상 참이다 */
    private Predicate present(AqlFields.Field field) {
        return switch (field.name()) {
            case "assignee" -> cb.isNotNull(root.get("assigneeId"));
            case "sprint" -> cb.isNotNull(root.get("sprintId"));
            case "fixVersion" -> cb.isNotNull(root.get("fixVersionId"));
            case "resolution" -> cb.isNotNull(root.get("resolution"));
            case "parent" -> cb.isNotNull(root.get("parentId"));
            case "due" -> cb.isNotNull(root.get("dueDate"));
            case "estimate" -> cb.isNotNull(root.get("estimateHours"));
            case "labels" -> cb.isNotEmpty(root.get("labels"));
            case "component" -> cb.isNotEmpty(root.get("componentIds"));
            default -> cb.conjunction();
        };
    }

    // ── 술어 조립 ──

    /** NULL 안전한 IN — 값이 없으면 언제나 거짓이라 {@code NOT}이 "전부"가 된다 */
    private Predicate in(Path<?> path, List<?> values, boolean nullable) {
        if (values.isEmpty()) return cb.disjunction();
        Predicate matches = path.in(values);
        return nullable ? cb.and(cb.isNotNull(path), matches) : matches;
    }

    /** 컬렉션(라벨·컴포넌트)은 조인이 아니라 EXISTS로 — 조인으로 부정하면 "다른 라벨이 하나라도 있으면 참"이 된다 */
    private Predicate existsIn(String attribute, List<?> values) {
        if (values.isEmpty()) return cb.disjunction();
        Subquery<Long> sub = query.subquery(Long.class);
        Root<AqlIssueRow> other = sub.from(AqlIssueRow.class);
        Join<Object, Object> element = other.join(attribute);
        sub.select(other.get("id"))
                .where(cb.and(cb.equal(other.get("id"), root.get("id")), element.in(values)));
        return cb.exists(sub);
    }

    private Predicate anyOf(List<Value> values, java.util.function.Function<Value, Predicate> mapper) {
        List<Predicate> predicates = values.stream().map(mapper).toList();
        return predicates.size() == 1 ? predicates.get(0) : cb.or(predicates.toArray(Predicate[]::new));
    }

    private Path<Instant> instantPath(AqlFields.Field field) {
        return root.get("created".equals(field.name()) ? "createdAt" : "updatedAt");
    }

    private static List<Object> flat(List<Value> values, java.util.function.Function<Value, List<?>> mapper) {
        List<Object> all = new ArrayList<>();
        for (Value value : values) {
            for (Object resolved : mapper.apply(value)) {
                if (!all.contains(resolved)) all.add(resolved);
            }
        }
        return all;
    }

}
