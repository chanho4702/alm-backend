package com.platform.almbackend.settings;

import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.ProjectSettings;
import com.platform.almbackend.domain.SettingsScheme;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.history.IssueChangeLogService;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SettingsSchemeRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import com.platform.almbackend.settings.dto.SettingsResponses.ResolvedSettingsResponse;
import com.platform.almbackend.settings.dto.SettingsResponses.SchemeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 스킴과 프로젝트 설정 — 정의(전역)·배정·커스텀·해석. 구성이 바뀌어 사라진 상태의 이슈는
 * 같은 카테고리의 첫 상태 → 같은 의미의 첫 상태 → 첫 상태로 이관하고 변경 이력을 남긴다.
 * 이슈 서비스는 여기의 assert*로 상태·전이·타입 규칙을 강제한다(프론트 목업과 같은 규칙).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SchemeService {
    public static final String DEFAULT_SCHEME = "scheme-default";
    private static final Map<String, String> BUILTIN_LEVEL = Map.of(
            "task", "standard", "story", "standard", "bug", "standard", "epic", "epic", "subtask", "subtask");

    private final SettingsSchemeRepository schemes;
    private final ProjectSettingsRepository projectSettings;
    private final StatusDefRepository statuses;
    private final IssueTypeDefRepository types;
    private final PriorityDefRepository priorities;
    private final IssueRepository issues;
    private final IssueChangeLogService changeLog;
    private final ProjectService projectService;
    private final SchemeQueries queries;

    // ── 스킴 ──

    @Transactional(readOnly = true)
    public List<SchemeResponse> list() {
        return schemes.findAllByOrderByIsDefaultDescNameAsc().stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public long countProjects(String schemeId) {
        return projectSettings.countBySchemeIdAndCustomBodyIsNull(schemeId);
    }

    public SchemeResponse create(String name) {
        String trimmed = requireText(name, "스킴 이름을 입력하세요");
        if (schemes.existsByName(trimmed)) throw new IllegalArgumentException("이미 존재하는 스킴 이름입니다: " + trimmed);
        SettingsScheme scheme = schemes.save(SettingsScheme.of(
                "scheme-" + UUID.randomUUID().toString().substring(0, 8), trimmed,
                queries.serialize(SettingsBody.defaults())));
        return response(scheme);
    }

    /** 스킴 수정 — 공유 중인 모든 프로젝트의 이슈를 새 구성으로 이관한다 */
    public SchemeResponse update(long actorId, String id, String name, SettingsBody body) {
        SettingsScheme scheme = requireScheme(id);
        if (name != null) scheme.rename(requireText(name, "스킴 이름을 입력하세요"));
        if (body != null) {
            validate(body);
            List<Long> shared = projectSettings.findBySchemeId(id).stream()
                    .filter(ps -> !ps.isCustom()).map(ProjectSettings::getProjectId).toList();
            migrateIssues(actorId, shared, body);
            applyToRegistry(body);
            scheme.replaceBody(queries.serialize(prune(body)));
        }
        return response(scheme);
    }

    public void delete(String id) {
        SettingsScheme scheme = requireScheme(id);
        if (scheme.isDefault()) throw new IllegalArgumentException("디폴트 스킴은 삭제할 수 없습니다");
        if (projectSettings.existsBySchemeId(id)) throw new IllegalArgumentException("배정된 프로젝트가 있는 스킴은 삭제할 수 없습니다");
        schemes.delete(scheme);
    }

    public void setDefault(String id) {
        requireScheme(id);
        for (SettingsScheme scheme : schemes.findAll()) scheme.setDefault(scheme.getId().equals(id));
    }

    // ── 프로젝트 설정 ──

    /** 새 프로젝트 — 디폴트 스킴 배정 */
    public void initProject(long projectId) {
        if (projectSettings.existsById(projectId)) return;
        String schemeId = queries.defaultScheme().map(SettingsScheme::getId).orElse(DEFAULT_SCHEME);
        projectSettings.save(ProjectSettings.of(projectId, schemeId));
    }

    @Transactional(readOnly = true)
    public ResolvedSettingsResponse resolve(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return resolved(projectId);
    }

    /**
     * 접근 가능한 프로젝트 전부의 해석된 상태 목록(order 순) — 홈·검색·⌘K가 프로젝트마다
     * {@code /projects/{id}/settings}를 부르던 1+N을 한 번으로 줄인다(감사 A-10). 레지스트리는 한 번, 같은 스킴은
     * 한 번만 해석한다. 키는 프로젝트 id 문자열(JSON 객체 키).
     */
    @Transactional(readOnly = true)
    public Map<String, List<SettingsBody.WorkflowStatus>> statusesByProject(long userId) {
        List<Long> projectIds = projectService.accessibleProjectIds(userId);
        if (projectIds.isEmpty()) return Map.of();
        SchemeQueries.Registry registry = queries.registry();
        Map<Long, ProjectSettings> settingsById = new java.util.HashMap<>();
        for (ProjectSettings ps : projectSettings.findAllById(projectIds)) settingsById.put(ps.getProjectId(), ps);
        Map<String, List<SettingsBody.WorkflowStatus>> byScheme = new java.util.HashMap<>();
        Map<String, List<SettingsBody.WorkflowStatus>> out = new java.util.LinkedHashMap<>();
        for (long projectId : projectIds) {
            ProjectSettings ps = settingsById.get(projectId);
            List<SettingsBody.WorkflowStatus> statuses;
            if (ps != null && ps.isCustom()) {
                statuses = sortedStatuses(queries.enrich(queries.parse(ps.getCustomBody()), registry));
            } else {
                String schemeKey = ps == null ? "" : ps.getSchemeId();
                statuses = byScheme.computeIfAbsent(schemeKey, key -> {
                    Optional<SettingsScheme> scheme = key.isEmpty() ? queries.defaultScheme() : schemes.findById(key);
                    SettingsBody body = scheme.map(sc -> queries.parse(sc.getBody())).orElseGet(SettingsBody::defaults);
                    return sortedStatuses(queries.enrich(body, registry));
                });
            }
            out.put(String.valueOf(projectId), statuses);
        }
        return out;
    }

    private static List<SettingsBody.WorkflowStatus> sortedStatuses(SettingsBody body) {
        return body.statuses().stream().sorted(java.util.Comparator.comparingInt(SettingsBody.WorkflowStatus::order)).toList();
    }

    public ResolvedSettingsResponse assignScheme(long actorId, long projectId, String schemeId) {
        projectService.require(actorId, projectId, AlmAction.ADMIN);
        SettingsScheme scheme = requireScheme(schemeId);
        ProjectSettings ps = entry(projectId);
        migrateIssues(actorId, List.of(projectId), queries.parse(scheme.getBody()));
        ps.assign(schemeId);
        return resolved(projectId);
    }

    /** 커스텀 전환(현재 스킴 복사) / 스킴 복귀(이관 후 폐기) */
    public ResolvedSettingsResponse setCustom(long actorId, long projectId, boolean custom) {
        projectService.require(actorId, projectId, AlmAction.ADMIN);
        ProjectSettings ps = entry(projectId);
        SettingsScheme scheme = requireScheme(ps.getSchemeId());
        if (custom) {
            if (!ps.isCustom()) ps.customize(scheme.getBody());
        } else if (ps.isCustom()) {
            migrateIssues(actorId, List.of(projectId), queries.parse(scheme.getBody()));
            ps.dropCustom();
        }
        return resolved(projectId);
    }

    public ResolvedSettingsResponse updateCustom(long actorId, long projectId, SettingsBody body) {
        projectService.require(actorId, projectId, AlmAction.ADMIN);
        ProjectSettings ps = entry(projectId);
        if (!ps.isCustom()) throw new IllegalArgumentException("커스텀 설정을 사용 중일 때만 편집할 수 있습니다");
        validate(body);
        migrateIssues(actorId, List.of(projectId), body);
        applyToRegistry(body);
        ps.customize(queries.serialize(prune(body)));
        return resolved(projectId);
    }

    // ── 이슈 서비스가 쓰는 규칙 ──

    @Transactional(readOnly = true)
    public SettingsBody body(long projectId) {
        Optional<ProjectSettings> ps = projectSettings.findById(projectId);
        if (ps.isPresent()) {
            ProjectSettings entry = ps.get();
            if (entry.isCustom()) return queries.enrich(queries.parse(entry.getCustomBody()));
            return schemes.findById(entry.getSchemeId())
                    .map(s -> queries.enrich(queries.parse(s.getBody())))
                    .orElseGet(() -> queries.enrich(SettingsBody.defaults()));
        }
        return queries.defaultScheme().map(s -> queries.enrich(queries.parse(s.getBody())))
                .orElseGet(() -> queries.enrich(SettingsBody.defaults()));
    }

    public void assertValidStatus(long projectId, String statusId) {
        assertValidStatus(body(projectId), null, statusId);
    }

    /**
     * 상태가 이 이슈 타입의 워크플로에 있는가 — 프로젝트에 없으면 "프로젝트에 없는 상태", 프로젝트에는 있지만
     * 타입별 워크플로(C5)에 없으면 "이 이슈 타입의 워크플로에 없는 상태"로 거부한다.
     */
    public static void assertValidStatus(SettingsBody body, String typeId, String statusId) {
        if (body.statuses().stream().noneMatch(s -> s.id().equals(statusId))) {
            throw new IllegalArgumentException("이 프로젝트에 없는 상태입니다: " + statusId);
        }
        if (typeId != null && !body.workflowFor(typeId).has(statusId)) {
            throw new IllegalArgumentException("이 이슈 타입의 워크플로에 없는 상태입니다: " + statusName(body, statusId));
        }
    }

    public String defaultStatus(long projectId) {
        return defaultStatus(body(projectId), null);
    }

    /** 워크플로(타입별이 있으면 그것)의 첫 '할 일' 의미 상태 — 없으면 첫 상태 */
    public static String defaultStatus(SettingsBody body, String typeId) {
        List<SettingsBody.WorkflowStatus> sorted = body.workflowFor(typeId).statuses();
        return sorted.stream().filter(s -> "new".equals(s.kind())).map(SettingsBody.WorkflowStatus::id)
                .findFirst()
                .orElse(sorted.isEmpty() ? "todo" : sorted.get(0).id());
    }

    /**
     * 워크플로에 없는 상태를 새 워크플로의 상태로 옮길 곳 — 설정 변경 이관과 같은 규칙: 같은 카테고리의 첫 상태 →
     * 같은 의미의 첫 상태 → 첫 상태. {@code previous}는 옛 구성에서 그 상태의 정의(없으면 null).
     */
    public String mappedStatus(SettingsBody.WorkflowStatus previous, String statusId,
                               List<SettingsBody.WorkflowStatus> target) {
        String oldCategory = previous != null ? previous.category() : statusId;
        String oldKind = previous != null && previous.kind() != null ? previous.kind() : queries.kindOf(statusId);
        return target.stream().filter(s -> s.category().equals(oldCategory)).findFirst()
                .or(() -> target.stream().filter(s -> queries.kindOf(s.category()).equals(oldKind)).findFirst())
                .orElse(target.get(0)).id();
    }

    /** 프로젝트의 "완료" 의미 상태 id 전부 — 스프린트 완료·릴리스가 doneStatuses를 안 보내면 이걸 쓴다 */
    @Transactional(readOnly = true)
    public Set<String> completeStatusIds(long projectId) {
        Set<String> ids = new HashSet<>();
        for (SettingsBody.WorkflowStatus s : body(projectId).statuses()) {
            if ("complete".equals(s.kind())) ids.add(s.id());
        }
        return ids;
    }

    /** 완료 의미인가 — 해결 규칙 */
    @Transactional(readOnly = true)
    public boolean isComplete(long projectId, String statusId) {
        return isComplete(body(projectId), statusId);
    }

    /** 완료 의미인가 — 레지스트리로 해석된 본문 기준. 본문에 없는 옛 id는 'done'만 완료로 본다(V29 백필과 같다) */
    public static boolean isComplete(SettingsBody body, String statusId) {
        return body.statuses().stream()
                .filter(s -> s.id().equals(statusId)).map(s -> "complete".equals(s.kind())).findFirst()
                .orElse("done".equals(statusId));
    }

    /** 하위 작업 계층은 계층 기능이라 활성 목록과 무관하게 허용 */
    public void assertTypeEnabled(long projectId, String typeId) {
        if (types.findById(typeId).isEmpty() && !BUILTIN_LEVEL.containsKey(typeId)) {
            throw new IllegalArgumentException("없는 이슈 타입입니다: " + typeId);
        }
        if ("subtask".equals(typeLevel(typeId))) return;
        if (!body(projectId).enabledTypes().contains(typeId)) {
            throw new IllegalArgumentException("이 프로젝트에서 사용할 수 없는 타입입니다: " + typeName(typeId));
        }
    }

    public String defaultType(long projectId) {
        List<String> enabled = body(projectId).enabledTypes();
        if (enabled.contains("task")) return "task";
        return enabled.stream().filter(t -> !"subtask".equals(typeLevel(t))).findFirst().orElse("task");
    }

    @Transactional(readOnly = true)
    public String typeLevel(String typeId) {
        return types.findById(typeId).map(IssueTypeDef::getLevel).orElse(BUILTIN_LEVEL.getOrDefault(typeId, "standard"));
    }

    private String typeName(String typeId) {
        return types.findById(typeId).map(IssueTypeDef::getName).orElse(typeId);
    }

    // ── 내부 ──

    /** 의미마다 최소 1개·이름 유일(레지스트리 전체)·카테고리 실재·subtask 고정·비-subtask 최소 1개 */
    void validate(SettingsBody body) {
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (SettingsBody.WorkflowStatus status : body.statuses()) {
            String name = status.name() == null ? "" : status.name().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("상태 이름을 입력하세요");
            if (!names.add(name)) throw new IllegalArgumentException("상태 이름이 중복됩니다: " + name);
            if (!ids.add(status.id())) throw new IllegalArgumentException("같은 상태를 두 번 넣을 수 없습니다");
            if (statuses.existsByNameAndIdNot(name, status.id())) throw new IllegalArgumentException("상태 이름이 중복됩니다: " + name);
            if (status.category() == null || queries.kindOf(status.category()) == null) {
                throw new IllegalArgumentException("카테고리를 찾을 수 없습니다");
            }
        }
        Set<String> kinds = new HashSet<>();
        for (SettingsBody.WorkflowStatus status : body.statuses()) kinds.add(queries.kindOf(status.category()));
        if (!kinds.containsAll(RegistryService.KINDS)) {
            throw new IllegalArgumentException("카테고리(할 일/진행 중/완료)마다 상태가 최소 1개 필요합니다");
        }
        for (String type : body.enabledTypes()) {
            if (types.findById(type).isEmpty()) throw new IllegalArgumentException("없는 이슈 타입입니다: " + type);
        }
        if (!body.enabledTypes().contains("subtask")) throw new IllegalArgumentException("하위 작업 타입은 비활성화할 수 없습니다");
        if (body.enabledTypes().stream().noneMatch(t -> !"subtask".equals(typeLevel(t)))) {
            throw new IllegalArgumentException("이슈 타입은 최소 1개 활성화해야 합니다");
        }
        for (String priority : body.enabledPriorities()) {
            if (priorities.findById(priority).isEmpty()) throw new IllegalArgumentException("없는 우선순위입니다: " + priority);
        }
        if (body.enabledPriorities().isEmpty()) throw new IllegalArgumentException("우선순위는 최소 1개 활성화해야 합니다");
        if (!body.enabledPriorities().contains(body.defaultPriority())) {
            throw new IllegalArgumentException("기본 우선순위는 활성화된 우선순위 중에서 골라야 합니다");
        }
        validateFields(body);
        validateWorkflows(body);
    }

    /**
     * 전환 규칙(C1~C3)과 타입별 워크플로(C5) — 대상·출발 상태가 워크플로에 있어야 하고, 조건·검증·화면 칸·후처리는
     * 정해진 어휘만 받는다. 해결 칸과 해결 후처리는 완료 의미로 가는 전환에만 쓸 수 있다.
     */
    private void validateWorkflows(SettingsBody body) {
        Map<String, String> kindById = new java.util.HashMap<>();
        for (SettingsBody.WorkflowStatus status : body.statuses()) kindById.put(status.id(), queries.kindOf(status.category()));
        validateTransitions(body.transitions(), kindById);
        for (Map.Entry<String, SettingsBody.TypeWorkflow> entry : body.workflowsByType().entrySet()) {
            String typeId = entry.getKey();
            if (typeId == null || typeId.isBlank() || types.findById(typeId).isEmpty()) {
                throw new IllegalArgumentException("없는 이슈 타입입니다: " + typeId);
            }
            SettingsBody.TypeWorkflow workflow = entry.getValue();
            if (workflow == null) throw new IllegalArgumentException("이슈 타입 워크플로가 비어 있습니다: " + typeName(typeId));
            List<String> subset = workflow.statuses() == null ? List.of() : workflow.statuses();
            Map<String, String> scoped = new java.util.HashMap<>();
            for (String id : subset) {
                if (!kindById.containsKey(id)) {
                    throw new IllegalArgumentException(typeName(typeId) + " 워크플로에 프로젝트에 없는 상태가 있습니다: " + id);
                }
                scoped.put(id, kindById.get(id));
            }
            if (subset.isEmpty()) scoped.putAll(kindById);
            if (!new HashSet<>(scoped.values()).containsAll(RegistryService.KINDS)) {
                throw new IllegalArgumentException(typeName(typeId) + " 워크플로에도 카테고리(할 일/진행 중/완료)마다 상태가 최소 1개 필요합니다");
            }
            if (workflow.transitions() != null) validateTransitions(workflow.transitions(), scoped);
        }
    }

    private static void validateTransitions(List<SettingsBody.Transition> transitions, Map<String, String> kindById) {
        Set<String> ids = new HashSet<>();
        for (SettingsBody.Transition t : transitions) {
            if (t == null || t.to() == null || !kindById.containsKey(t.to())) {
                throw new IllegalArgumentException("전환의 대상 상태가 워크플로에 없습니다");
            }
            if (t.id() != null && !t.id().isBlank() && !ids.add(t.id())) {
                throw new IllegalArgumentException("같은 전환 id가 두 번 있습니다: " + t.id());
            }
            for (String from : t.from()) {
                if (!kindById.containsKey(from)) throw new IllegalArgumentException("전환의 출발 상태가 워크플로에 없습니다: " + from);
            }
            boolean toComplete = "complete".equals(kindById.get(t.to()));
            for (SettingsBody.Condition c : t.conditions()) {
                if (c == null || !SettingsBody.CONDITION_TYPES.contains(c.type())) {
                    throw new IllegalArgumentException("없는 전환 조건입니다: " + (c == null ? null : c.type()));
                }
                if ("role".equals(c.type()) && !"ADMIN".equals(c.role()) && !"EDIT".equals(c.role())) {
                    throw new IllegalArgumentException("역할 조건은 ADMIN 또는 EDIT여야 합니다");
                }
            }
            Set<String> seen = new HashSet<>();
            for (String field : t.validators()) {
                if (!SettingsBody.VALIDATOR_FIELDS.contains(field)) throw new IllegalArgumentException("전환 검증에 쓸 수 없는 필드입니다: " + field);
                if (!seen.add(field)) throw new IllegalArgumentException("같은 필드를 두 번 넣을 수 없습니다: " + SettingsBody.fieldName(field));
            }
            seen.clear();
            for (String field : t.screenFields()) {
                if (!SettingsBody.SCREEN_FIELDS.contains(field)) throw new IllegalArgumentException("전환 화면에 넣을 수 없는 칸입니다: " + field);
                if (!seen.add(field)) throw new IllegalArgumentException("같은 칸을 두 번 넣을 수 없습니다: " + field);
                if ("resolution".equals(field) && !toComplete) {
                    throw new IllegalArgumentException("해결 칸은 완료 상태로 가는 전환에만 넣을 수 있습니다");
                }
            }
            for (SettingsBody.PostFunction f : t.postFunctions()) {
                if (f == null || !SettingsBody.POST_FUNCTION_TYPES.contains(f.type())) {
                    throw new IllegalArgumentException("없는 후처리입니다: " + (f == null ? null : f.type()));
                }
                if ("setResolution".equals(f.type())) {
                    if (!toComplete) throw new IllegalArgumentException("해결 설정 후처리는 완료 상태로 가는 전환에만 쓸 수 있습니다");
                    try {
                        com.platform.almbackend.domain.IssueResolution.valueOf(f.value() == null ? "" : f.value());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("없는 해결 값입니다: " + f.value());
                    }
                }
            }
            boolean assigns = t.postFunctions().stream().anyMatch(f -> "assignToActor".equals(f.type()));
            boolean clears = t.postFunctions().stream().anyMatch(f -> "clearAssignee".equals(f.type()));
            if (assigns && clears) throw new IllegalArgumentException("담당자를 나로 지정하면서 비울 수는 없습니다");
        }
    }

    /**
     * 필드 구성 — id는 13종 안에서 유일, 숨긴 필드·해결·상위 항목은 필수 불가. 타입별 덮어쓰기는
     * 키가 레지스트리의 이슈 타입이어야 하고 목록은 기본 구성과 같은 규칙을 따른다.
     */
    private void validateFields(SettingsBody body) {
        validateFieldList(body.rawFields());
        for (Map.Entry<String, List<SettingsBody.FieldConfig>> entry : body.rawFieldsByType().entrySet()) {
            String typeId = entry.getKey();
            if (typeId == null || typeId.isBlank() || types.findById(typeId).isEmpty()) {
                throw new IllegalArgumentException("없는 이슈 타입입니다: " + typeId);
            }
            validateFieldList(entry.getValue() == null ? List.of() : entry.getValue());
        }
    }

    private static void validateFieldList(List<SettingsBody.FieldConfig> fields) {
        Set<String> seen = new HashSet<>();
        for (SettingsBody.FieldConfig field : fields) {
            String id = field == null ? null : field.id();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("필드 id가 비어 있습니다");
            if (!SettingsBody.FIELD_IDS.contains(id)) {
                throw new IllegalArgumentException("없는 필드입니다: " + id);
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("같은 필드를 두 번 넣을 수 없습니다: " + SettingsBody.fieldName(id));
            }
            if (field.required() && !field.visible()) {
                throw new IllegalArgumentException("숨긴 필드는 필수로 지정할 수 없습니다: " + SettingsBody.fieldName(id));
            }
            if (field.required() && SettingsBody.RESOLUTION.equals(id)) {
                throw new IllegalArgumentException("해결은 완료 상태에서만 입력하므로 필수로 지정할 수 없습니다");
            }
            if (field.required() && SettingsBody.PARENT.equals(id)) {
                throw new IllegalArgumentException("상위 항목은 최상위 이슈가 있어야 하므로 필수로 지정할 수 없습니다");
            }
        }
    }

    /** 요청 값(대소문자 무관) → 레지스트리 id. null이면 프로젝트 기본 우선순위. 비활성이면 거부 */
    public String resolvePriority(long projectId, String requested) {
        SettingsBody body = body(projectId);
        if (requested == null || requested.isBlank()) return body.defaultPriority();
        String id = requested.trim().toLowerCase(java.util.Locale.ROOT);
        PriorityDef def = priorities.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("없는 우선순위입니다: " + requested));
        if (!body.enabledPriorities().contains(id)) {
            throw new IllegalArgumentException("이 프로젝트에서 사용할 수 없는 우선순위입니다: " + def.getName());
        }
        return id;
    }

    @Transactional(readOnly = true)
    public String priorityName(String id) {
        return priorities.findById(id == null ? "" : id).map(PriorityDef::getName).orElse(id);
    }

    /** 본문의 이름·카테고리는 레지스트리로 관통 저장(프론트 목업과 같은 계약) */
    private void applyToRegistry(SettingsBody body) {
        for (SettingsBody.WorkflowStatus status : body.statuses()) {
            Optional<StatusDef> existing = statuses.findById(status.id());
            if (existing.isEmpty()) {
                statuses.save(StatusDef.of(status.id(), status.name().trim(), status.category(), ""));
            } else {
                existing.get().rename(status.name().trim());
                existing.get().moveTo(status.category());
            }
        }
    }

    /** 사라진 상태의 전이·배치를 남기지 않는다 */
    private static SettingsBody prune(SettingsBody body) {
        Set<String> valid = new HashSet<>();
        for (SettingsBody.WorkflowStatus s : body.statuses()) valid.add(s.id());
        List<SettingsBody.Transition> transitions = pruneTransitions(body.transitions(), valid);
        Map<String, SettingsBody.TypeWorkflow> workflows = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, SettingsBody.TypeWorkflow> e : body.workflowsByType().entrySet()) {
            SettingsBody.TypeWorkflow w = e.getValue();
            List<String> subset = w.statuses() == null ? List.of() : w.statuses().stream().filter(valid::contains).toList();
            Set<String> scoped = subset.isEmpty() ? valid : new HashSet<>(subset);
            workflows.put(e.getKey(), new SettingsBody.TypeWorkflow(subset,
                    w.transitions() == null ? null : pruneTransitions(w.transitions(), scoped)));
        }
        Map<String, SettingsBody.Point> layout = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, SettingsBody.Point> e : body.layout().entrySet()) {
            if (valid.contains(e.getKey()) || "__any__".equals(e.getKey())) layout.put(e.getKey(), e.getValue());
        }
        return new SettingsBody(body.statuses(), transitions, layout, body.enabledTypes(),
                body.enabledPriorities(), body.defaultPriority(), body.fields(), body.fieldsByType(), workflows);
    }

    /** 원래 전역 전환(from 비었음)은 그대로 두고, from을 전부 잃은 전환만 버린다 — 규칙(조건·검증·화면·후처리)은 보존 */
    private static List<SettingsBody.Transition> pruneTransitions(List<SettingsBody.Transition> source, Set<String> valid) {
        List<SettingsBody.Transition> transitions = new ArrayList<>();
        for (SettingsBody.Transition t : source) {
            if (!valid.contains(t.to())) continue;
            List<String> from = t.from().stream().filter(valid::contains).toList();
            if (!t.isGlobal() && from.isEmpty()) continue;
            transitions.add(t.withFrom(from));
        }
        return transitions;
    }

    /** 새 구성에 없는 상태의 이슈를 옮긴다 — 반드시 구성을 바꾸기 전에(옛 구성으로 의미를 읽는다) */
    /**
     * 이슈 타입별 워크플로(C5)도 본다 — 이슈의 상태가 <b>그 타입의</b> 새 워크플로에 없으면 옮긴다. 옮긴 결과 완료 의미에
     * 들고 나면 해결 규칙(A1)을 같이 적용한다(해결이 상태와 따로 놀지 않게).
     */
    private void migrateIssues(long actorId, List<Long> projectIds, SettingsBody newBody) {
        if (projectIds.isEmpty()) return;
        SettingsBody enrichedNew = queries.enrich(newBody);
        Map<String, SettingsBody.ResolvedWorkflow> byType = new java.util.HashMap<>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (long projectId : projectIds) {
            SettingsBody old = body(projectId);
            for (Issue issue : issues.findByProjectIdOrderBySortOrderAscKeyAsc(projectId)) {
                SettingsBody.ResolvedWorkflow workflow = byType.computeIfAbsent(issue.getType(), enrichedNew::workflowFor);
                if (workflow.has(issue.getStatus())) continue;
                SettingsBody.WorkflowStatus previous = old.statuses().stream()
                        .filter(s -> s.id().equals(issue.getStatus())).findFirst().orElse(null);
                String target = mappedStatus(previous, issue.getStatus(), workflow.statuses());
                String previousStatus = issue.getStatus();
                boolean wasComplete = previous != null ? "complete".equals(previous.kind()) : "done".equals(previousStatus);
                boolean isComplete = workflow.status(target).map(s -> "complete".equals(s.kind())).orElse(false);
                issue.moveToStatus(target);
                issue.changeResolution(com.platform.almbackend.issue.ResolutionRule.resolve(
                        wasComplete, isComplete, issue.getResolution(), false, null, null));
                changeLog.recordChanges(actorId, issue, previousStatus, issue.getSprintId(), now);
            }
        }
    }

    private ResolvedSettingsResponse resolved(long projectId) {
        ProjectSettings ps = projectSettings.findById(projectId).orElse(null);
        SettingsScheme scheme = ps == null ? queries.defaultScheme().orElseThrow() : requireScheme(ps.getSchemeId());
        boolean custom = ps != null && ps.isCustom();
        SettingsBody body = custom ? queries.parse(ps.getCustomBody()) : queries.parse(scheme.getBody());
        return new ResolvedSettingsResponse(queries.enrich(body), custom ? "custom" : "scheme", response(scheme));
    }

    private SchemeResponse response(SettingsScheme scheme) {
        return SchemeResponse.of(scheme, queries.enrich(queries.parse(scheme.getBody())));
    }

    private ProjectSettings entry(long projectId) {
        return projectSettings.findById(projectId).orElseGet(() -> {
            initProject(projectId);
            return projectSettings.findById(projectId).orElseThrow();
        });
    }

    private SettingsScheme requireScheme(String id) {
        return schemes.findById(id == null ? "" : id).orElseThrow(() -> new NotFoundException("스킴을 찾을 수 없습니다"));
    }

    private static String statusName(SettingsBody body, String id) {
        return body.statuses().stream().filter(s -> s.id().equals(id)).map(SettingsBody.WorkflowStatus::name)
                .findFirst().orElse(id);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
