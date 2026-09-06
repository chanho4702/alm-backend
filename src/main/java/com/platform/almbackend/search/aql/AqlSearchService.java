package com.platform.almbackend.search.aql;

import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.search.aql.dto.AqlDtos.Candidate;
import com.platform.almbackend.search.aql.dto.AqlDtos.FieldInfo;
import com.platform.almbackend.search.aql.dto.AqlDtos.FieldsResponse;
import com.platform.almbackend.search.aql.dto.AqlDtos.FunctionInfo;
import com.platform.almbackend.search.aql.dto.AqlDtos.QueryResponse;
import com.platform.almbackend.search.aql.dto.AqlDtos.ValidateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AQL 실행 — 파싱 → 해석 → Specification → 페이지. 결과는 기존 검색과 같은 이슈 shape이라
 * 프론트 어댑터가 하나로 끝난다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AqlSearchService {

    private static final int MAX_SIZE = 200;
    private static final int DEFAULT_SIZE = 50;

    private final AqlIssueRowRepository rows;
    private final AqlResolver resolver;
    private final PermissionClient permissions;
    private final ProjectRepository projects;

    public QueryResponse query(long userId, String aql, Integer page, Integer size) {
        int safeSize = Math.max(1, Math.min(size == null ? DEFAULT_SIZE : size, MAX_SIZE));
        int safePage = Math.max(0, page == null ? 0 : page);
        String text = aql == null ? "" : aql;

        AqlAst.Query parsed = AqlParser.parse(text);
        AqlValidation.check(parsed);

        List<Long> scopeIds = scope(userId);
        // 볼 수 있는 프로젝트가 하나도 없으면 질의를 돌릴 필요가 없다
        if (scopeIds != null && scopeIds.isEmpty()) {
            return new QueryResponse(List.of(), safePage, safeSize, 0, text);
        }
        AqlResolver.Session session = resolver.open(userId, scopeIds);
        Specification<AqlIssueRow> spec = new AqlSpecification(session, scopeIds).build(parsed);
        Page<AqlIssueRow> result = rows.findAll(spec, PageRequest.of(safePage, safeSize));
        return new QueryResponse(
                result.getContent().stream().map(AqlIssueRow::toResponse).toList(),
                safePage, safeSize, result.getTotalElements(), text);
    }

    /** 문법·필드·연산자만 본다 — 값이 실재하는지는 실행할 때 확인한다 */
    public ValidateResponse validate(String aql) {
        try {
            AqlAst.Query parsed = AqlParser.parse(aql == null ? "" : aql);
            Set<String> fields = AqlValidation.check(parsed);
            return new ValidateResponse(true, null, null, null, List.copyOf(fields), AqlJson.query(parsed));
        } catch (AqlException e) {
            return new ValidateResponse(false, e.getMessage(), e.position(),
                    e.expected().isEmpty() ? null : e.expected(), null, null);
        }
    }

    /** 자동완성 사전 — 값 후보는 볼 수 있는 범위 안에서만 준다 */
    public FieldsResponse fields(long userId) {
        AqlResolver.Session session = resolver.open(userId, scope(userId));
        List<FieldInfo> infos = new ArrayList<>();
        for (AqlFields.Field field : AqlFields.all()) {
            if (!field.supported()) continue;
            infos.add(new FieldInfo(field.name(), field.aliases(), field.kind().name(), field.operators(),
                    field.sortable(), field.emptyAllowed(), candidates(field, session, userId)));
        }
        return new FieldsResponse(infos, functions(), List.of(
                "AND", "OR", "NOT", "IN", "NOT IN", "IS", "IS NOT", "EMPTY", "ORDER BY", "ASC", "DESC"));
    }

    private List<Candidate> candidates(AqlFields.Field field, AqlResolver.Session session, long userId) {
        return switch (field.name()) {
            case "status" -> session.statusDefs().stream()
                    .map(def -> new Candidate(def.getId(), def.getName())).toList();
            case "statusCategory" -> session.categories().stream()
                    .map(category -> new Candidate(category.getKind(), category.getName())).toList();
            case "type" -> session.typeDefs().stream()
                    .map(def -> new Candidate(def.getId(), def.getName())).toList();
            case "priority" -> session.priorityDefs().stream()
                    .map(def -> new Candidate(def.getId(), def.getName())).toList();
            case "resolution" -> List.of(
                    new Candidate(IssueResolution.DONE.name(), "완료"),
                    new Candidate(IssueResolution.WONT_DO.name(), "하지 않음"),
                    new Candidate(IssueResolution.DUPLICATE.name(), "중복"),
                    new Candidate(IssueResolution.CANNOT_REPRODUCE.name(), "재현 불가"));
            case "project" -> visibleProjects(userId).stream()
                    .map(project -> new Candidate(project.getKey(), project.getName())).toList();
            case "archived" -> List.of(new Candidate("false", "활성"), new Candidate("true", "보관됨"));
            default -> List.of();
        };
    }

    private List<Project> visibleProjects(long userId) {
        AccessScope accessScope = permissions.accessibleProjects(userId);
        return accessScope.all()
                ? projects.findAllByOrderByNameAsc()
                : projects.findAllByIdInOrderByNameAsc(accessScope.projectIds());
    }

    private static List<FunctionInfo> functions() {
        return List.of(
                new FunctionInfo("currentUser", "currentUser()", List.of("assignee", "reporter"),
                        "지금 로그인한 사람"),
                new FunctionInfo("openSprints", "openSprints()", List.of("sprint"),
                        "진행 중인 스프린트 전부"),
                new FunctionInfo("now", "now()", dateFields(), "지금"),
                new FunctionInfo("startOfDay", "startOfDay(-1)", dateFields(), "그 날 0시(인자는 일 단위 이동)"),
                new FunctionInfo("endOfDay", "endOfDay()", dateFields(), "그 날의 끝"),
                new FunctionInfo("startOfWeek", "startOfWeek()", dateFields(), "그 주 월요일 0시"),
                new FunctionInfo("endOfWeek", "endOfWeek()", dateFields(), "그 주의 끝"),
                new FunctionInfo("startOfMonth", "startOfMonth(-1)", dateFields(), "그 달 1일 0시"),
                new FunctionInfo("endOfMonth", "endOfMonth()", dateFields(), "그 달의 끝"),
                new FunctionInfo("startOfYear", "startOfYear()", dateFields(), "그 해 1월 1일 0시"),
                new FunctionInfo("endOfYear", "endOfYear()", dateFields(), "그 해의 끝"));
    }

    private static List<String> dateFields() {
        return List.of("created", "updated", "due");
    }

    /** 볼 수 있는 프로젝트 id. {@code null}이면 제한 없음 */
    private List<Long> scope(long userId) {
        AccessScope accessScope = permissions.accessibleProjects(userId);
        return accessScope.all() ? null : List.copyOf(accessScope.projectIds());
    }
}
