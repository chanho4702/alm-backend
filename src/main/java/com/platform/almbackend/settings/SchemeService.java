package com.platform.almbackend.settings;

import com.platform.almbackend.common.NotFoundException;
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
        if (body(projectId).statuses().stream().noneMatch(s -> s.id().equals(statusId))) {
            throw new IllegalArgumentException("이 프로젝트에 없는 상태입니다: " + statusId);
        }
    }

    /** 목록이 비면 모두 허용(호환 기본값), 정의돼 있으면 목록에 있는 이동만 */
    public void assertTransitionAllowed(long projectId, String from, String to) {
        if (from.equals(to)) return;
        SettingsBody body = body(projectId);
        if (body.transitions().isEmpty()) return;
        boolean allowed = body.transitions().stream().anyMatch(t ->
                t.to().equals(to) && (t.from() == null || t.from().isEmpty() || t.from().contains(from)));
        if (!allowed) {
            throw new IllegalArgumentException(statusName(body, from) + "에서 " + statusName(body, to) + "로 옮길 수 없습니다");
        }
    }

    public String defaultStatus(long projectId) {
        return body(projectId).statuses().stream()
                .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                .filter(s -> "new".equals(s.kind())).map(SettingsBody.WorkflowStatus::id)
                .findFirst().orElse("todo");
    }

    /** 완료 의미인가 — 해결 규칙 */
    @Transactional(readOnly = true)
    public boolean isComplete(long projectId, String statusId) {
        return body(projectId).statuses().stream()
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
        List<SettingsBody.Transition> transitions = new ArrayList<>();
        for (SettingsBody.Transition t : body.transitions()) {
            if (!valid.contains(t.to())) continue;
            List<String> from = t.from() == null ? List.of() : t.from().stream().filter(valid::contains).toList();
            if (t.from() != null && !t.from().isEmpty() && from.isEmpty()) continue;
            transitions.add(new SettingsBody.Transition(t.id(), t.name(), from, t.to()));
        }
        Map<String, SettingsBody.Point> layout = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, SettingsBody.Point> e : body.layout().entrySet()) {
            if (valid.contains(e.getKey()) || "__any__".equals(e.getKey())) layout.put(e.getKey(), e.getValue());
        }
        return new SettingsBody(body.statuses(), transitions, layout, body.enabledTypes(), body.enabledPriorities(), body.defaultPriority());
    }

    /** 새 구성에 없는 상태의 이슈를 옮긴다 — 반드시 구성을 바꾸기 전에(옛 구성으로 의미를 읽는다) */
    private void migrateIssues(long actorId, List<Long> projectIds, SettingsBody newBody) {
        if (projectIds.isEmpty()) return;
        Set<String> valid = new HashSet<>();
        for (SettingsBody.WorkflowStatus s : newBody.statuses()) valid.add(s.id());
        List<SettingsBody.WorkflowStatus> sorted = newBody.statuses().stream()
                .sorted((a, b) -> Integer.compare(a.order(), b.order())).toList();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (long projectId : projectIds) {
            SettingsBody old = body(projectId);
            for (Issue issue : issues.findByProjectIdOrderBySortOrderAscKeyAsc(projectId)) {
                if (valid.contains(issue.getStatus())) continue;
                SettingsBody.WorkflowStatus previous = old.statuses().stream()
                        .filter(s -> s.id().equals(issue.getStatus())).findFirst().orElse(null);
                String oldCategory = previous != null ? previous.category() : issue.getStatus();
                String oldKind = previous != null ? previous.kind() : queries.kindOf(issue.getStatus());
                SettingsBody.WorkflowStatus fallback = sorted.stream()
                        .filter(s -> s.category().equals(oldCategory)).findFirst()
                        .or(() -> sorted.stream().filter(s -> queries.kindOf(s.category()).equals(oldKind)).findFirst())
                        .orElse(sorted.get(0));
                String previousStatus = issue.getStatus();
                issue.moveToStatus(fallback.id());
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
