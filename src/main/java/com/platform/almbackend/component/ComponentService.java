package com.platform.almbackend.component;

import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Component;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.ComponentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 컴포넌트(지라 Components) — 프로젝트 관리자가 만들고, 이슈는 여러 개를 가질 수 있다 */
@Service
@RequiredArgsConstructor
@Transactional
public class ComponentService {
    private static final Set<String> ASSIGNEE_RULES = Set.of(Component.ASSIGNEE_PROJECT, Component.ASSIGNEE_LEAD, Component.ASSIGNEE_UNASSIGNED);

    private final ComponentRepository components;
    private final ProjectService projectService;

    public record ComponentResponse(long id, long projectId, String name, String description, Long leadId,
                                    String defaultAssignee, long issueCount, Instant createdAt) {}
    public record ComponentRequest(String name, String description, Long leadId, Boolean clearLead, String defaultAssignee) {}

    @Transactional(readOnly = true)
    public List<ComponentResponse> list(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return components.findByProjectIdOrderByNameAsc(projectId).stream().map(this::response).toList();
    }

    public ComponentResponse create(long userId, long projectId, ComponentRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        String name = requireName(request.name());
        if (components.existsByProjectIdAndName(projectId, name)) throw new IllegalArgumentException("컴포넌트 이름이 중복됩니다: " + name);
        String rule = requireRule(request.defaultAssignee());
        Component saved = components.save(Component.of(projectId, name, trim(request.description()), request.leadId(), rule,
                Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return response(saved);
    }

    public ComponentResponse update(long userId, long componentId, ComponentRequest request) {
        Component component = require(componentId);
        projectService.require(userId, component.getProjectId(), AlmAction.ADMIN);
        if (request.name() != null) {
            String name = requireName(request.name());
            if (components.existsByProjectIdAndNameAndIdNot(component.getProjectId(), name, componentId)) {
                throw new IllegalArgumentException("컴포넌트 이름이 중복됩니다: " + name);
            }
            component.rename(name);
        }
        if (request.description() != null) component.describe(trim(request.description()));
        if (Boolean.TRUE.equals(request.clearLead())) component.assignLead(null);
        else if (request.leadId() != null) component.assignLead(request.leadId());
        if (request.defaultAssignee() != null) component.changeDefaultAssignee(requireRule(request.defaultAssignee()));
        return response(component);
    }

    public void delete(long userId, long componentId) {
        Component component = require(componentId);
        projectService.require(userId, component.getProjectId(), AlmAction.ADMIN);
        components.detachFromIssues(componentId);
        components.delete(component);
    }

    /** 이슈에 붙일 컴포넌트 id 검증 — 같은 프로젝트의 것만, 순서 유지·중복 제거 */
    @Transactional(readOnly = true)
    public List<Long> validateForIssue(long projectId, List<Long> ids) {
        if (ids == null) return List.of();
        List<Long> result = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || result.contains(id)) continue;
            Component component = require(id);
            if (component.getProjectId() != projectId) throw new IllegalArgumentException("다른 프로젝트의 컴포넌트입니다: " + component.getName());
            result.add(id);
        }
        return result;
    }

    /**
     * 담당자 없이 만든 이슈의 담당자 — 첫 컴포넌트의 규칙이 프로젝트 규칙보다 우선한다(지라).
     * lead인데 리더가 없으면 프로젝트 규칙으로 넘어간다.
     */
    @Transactional(readOnly = true)
    public Long resolveDefaultAssignee(Project project, List<Long> componentIds) {
        for (Long id : componentIds) {
            Component component = components.findById(id).orElse(null);
            if (component == null) continue;
            if (Component.ASSIGNEE_UNASSIGNED.equals(component.getDefaultAssignee())) return null;
            if (Component.ASSIGNEE_LEAD.equals(component.getDefaultAssignee()) && component.getLeadId() != null) {
                return component.getLeadId();
            }
        }
        return project.resolveDefaultAssignee();
    }

    private Component require(long id) {
        return components.findById(id).orElseThrow(() -> new NotFoundException("컴포넌트를 찾을 수 없습니다"));
    }

    private ComponentResponse response(Component c) {
        return new ComponentResponse(c.getId(), c.getProjectId(), c.getName(), c.getDescription(), c.getLeadId(),
                c.getDefaultAssignee(), components.countIssues(c.getId()), c.getCreatedAt());
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("컴포넌트 이름을 입력하세요");
        if (name.trim().length() > 80) throw new IllegalArgumentException("컴포넌트 이름은 80자 이하여야 합니다");
        return name.trim();
    }

    private static String requireRule(String rule) {
        if (rule == null || rule.isBlank()) return Component.ASSIGNEE_PROJECT;
        if (!ASSIGNEE_RULES.contains(rule)) throw new IllegalArgumentException("기본 담당자는 project/lead/unassigned 중 하나입니다");
        return rule;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
