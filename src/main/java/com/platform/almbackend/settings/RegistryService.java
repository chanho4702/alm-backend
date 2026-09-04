package com.platform.almbackend.settings;

import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.repository.LinkTypeDefRepository;
import com.platform.almbackend.repository.IssueLinkRepository;
import com.platform.almbackend.domain.LinkTypeDef;
import com.platform.almbackend.settings.dto.RegistryRequests.LinkTypeRequest;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.settings.dto.RegistryRequests.PriorityRequest;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import com.platform.almbackend.settings.dto.RegistryRequests.CategoryRequest;
import com.platform.almbackend.settings.dto.RegistryRequests.IssueTypeRequest;
import com.platform.almbackend.settings.dto.RegistryRequests.StatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 전역 레지스트리(상태 카테고리·상태·이슈 타입) — 프론트 목업 스토어와 같은 규칙:
 * 기본값은 의미/계층을 못 바꾸고 못 지운다, 이름은 유일, 쓰는 곳이 있으면 못 지운다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RegistryService {
    static final List<String> KINDS = List.of("new", "active", "complete");
    static final Set<String> COLORS = Set.of("neutral", "info", "success", "warning", "danger");
    static final Set<String> LEVELS = Set.of("epic", "standard", "subtask");

    private final StatusCategoryRepository categories;
    private final StatusDefRepository statuses;
    private final IssueTypeDefRepository types;
    private final PriorityDefRepository priorities;
    private final LinkTypeDefRepository linkTypes;
    private final IssueLinkRepository issueLinks;
    private final IssueRepository issues;
    private final SchemeQueries schemes;

    // ── 카테고리 ──

    @Transactional(readOnly = true)
    public List<StatusCategory> categories() { return categories.findAllByOrderBySortOrderAsc(); }

    public StatusCategory createCategory(CategoryRequest request) {
        String name = requireName(request.name(), "카테고리 이름을 입력하세요");
        if (categories.existsByName(name)) throw new IllegalArgumentException("카테고리 이름이 중복됩니다: " + name);
        requireKind(request.kind());
        requireColor(request.color());
        int order = categories.findAllByOrderBySortOrderAsc().size() + 1;
        return categories.save(StatusCategory.of(newId("cat"), name, request.kind(), request.color(), order));
    }

    public StatusCategory updateCategory(String id, CategoryRequest request) {
        StatusCategory category = requireCategory(id);
        if (request.kind() != null && !request.kind().equals(category.getKind())) {
            if (category.isBuiltIn()) throw new IllegalArgumentException("기본 카테고리의 의미는 바꿀 수 없습니다");
            requireKind(request.kind());
            String previous = category.getKind();
            category.changeKind(request.kind());
            Set<String> affected = new java.util.HashSet<>();
            for (StatusDef def : statuses.findByCategoryId(id)) affected.add(def.getId());
            if (schemes.anyBodyLosesKind(affected)) {
                category.changeKind(previous);
                throw new IllegalArgumentException("이 카테고리를 쓰는 워크플로에서 의미(할 일/진행 중/완료)가 비게 됩니다");
            }
        }
        if (request.name() != null) {
            String name = requireName(request.name(), "카테고리 이름을 입력하세요");
            if (categories.existsByNameAndIdNot(name, id)) throw new IllegalArgumentException("카테고리 이름이 중복됩니다: " + name);
            category.rename(name);
        }
        if (request.color() != null) {
            requireColor(request.color());
            category.recolor(request.color());
        }
        return category;
    }

    public void moveCategory(String id, int delta) {
        List<StatusCategory> sorted = categories.findAllByOrderBySortOrderAsc();
        int index = indexOf(sorted.stream().map(StatusCategory::getId).toList(), id, "카테고리를 찾을 수 없습니다");
        int target = index + delta;
        if (target < 0 || target >= sorted.size()) return;
        StatusCategory a = sorted.get(index);
        StatusCategory b = sorted.get(target);
        int tmp = a.getSortOrder();
        a.reorder(b.getSortOrder());
        b.reorder(tmp);
    }

    public void deleteCategory(String id) {
        StatusCategory category = requireCategory(id);
        if (category.isBuiltIn()) throw new IllegalArgumentException("기본 카테고리는 삭제할 수 없습니다");
        if (!statuses.findByCategoryId(id).isEmpty()) throw new IllegalArgumentException("이 카테고리를 쓰는 상태가 있습니다");
        categories.delete(category);
        List<StatusCategory> rest = categories.findAllByOrderBySortOrderAsc();
        for (int i = 0; i < rest.size(); i++) rest.get(i).reorder(i + 1);
    }

    // ── 상태 ──

    @Transactional(readOnly = true)
    public List<StatusDef> statuses() { return statuses.findAllByOrderByIdAsc(); }

    /** 상태 id → 쓰는 워크플로 수 */
    @Transactional(readOnly = true)
    public Map<String, Long> statusUsage() { return schemes.statusUsage(); }

    public StatusDef createStatus(StatusRequest request) {
        String name = requireName(request.name(), "상태 이름을 입력하세요");
        if (statuses.existsByName(name)) throw new IllegalArgumentException("상태 이름이 중복됩니다: " + name);
        requireCategory(request.categoryId());
        return statuses.save(StatusDef.of(newId("st"), name, request.categoryId(), request.description(),
                StatusIcons.normalize(request.icon())));
    }

    public StatusDef updateStatus(String id, StatusRequest request) {
        StatusDef def = requireStatus(id);
        if (request.name() != null) {
            String name = requireName(request.name(), "상태 이름을 입력하세요");
            if (statuses.existsByNameAndIdNot(name, id)) throw new IllegalArgumentException("상태 이름이 중복됩니다: " + name);
            def.rename(name);
        }
        if (request.categoryId() != null && !request.categoryId().equals(def.getCategoryId())) {
            requireCategory(request.categoryId());
            String previous = def.getCategoryId();
            def.moveTo(request.categoryId());
            if (schemes.anyBodyLosesKind(Set.of(id))) {
                def.moveTo(previous);
                throw new IllegalArgumentException("이 상태를 쓰는 워크플로에서 의미(할 일/진행 중/완료)가 비게 됩니다");
            }
        }
        if (request.description() != null) def.describe(request.description());
        // 아이콘은 빈 문자열도 유효한 값(미지정 → 카테고리 기본)이라 null만 "안 바꿈"으로 본다
        if (request.icon() != null) def.reicon(StatusIcons.normalize(request.icon()));
        schemes.syncStatusCache(def);
        return def;
    }

    public void deleteStatus(String id) {
        StatusDef def = requireStatus(id);
        if (schemes.statusUsage().getOrDefault(id, 0L) > 0) {
            throw new IllegalArgumentException("워크플로에서 쓰는 상태는 삭제할 수 없습니다");
        }
        statuses.delete(def);
    }

    // ── 이슈 타입 ──

    @Transactional(readOnly = true)
    public List<IssueTypeDef> issueTypes() { return types.findAllByOrderBySortOrderAsc(); }

    @Transactional(readOnly = true)
    public Map<String, Long> issueTypeUsage() {
        Map<String, Long> usage = new java.util.LinkedHashMap<>();
        for (IssueTypeDef def : types.findAllByOrderBySortOrderAsc()) usage.put(def.getId(), issues.countByType(def.getId()));
        return usage;
    }

    public IssueTypeDef createIssueType(IssueTypeRequest request) {
        String name = requireName(request.name(), "이슈 타입 이름을 입력하세요");
        if (types.existsByName(name)) throw new IllegalArgumentException("이슈 타입 이름이 중복됩니다: " + name);
        requireLevel(request.level());
        requireColor(request.color());
        if (request.icon() == null || request.icon().isBlank()) throw new IllegalArgumentException("아이콘을 고르세요");
        int order = types.findAllByOrderBySortOrderAsc().size() + 1;
        return types.save(IssueTypeDef.of(newId("it"), name, request.icon(), request.color(), request.level(),
                request.description(), order));
    }

    public IssueTypeDef updateIssueType(String id, IssueTypeRequest request) {
        IssueTypeDef def = requireIssueType(id);
        if (request.level() != null && !request.level().equals(def.getLevel())) {
            if (def.isBuiltIn()) throw new IllegalArgumentException("기본 이슈 타입의 계층은 바꿀 수 없습니다");
            if (issues.countByType(id) > 0) throw new IllegalArgumentException("이 타입을 쓰는 이슈가 있어 계층을 바꿀 수 없습니다");
            requireLevel(request.level());
            def.changeLevel(request.level());
        }
        if (request.name() != null) {
            String name = requireName(request.name(), "이슈 타입 이름을 입력하세요");
            if (types.existsByNameAndIdNot(name, id)) throw new IllegalArgumentException("이슈 타입 이름이 중복됩니다: " + name);
            def.rename(name);
        }
        if (request.color() != null) requireColor(request.color());
        def.restyle(request.icon(), request.color());
        if (request.description() != null) def.describe(request.description());
        return def;
    }

    public void moveIssueType(String id, int delta) {
        List<IssueTypeDef> sorted = types.findAllByOrderBySortOrderAsc();
        int index = indexOf(sorted.stream().map(IssueTypeDef::getId).toList(), id, "이슈 타입을 찾을 수 없습니다");
        int target = index + delta;
        if (target < 0 || target >= sorted.size()) return;
        IssueTypeDef a = sorted.get(index);
        IssueTypeDef b = sorted.get(target);
        int tmp = a.getSortOrder();
        a.reorder(b.getSortOrder());
        b.reorder(tmp);
    }

    public void deleteIssueType(String id) {
        IssueTypeDef def = requireIssueType(id);
        if (def.isBuiltIn()) throw new IllegalArgumentException("기본 이슈 타입은 삭제할 수 없습니다");
        if (issues.countByType(id) > 0) throw new IllegalArgumentException("이 타입을 쓰는 이슈가 있습니다");
        types.delete(def);
        schemes.removeTypeEverywhere(id);
        List<IssueTypeDef> rest = types.findAllByOrderBySortOrderAsc();
        for (int i = 0; i < rest.size(); i++) rest.get(i).reorder(i + 1);
    }

    // ── 우선순위 ──

    @Transactional(readOnly = true)
    public List<PriorityDef> priorities() { return priorities.findAllByOrderBySortOrderAsc(); }

    @Transactional(readOnly = true)
    public Map<String, Long> priorityUsage() {
        Map<String, Long> usage = new java.util.LinkedHashMap<>();
        for (PriorityDef def : priorities.findAllByOrderBySortOrderAsc()) usage.put(def.getId(), issues.countByPriority(def.getId()));
        return usage;
    }

    public PriorityDef createPriority(PriorityRequest request) {
        String name = requireName(request.name(), "우선순위 이름을 입력하세요");
        if (priorities.existsByName(name)) throw new IllegalArgumentException("우선순위 이름이 중복됩니다: " + name);
        requireColor(request.color());
        if (request.icon() == null || request.icon().isBlank()) throw new IllegalArgumentException("아이콘을 고르세요");
        int order = priorities.findAllByOrderBySortOrderAsc().size() + 1;
        return priorities.save(PriorityDef.of(newId("pr"), name, request.icon(), request.color(), request.description(), order));
    }

    public PriorityDef updatePriority(String id, PriorityRequest request) {
        PriorityDef def = requirePriority(id);
        if (request.name() != null) {
            String name = requireName(request.name(), "우선순위 이름을 입력하세요");
            if (priorities.existsByNameAndIdNot(name, id)) throw new IllegalArgumentException("우선순위 이름이 중복됩니다: " + name);
            def.rename(name);
        }
        if (request.color() != null) requireColor(request.color());
        def.restyle(request.icon(), request.color());
        if (request.description() != null) def.describe(request.description());
        return def;
    }

    public void movePriority(String id, int delta) {
        List<PriorityDef> sorted = priorities.findAllByOrderBySortOrderAsc();
        int index = indexOf(sorted.stream().map(PriorityDef::getId).toList(), id, "우선순위를 찾을 수 없습니다");
        int target = index + delta;
        if (target < 0 || target >= sorted.size()) return;
        PriorityDef a = sorted.get(index);
        PriorityDef b = sorted.get(target);
        int tmp = a.getSortOrder();
        a.reorder(b.getSortOrder());
        b.reorder(tmp);
    }

    public void deletePriority(String id) {
        PriorityDef def = requirePriority(id);
        if (def.isBuiltIn()) throw new IllegalArgumentException("기본 우선순위는 삭제할 수 없습니다");
        if (issues.countByPriority(id) > 0) throw new IllegalArgumentException("이 우선순위를 쓰는 이슈가 있습니다");
        priorities.delete(def);
        schemes.removePriorityEverywhere(id);
        List<PriorityDef> rest = priorities.findAllByOrderBySortOrderAsc();
        for (int i = 0; i < rest.size(); i++) rest.get(i).reorder(i + 1);
    }

    public PriorityDef requirePriority(String id) {
        return priorities.findById(id == null ? "" : id)
                .orElseThrow(() -> new NotFoundException("우선순위를 찾을 수 없습니다"));
    }

    // ── 링크 타입 ──

    @Transactional(readOnly = true)
    public List<LinkTypeDef> linkTypes() { return linkTypes.findAllByOrderBySortOrderAsc(); }

    @Transactional(readOnly = true)
    public Map<String, Long> linkTypeUsage() {
        Map<String, Long> usage = new java.util.LinkedHashMap<>();
        for (LinkTypeDef def : linkTypes.findAllByOrderBySortOrderAsc()) usage.put(def.getId(), issueLinks.countByType(def.getId()));
        return usage;
    }

    public LinkTypeDef createLinkType(LinkTypeRequest request) {
        String name = requireName(request.name(), "링크 타입 이름을 입력하세요");
        if (linkTypes.existsByName(name)) throw new IllegalArgumentException("링크 타입 이름이 중복됩니다: " + name);
        String outward = requireName(request.outward(), "나가는 문구(예: 차단함)를 입력하세요");
        String inward = requireName(request.inward(), "들어오는 문구(예: 차단됨)를 입력하세요");
        int order = linkTypes.findAllByOrderBySortOrderAsc().size() + 1;
        return linkTypes.save(LinkTypeDef.of(newId("lt"), name, outward, inward, order));
    }

    public LinkTypeDef updateLinkType(String id, LinkTypeRequest request) {
        LinkTypeDef def = requireLinkType(id);
        if (request.name() != null) {
            String name = requireName(request.name(), "링크 타입 이름을 입력하세요");
            if (linkTypes.existsByNameAndIdNot(name, id)) throw new IllegalArgumentException("링크 타입 이름이 중복됩니다: " + name);
            def.rename(name);
        }
        String outward = request.outward() == null ? null : requireName(request.outward(), "나가는 문구(예: 차단함)를 입력하세요");
        String inward = request.inward() == null ? null : requireName(request.inward(), "들어오는 문구(예: 차단됨)를 입력하세요");
        if ((outward != null || inward != null) && issueLinks.countByType(id) > 0) {
            boolean wasSymmetric = def.isSymmetric();
            String nextOut = outward == null ? def.getOutward() : outward;
            String nextIn = inward == null ? def.getInward() : inward;
            if (wasSymmetric != nextOut.equals(nextIn)) {
                throw new IllegalArgumentException("이 타입을 쓰는 링크가 있어 방향성(대칭 여부)을 바꿀 수 없습니다");
            }
        }
        def.relabel(outward, inward);
        return def;
    }

    public void moveLinkType(String id, int delta) {
        List<LinkTypeDef> sorted = linkTypes.findAllByOrderBySortOrderAsc();
        int index = indexOf(sorted.stream().map(LinkTypeDef::getId).toList(), id, "링크 타입을 찾을 수 없습니다");
        int target = index + delta;
        if (target < 0 || target >= sorted.size()) return;
        LinkTypeDef a = sorted.get(index);
        LinkTypeDef b = sorted.get(target);
        int tmp = a.getSortOrder();
        a.reorder(b.getSortOrder());
        b.reorder(tmp);
    }

    public void deleteLinkType(String id) {
        LinkTypeDef def = requireLinkType(id);
        if (def.isBuiltIn()) throw new IllegalArgumentException("기본 링크 타입은 삭제할 수 없습니다");
        if (issueLinks.countByType(id) > 0) throw new IllegalArgumentException("이 타입을 쓰는 링크가 있습니다");
        linkTypes.delete(def);
        List<LinkTypeDef> rest = linkTypes.findAllByOrderBySortOrderAsc();
        for (int i = 0; i < rest.size(); i++) rest.get(i).reorder(i + 1);
    }

    public LinkTypeDef requireLinkType(String id) {
        return linkTypes.findById(id == null ? "" : id)
                .orElseThrow(() -> new NotFoundException("링크 타입을 찾을 수 없습니다"));
    }

    // ── 조회 헬퍼 ──

    public StatusCategory requireCategory(String id) {
        return categories.findById(id == null ? "" : id)
                .orElseThrow(() -> new NotFoundException("카테고리를 찾을 수 없습니다"));
    }

    public StatusDef requireStatus(String id) {
        return statuses.findById(id == null ? "" : id)
                .orElseThrow(() -> new NotFoundException("상태를 찾을 수 없습니다"));
    }

    public IssueTypeDef requireIssueType(String id) {
        return types.findById(id == null ? "" : id)
                .orElseThrow(() -> new NotFoundException("이슈 타입을 찾을 수 없습니다"));
    }

    private static String requireName(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static void requireKind(String kind) {
        if (kind == null || !KINDS.contains(kind)) throw new IllegalArgumentException("카테고리 의미는 new/active/complete 중 하나입니다");
    }

    private static void requireColor(String color) {
        if (color == null || !COLORS.contains(color)) throw new IllegalArgumentException("색은 neutral/info/success/warning/danger 중 하나입니다");
    }

    private static void requireLevel(String level) {
        if (level == null || !LEVELS.contains(level)) throw new IllegalArgumentException("계층은 epic/standard/subtask 중 하나입니다");
    }

    private static int indexOf(List<String> ids, String id, String message) {
        int index = ids.indexOf(id);
        if (index < 0) throw new NotFoundException(message);
        return index;
    }

    private static String newId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
