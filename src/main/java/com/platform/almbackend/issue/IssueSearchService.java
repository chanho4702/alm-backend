package com.platform.almbackend.issue;

import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.issue.dto.IssuePageResponse;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 서버 검색·페이징 — 클라이언트 전량 필터(BACKLOG #5)를 대체한다. 조건은 전부 선택이고 AND로 묶는다.
 * 프로젝트를 지정하지 않으면 접근 가능한 프로젝트 전체를 뒤진다(교차 프로젝트 검색).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IssueSearchService {
    public static final String UNASSIGNED = "unassigned";
    private static final int MAX_SIZE = 200;

    private final IssueRepository issues;
    private final ProjectService projectService;
    private final PermissionClient permissions;

    public record Criteria(
            List<Long> projectIds,
            String text,
            List<String> statuses,
            List<IssuePriority> priorities,
            List<String> types,
            /** 사용자 id 문자열 목록. "unassigned"는 미지정 */
            List<String> assignees,
            List<String> labels,
            Long sprintId,
            Long parentId,
            Long fixVersionId,
            String sort,
            String dir) {}

    public IssuePageResponse search(long userId, Criteria criteria, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_SIZE));
        int safePage = Math.max(0, page);
        Specification<Issue> spec = specification(userId, criteria);
        if (spec == null) return new IssuePageResponse(List.of(), safePage, safeSize, 0);
        Page<Issue> result = issues.findAll(spec, PageRequest.of(safePage, safeSize));
        return new IssuePageResponse(
                result.getContent().stream().map(IssueResponse::from).toList(),
                safePage, safeSize, result.getTotalElements());
    }

    public IssueResponse byKey(long userId, String key) {
        Issue issue = issues.findByKey(key.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + key));
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        return IssueResponse.from(issue);
    }

    /** null = 접근 가능한 프로젝트가 하나도 없어 결과가 비는 경우 */
    private Specification<Issue> specification(long userId, Criteria c) {
        List<Long> scopeIds = scopedProjectIds(userId, c.projectIds());
        if (scopeIds != null && scopeIds.isEmpty()) return null;

        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (scopeIds != null) where.add(root.get("projectId").in(scopeIds));
            if (c.text() != null && !c.text().isBlank()) {
                String like = "%" + c.text().trim().toLowerCase(Locale.ROOT) + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("key")), like)));
            }
            if (notEmpty(c.statuses())) where.add(root.get("status").in(c.statuses()));
            if (notEmpty(c.priorities())) where.add(root.get("priority").in(c.priorities()));
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
                Join<Issue, String> labels = root.join("labels");
                where.add(labels.in(c.labels()));
                query.distinct(true);
            }
            if (c.sprintId() != null) where.add(cb.equal(root.get("sprintId"), c.sprintId()));
            if (c.parentId() != null) where.add(cb.equal(root.get("parentId"), c.parentId()));
            if (c.fixVersionId() != null) where.add(cb.equal(root.get("fixVersionId"), c.fixVersionId()));

            boolean asc = "asc".equalsIgnoreCase(c.dir());
            Expression<?> sortKey = switch (c.sort() == null ? "updated" : c.sort()) {
                case "created" -> root.get("createdAt");
                case "due" -> root.get("dueDate");
                case "key" -> root.get("issueNumber");
                case "priority" -> cb.<Integer>selectCase()
                        .when(cb.equal(root.get("priority"), IssuePriority.HIGH), 0)
                        .when(cb.equal(root.get("priority"), IssuePriority.MEDIUM), 1)
                        .otherwise(2);
                default -> root.get("updatedAt");
            };
            Order primary = asc ? cb.asc(sortKey) : cb.desc(sortKey);
            query.orderBy(primary, cb.asc(root.get("id")));
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** 요청한 프로젝트 ∩ 접근 범위. null = 전역 접근이라 제한 없음 */
    private List<Long> scopedProjectIds(long userId, List<Long> requested) {
        AccessScope scope = permissions.accessibleProjects(userId);
        if (notEmpty(requested)) {
            for (long projectId : requested) projectService.require(userId, projectId, AlmAction.VIEW);
            return requested;
        }
        if (scope.all()) return null;
        Set<Long> ids = scope.projectIds();
        return List.copyOf(ids);
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
