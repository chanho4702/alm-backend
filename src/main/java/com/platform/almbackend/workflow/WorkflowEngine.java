package com.platform.almbackend.workflow;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.settings.SettingsBody;
import com.platform.almbackend.settings.SettingsBody.Condition;
import com.platform.almbackend.settings.SettingsBody.PostFunction;
import com.platform.almbackend.settings.SettingsBody.ResolvedWorkflow;
import com.platform.almbackend.settings.SettingsBody.Transition;
import com.platform.almbackend.settings.SettingsBody.WorkflowStatus;
import com.platform.common.error.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 워크플로 엔진(2026-10-24 A2·C1~C3) — 전환 고르기·조건 평가·검증·고정 후처리. 상태가 바뀌는 모든 경로(PUT 수정,
 * 보드 이동, 일괄 편집, 전환 실행)가 이 클래스 하나로 규칙을 탄다. 해결(resolution) 규칙은
 * {@link com.platform.almbackend.issue.ResolutionRule}이 맡고, 여기서는 그 기본값(후처리 setResolution)만 준다.
 *
 * <p><b>거부 상태 코드</b>: 조건·검증 미충족은 400 {@code {"error"}}다(IllegalArgumentException) — 403은 "프로젝트
 * 권한 없음"에만 쓴다(2026-10-24 사용자 결정, 기획 §7-9). 역할 조건 평가 중 org-service가 불능이면 503이 그대로
 * 올라간다(삼키지 않는다).
 *
 * <p><b>전환 목록이 빈 워크플로</b>는 "모든 상태 ↔ 모든 상태"(지라 기본 워크플로의 전역 전환과 같다)이고, 조건이 없는
 * 암묵 전환으로 다룬다. 암묵 전환의 id는 {@code implicit:<상태 id>}다.
 */
@Component
@RequiredArgsConstructor
public class WorkflowEngine {
    public static final String IMPLICIT_PREFIX = "implicit:";

    private final ProjectService projectService;
    private final IssueRepository issues;
    private final SchemeService settings;

    /**
     * 평가된 전환 하나.
     *
     * @param transition 원본 전환. 암묵 전환이면 null
     * @param reason     못 하는 이유(한국어). 할 수 있으면 null
     */
    public record Evaluated(Transition transition, String id, String name, String to, boolean global,
                            boolean available, String reason) {
        public List<String> screenFields() { return transition == null ? List.of() : transition.screenFields(); }
        public List<String> validators() { return transition == null ? List.of() : transition.validators(); }
    }

    /**
     * 현재 상태에서 갈 수 있는 전환 전부 — 조건을 평가하고, 못 하는 것도 숨기지 않고 이유와 함께 돌려준다(A2 AC).
     * 같은 상태로 가는 전환(자기 루프)은 뺀다.
     */
    public List<Evaluated> evaluate(long actorId, Issue issue, ResolvedWorkflow workflow) {
        Context context = new Context(actorId, issue);
        List<Evaluated> out = new ArrayList<>();
        if (workflow.transitions().isEmpty()) {
            for (WorkflowStatus status : workflow.statuses()) {
                if (status.id().equals(issue.getStatus())) continue;
                out.add(implicit(status, context.editDenied()));
            }
            return out;
        }
        for (Transition t : workflow.transitions()) {
            if (!t.appliesFrom(issue.getStatus()) || t.to().equals(issue.getStatus())) continue;
            out.add(evaluateOne(t, workflow, context));
        }
        return out;
    }

    /**
     * from(현재 상태) → to 이동에 쓸 전환을 고른다 — PUT·보드 이동·일괄 편집처럼 전환 id 없이 상태만 오는 경로용.
     * 같은 대상으로 가는 전환이 여럿이면 조건을 충족하는 첫 번째를 쓴다. 하나도 못 하면 첫 후보의 이유로 거부한다.
     *
     * @throws IllegalArgumentException 갈 수 없는 상태이거나 조건 미충족(400)
     */
    public Evaluated choose(long actorId, Issue issue, ResolvedWorkflow workflow, String to) {
        if (workflow.transitions().isEmpty()) {
            WorkflowStatus target = workflow.status(to).orElseThrow(
                    () -> new IllegalArgumentException("이 이슈 타입의 워크플로에 없는 상태입니다: " + to));
            return implicit(target, null);
        }
        Context context = new Context(actorId, issue);
        Evaluated firstRejected = null;
        for (Transition t : workflow.transitions()) {
            if (!t.to().equals(to) || !t.appliesFrom(issue.getStatus())) continue;
            Evaluated evaluated = evaluateOne(t, workflow, context);
            if (evaluated.available()) return evaluated;
            if (firstRejected == null) firstRejected = evaluated;
        }
        if (firstRejected != null) throw new IllegalArgumentException(firstRejected.reason());
        throw new IllegalArgumentException(statusName(workflow, issue.getStatus()) + "에서 "
                + statusName(workflow, to) + "로 옮길 수 없습니다");
    }

    /**
     * 전환 id로 고른다 — 전환 실행(POST .../transitions) 경로. 조건까지 평가해 못 하면 거부한다.
     *
     * @throws IllegalArgumentException 없는 전환·지금 상태에서 못 하는 전환·조건 미충족(400)
     */
    public Evaluated chooseById(long actorId, Issue issue, ResolvedWorkflow workflow, String transitionId) {
        String id = transitionId == null ? "" : transitionId.trim();
        if (workflow.transitions().isEmpty()) {
            if (!id.startsWith(IMPLICIT_PREFIX)) throw new IllegalArgumentException("없는 전환입니다: " + id);
            String to = id.substring(IMPLICIT_PREFIX.length());
            WorkflowStatus target = workflow.status(to)
                    .orElseThrow(() -> new IllegalArgumentException("없는 전환입니다: " + id));
            return implicit(target, null);
        }
        Transition t = workflow.transitions().stream()
                .filter(x -> id.equals(x.id())
                        || ((x.id() == null || x.id().isBlank()) && id.equals(IMPLICIT_PREFIX + x.to())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("없는 전환입니다: " + id));
        if (!t.appliesFrom(issue.getStatus())) {
            throw new IllegalArgumentException("지금 상태(" + statusName(workflow, issue.getStatus())
                    + ")에서는 할 수 없는 전환입니다: " + transitionName(t, workflow));
        }
        Evaluated evaluated = evaluateOne(t, workflow, new Context(actorId, issue));
        if (!evaluated.available()) throw new IllegalArgumentException(evaluated.reason());
        return evaluated;
    }

    /** 전환 검증 — 전환 화면 값까지 반영한 <b>뒤</b>의 이슈로 본다. 문구는 생성 필수 검사와 같다("담당자는 필수입니다") */
    public void assertValidators(Evaluated used, Issue after) {
        if (used == null) return;
        for (String field : used.validators()) {
            if (!present(field, after)) throw new IllegalArgumentException(SettingsBody.requiredMessage(field));
        }
    }

    /** 지금 값이 빈 검증 필드 — 전환 목록이 화면에 미리 보여 줄 수 있게 */
    public List<String> missingFields(Evaluated evaluated, Issue issue) {
        return evaluated.validators().stream().filter(f -> !present(f, issue)).toList();
    }

    /**
     * 고정 후처리 — 담당자를 실행자로·비우기. 해결은 내장 규칙({@code ResolutionRule})이라 여기서 다루지 않는다.
     *
     * @return 담당자가 바뀌었나(편집 폼이 보는 값이라 호출자가 버전을 올린다)
     */
    public boolean applyPostFunctions(Evaluated used, Issue issue, long actorId) {
        if (used == null || used.transition() == null) return false;
        Long before = issue.getAssigneeId();
        for (PostFunction f : used.transition().postFunctions()) {
            switch (f.type()) {
                case "assignToActor" -> issue.assignTo(actorId);
                case "clearAssignee" -> issue.assignTo(null);
                default -> { /* setResolution은 ResolutionRule의 기본값으로 쓰인다 */ }
            }
        }
        return !Objects.equals(before, issue.getAssigneeId());
    }

    /** 후처리 setResolution이 있으면 그 값, 없으면 null(= 규칙 기본값 DONE) */
    public static IssueResolution defaultResolution(Evaluated used) {
        if (used == null || used.transition() == null) return null;
        for (PostFunction f : used.transition().postFunctions()) {
            if ("setResolution".equals(f.type()) && f.value() != null) {
                try {
                    return IssueResolution.valueOf(f.value());
                } catch (IllegalArgumentException ignored) {
                    return null; // 저장 검증이 막는 값 — 옛 본문이면 기본값으로
                }
            }
        }
        return null;
    }

    /** 전환 이름 — 비어 있으면 대상 상태 이름(지라 기본 워크플로처럼 전환 이름 = 대상 상태 이름) */
    public static String transitionName(Transition t, ResolvedWorkflow workflow) {
        return t.name() == null || t.name().isBlank() ? statusName(workflow, t.to()) : t.name().trim();
    }

    // ── 내부 ──

    private Evaluated implicit(WorkflowStatus target, String editDenied) {
        return new Evaluated(null, IMPLICIT_PREFIX + target.id(), target.name(), target.id(), true,
                editDenied == null, editDenied);
    }

    private Evaluated evaluateOne(Transition t, ResolvedWorkflow workflow, Context context) {
        String id = t.id() == null || t.id().isBlank() ? IMPLICIT_PREFIX + t.to() : t.id();
        String reason = context.editDenied();
        if (reason == null) {
            for (Condition c : t.conditions()) {
                reason = check(c, context);
                if (reason != null) break;
            }
        }
        return new Evaluated(t, id, transitionName(t, workflow), t.to(), t.isGlobal(), reason == null, reason);
    }

    /** 조건 하나 — 충족하면 null, 아니면 사용자에게 보일 이유 */
    private String check(Condition c, Context context) {
        Issue issue = context.issue;
        return switch (c.type()) {
            case "assignee" -> Objects.equals(issue.getAssigneeId(), context.actorId)
                    ? null : "담당자만 이 전환을 할 수 있습니다";
            case "role" -> {
                AlmAction action = "ADMIN".equals(c.role()) ? AlmAction.ADMIN : AlmAction.EDIT;
                yield context.hasGrant(action) ? null
                        : (action == AlmAction.ADMIN ? "프로젝트 관리자만 이 전환을 할 수 있습니다"
                                                     : "프로젝트 편집 권한이 있는 사람만 이 전환을 할 수 있습니다");
            }
            case "subtasksDone" -> {
                long open = context.openChildren();
                yield open == 0 ? null : "완료되지 않은 하위 이슈가 " + open + "개 있습니다";
            }
            default -> "알 수 없는 전환 조건입니다: " + c.type(); // 저장 검증이 막는다 — 옛 본문은 fail-closed
        };
    }

    private static boolean present(String field, Issue issue) {
        return switch (field) {
            case "description" -> com.platform.almbackend.issue.IssueService.hasText(issue.getDescription());
            case "assignee" -> issue.getAssigneeId() != null;
            case "labels" -> !issue.getLabels().isEmpty();
            case "components" -> !issue.getComponentIds().isEmpty();
            case "parent" -> issue.getParentId() != null;
            case "sprint" -> issue.getSprintId() != null;
            case "dueDate" -> issue.getDueDate() != null;
            case "fixVersion" -> issue.getFixVersionId() != null;
            case "estimate" -> issue.getEstimateHours() != null;
            default -> true;
        };
    }

    private static String statusName(ResolvedWorkflow workflow, String id) {
        return workflow.status(id).map(WorkflowStatus::name).orElse(id);
    }

    /** 한 번의 평가 안에서 권한·하위 이슈 질의를 한 번씩만 하도록 묶는다 */
    private final class Context {
        final long actorId;
        final Issue issue;
        private final Map<AlmAction, Boolean> grants = new EnumMap<>(AlmAction.class);
        private Long openChildren;
        private String editDenied;
        private boolean editChecked;

        Context(long actorId, Issue issue) {
            this.actorId = actorId;
            this.issue = issue;
        }

        /** 편집 권한이 없거나 보관된 프로젝트면 모든 전환이 막힌다 — 그 이유. org 불능(503)은 그대로 올라간다 */
        String editDenied() {
            if (!editChecked) {
                editChecked = true;
                try {
                    projectService.require(actorId, issue.getProjectId(), AlmAction.EDIT);
                } catch (ForbiddenException e) {
                    editDenied = e.getMessage();
                }
            }
            return editDenied;
        }

        boolean hasGrant(AlmAction action) {
            return grants.computeIfAbsent(action, a -> projectService.hasGrant(actorId, issue.getProjectId(), a));
        }

        long openChildren() {
            if (openChildren == null) {
                Set<String> done = settings.completeStatusIds(issue.getProjectId());
                openChildren = done.isEmpty()
                        ? issues.countByParentId(issue.getId())
                        : issues.countOpenChildren(issue.getId(), done);
            }
            return openChildren;
        }
    }
}
