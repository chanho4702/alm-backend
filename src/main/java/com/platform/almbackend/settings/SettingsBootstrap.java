package com.platform.almbackend.settings;

import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.domain.ProjectSettings;
import com.platform.almbackend.domain.SettingsScheme;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SettingsSchemeRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 기본 레지스트리·디폴트 스킴 보장 — V11이 심는 값과 같다. 운영에서는 no-op이고, Flyway 없이
 * ddl-auto로 도는 테스트 프로필과 "기본값이 지워진" 비정상 상태에서 빈자리를 채운다(멱등).
 */
@Component
@RequiredArgsConstructor
public class SettingsBootstrap implements ApplicationRunner {
    private final StatusCategoryRepository categories;
    private final StatusDefRepository statuses;
    private final IssueTypeDefRepository types;
    private final PriorityDefRepository priorities;
    private final SettingsSchemeRepository schemes;
    private final ProjectSettingsRepository projectSettings;
    private final ProjectRepository projects;
    private final SchemeQueries queries;

    private record Cat(String id, String name, String kind, String color, int order) {}
    private record Type(String id, String name, String icon, String color, String level, int order) {}
    private record Prio(String id, String name, String icon, String color, String description, int order) {}
    static final List<Prio> PRIORITIES = List.of(
            new Prio("highest", "최상", "chevrons-up", "danger", "지금 당장 처리해야 한다", 1),
            new Prio("high", "높음", "chevron-up", "danger", "다른 일보다 먼저 처리한다", 2),
            new Prio("medium", "보통", "equal", "warning", "순서대로 처리한다", 3),
            new Prio("low", "낮음", "chevron-down", "info", "여유가 있을 때 처리한다", 4),
            new Prio("lowest", "최하", "chevrons-down", "neutral", "미뤄도 된다", 5));

    static final List<Cat> CATEGORIES = List.of(
            new Cat("todo", "할 일", "new", "neutral", 1),
            new Cat("inprogress", "진행 중", "active", "info", 2),
            new Cat("done", "완료", "complete", "success", 3));
    static final List<Type> TYPES = List.of(
            new Type("task", "작업", "check-square", "info", "standard", 1),
            new Type("story", "스토리", "bookmark", "success", "standard", 2),
            new Type("bug", "버그", "bug", "danger", "standard", 3),
            new Type("epic", "에픽", "zap", "warning", "epic", 4),
            new Type("subtask", "하위 작업", "list-tree", "neutral", "subtask", 5));

    @Override
    public void run(ApplicationArguments args) {
        ensureDefaults();
    }

    @Transactional
    public void ensureDefaults() {
        for (Prio p : PRIORITIES) {
            if (priorities.findById(p.id()).isEmpty()) {
                PriorityDef def = PriorityDef.of(p.id(), p.name(), p.icon(), p.color(), p.description(), p.order());
                def.markBuiltIn();
                priorities.save(def);
            }
        }
        for (Cat c : CATEGORIES) {
            if (categories.findById(c.id()).isEmpty()) {
                StatusCategory category = StatusCategory.of(c.id(), c.name(), c.kind(), c.color(), c.order());
                markBuiltIn(category);
                categories.save(category);
            }
        }
        for (Cat c : CATEGORIES) {
            if (statuses.findById(c.id()).isEmpty()) statuses.save(StatusDef.of(c.id(), c.name(), c.id(), ""));
        }
        for (Type t : TYPES) {
            if (types.findById(t.id()).isEmpty()) {
                IssueTypeDef def = IssueTypeDef.of(t.id(), t.name(), t.icon(), t.color(), t.level(), "", t.order());
                markBuiltIn(def);
                types.save(def);
            }
        }
        if (schemes.findFirstByIsDefaultTrue().isEmpty()) {
            SettingsScheme scheme = schemes.findById(SchemeService.DEFAULT_SCHEME).orElseGet(() ->
                    schemes.save(SettingsScheme.of(SchemeService.DEFAULT_SCHEME, "기본 스킴",
                            queries.serialize(SettingsBody.defaults()))));
            scheme.setDefault(true);
        }
        String defaultId = schemes.findFirstByIsDefaultTrue().map(SettingsScheme::getId).orElse(SchemeService.DEFAULT_SCHEME);
        for (Project project : projects.findAll()) {
            if (!projectSettings.existsById(project.getId())) {
                projectSettings.save(ProjectSettings.of(project.getId(), defaultId));
            }
        }
    }

    /** 기본값 플래그 — 엔티티 팩토리는 사용자 정의(false)로 만들기 때문에 여기서만 켠다 */
    private static void markBuiltIn(Object entity) {
        try {
            var field = entity.getClass().getDeclaredField("builtIn");
            field.setAccessible(true);
            field.set(entity, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("builtIn 플래그를 설정할 수 없습니다", e);
        }
    }
}
