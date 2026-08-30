package com.platform.almbackend.personal;

import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.ProjectShortcut;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.ProjectShortcutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 프로젝트 바로 가기 — 보기는 멤버 누구나, 편집은 프로젝트 관리자 */
@Service
@RequiredArgsConstructor
@Transactional
public class ProjectShortcutService {
    private final ProjectShortcutRepository shortcuts;
    private final ProjectService projectService;

    public record ShortcutResponse(long id, long projectId, String name, String url, int order, Instant createdAt) {
        static ShortcutResponse from(ProjectShortcut s) {
            return new ShortcutResponse(s.getId(), s.getProjectId(), s.getName(), s.getUrl(), s.getSortOrder(), s.getCreatedAt());
        }
    }
    public record ShortcutRequest(String name, String url) {}

    @Transactional(readOnly = true)
    public List<ShortcutResponse> list(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return shortcuts.findByProjectIdOrderBySortOrderAscIdAsc(projectId).stream().map(ShortcutResponse::from).toList();
    }

    public ShortcutResponse create(long userId, long projectId, ShortcutRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        int order = shortcuts.findByProjectIdOrderBySortOrderAscIdAsc(projectId).size() + 1;
        ProjectShortcut saved = shortcuts.save(ProjectShortcut.of(projectId, requireName(request.name()),
                requireUrl(request.url()), order, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        return ShortcutResponse.from(saved);
    }

    public ShortcutResponse update(long userId, long shortcutId, ShortcutRequest request) {
        ProjectShortcut shortcut = require(shortcutId);
        projectService.require(userId, shortcut.getProjectId(), AlmAction.ADMIN);
        shortcut.edit(requireName(request.name()), requireUrl(request.url()));
        return ShortcutResponse.from(shortcut);
    }

    public void delete(long userId, long shortcutId) {
        ProjectShortcut shortcut = require(shortcutId);
        projectService.require(userId, shortcut.getProjectId(), AlmAction.ADMIN);
        shortcuts.delete(shortcut);
    }

    private ProjectShortcut require(long id) {
        return shortcuts.findById(id).orElseThrow(() -> new NotFoundException("바로 가기를 찾을 수 없습니다"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("바로 가기 이름을 입력하세요");
        if (name.trim().length() > 80) throw new IllegalArgumentException("바로 가기 이름은 80자 이하여야 합니다");
        return name.trim();
    }

    private static String requireUrl(String url) {
        String trimmed = url == null ? "" : url.trim();
        if (!(trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
            throw new IllegalArgumentException("바로 가기 URL은 http:// 또는 https://로 시작해야 합니다");
        }
        if (trimmed.length() > 1000) throw new IllegalArgumentException("바로 가기 URL은 1000자 이하여야 합니다");
        return trimmed;
    }
}
