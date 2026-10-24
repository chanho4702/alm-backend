package com.platform.almbackend.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.SavedFilter;
import com.platform.almbackend.domain.SavedView;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.SavedViewRepository;
import com.platform.almbackend.search.aql.AqlSyntax;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 저장된 보기(V31, D1). 공유 모델은 저장 필터(F-12)와 같은 결이다 — <b>질의와 표시 옵션만</b> 공유하고, 결과는 여는
 * 사람이 자기 권한으로 다시 실행한다. 차이는 공유 범위가 조직 전체가 아니라 <b>프로젝트</b>라는 점이다(Linear 팀 보기).
 *
 * <ul>
 *   <li>personal: 만든 사람만 본다. 남이 지목하면 404(있다는 사실도 알리지 않는다). 프로젝트를 지정하면 그 프로젝트 VIEW가 필요하다.</li>
 *   <li>project: 그 프로젝트 VIEW면 누구나 본다. 만들기는 EDIT, 고치기·지우기는 만든 사람(지금 EDIT) 또는 프로젝트 ADMIN.
 *       볼 수 있는 남의 것을 고치려 하면 403(보이는 것을 없다고 하지 않는다).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SavedViewService {

    private static final int MAX_NAME = 60;
    private static final int MAX_QUERY = 4000;
    private static final String NOT_FOUND = "저장된 보기를 찾을 수 없습니다: ";

    private final SavedViewRepository views;
    private final ProjectService projects;
    private final ObjectMapper json;

    @Schema(description = "보기의 필터 — 저장 필터와 같은 모양(kind = smart·aql)")
    public record ViewFilter(
            @Schema(description = "smart(스마트 검색 문자열) 또는 aql", example = "aql") String kind,
            @Schema(description = "질의(1~4000자). aql이면 저장할 때 문법을 검사한다", example = "status != done AND flagged = true")
            String query) {}

    @Schema(description = "저장된 보기")
    public record ViewResponse(
            @Schema(example = "31") long id,
            @Schema(example = "막힌 이슈") String name,
            @Schema(description = "personal·project", example = "project") String scope,
            @Schema(description = "프로젝트 ID. 프로젝트 밖 개인 보기면 null", example = "7") Long projectId,
            @Schema(example = "42") long ownerId,
            @Schema(description = "내가 만든 보기인가") boolean mine,
            @Schema(description = "내가 고치거나 지울 수 있는가") boolean canEdit,
            @Schema(description = "list·board", example = "board") String layout,
            @Schema(description = "필터. 없으면 null(프로젝트 전체)") ViewFilter filter,
            ViewDisplay display,
            @Schema(description = "낙관적 락 버전. 수정 요청의 expectedVersion에 넣는다", example = "1") int version,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(description = "보기 만들기")
    public record ViewCreateRequest(
            @Schema(description = "이름(1~60자)", example = "막힌 이슈") String name,
            @Schema(description = "personal(기본)·project", example = "project") String scope,
            @Schema(description = "프로젝트 ID — project 범위면 필수. 만든 뒤 바꿀 수 없다", example = "7") Long projectId,
            @Schema(description = "list(기본)·board", example = "board") String layout,
            ViewFilter filter,
            @Valid ViewDisplay display) {}

    @Schema(description = "보기 고치기 — null인 항목은 그대로. filter를 비우려면 clearFilter=true")
    public record ViewUpdateRequest(
            String name,
            @Schema(description = "personal·project — project로 바꾸려면 프로젝트가 있는 보기여야 한다") String scope,
            String layout,
            ViewFilter filter,
            @Schema(description = "true면 필터를 지운다(프로젝트 전체)") Boolean clearFilter,
            ViewDisplay display,
            @Schema(description = "고치기 직전에 읽은 버전. 다르면 409", example = "1")
            @NotNull(message = "expectedVersion이 필요합니다") Integer expectedVersion) {}

    // ── 읽기 ──

    /** 내 개인 보기 — projectId를 주면 그 프로젝트 것만 */
    @Transactional(readOnly = true)
    public List<ViewResponse> mine(long userId, Long projectId) {
        List<SavedView> found = projectId == null
                ? views.findByOwnerIdAndScope(userId, SavedView.SCOPE_PERSONAL)
                : views.findByOwnerIdAndProjectIdAndScope(userId, projectId, SavedView.SCOPE_PERSONAL);
        return sorted(found.stream().map(v -> response(v, userId, true)).toList());
    }

    /** 프로젝트 화면의 보기 — 프로젝트 공유 보기 + 그 프로젝트의 내 개인 보기 */
    @Transactional(readOnly = true)
    public List<ViewResponse> forProject(long userId, long projectId) {
        projects.require(userId, projectId, AlmAction.VIEW);
        boolean admin = projects.hasGrant(userId, projectId, AlmAction.ADMIN);
        boolean editor = admin || projects.hasGrant(userId, projectId, AlmAction.EDIT);
        List<ViewResponse> out = new ArrayList<>();
        for (SavedView view : views.findByProjectIdAndScope(projectId, SavedView.SCOPE_PROJECT)) {
            boolean mine = view.getOwnerId() == userId;
            out.add(response(view, userId, admin || (mine && editor)));
        }
        for (SavedView view : views.findByOwnerIdAndProjectIdAndScope(userId, projectId, SavedView.SCOPE_PERSONAL)) {
            out.add(response(view, userId, true));
        }
        return sorted(out);
    }

    @Transactional(readOnly = true)
    public ViewResponse get(long userId, long viewId) {
        SavedView view = requireVisible(userId, viewId);
        return response(view, userId, canEdit(userId, view));
    }

    // ── 쓰기 ──

    public ViewResponse create(long userId, ViewCreateRequest request) {
        String scope = scope(request.scope());
        Long projectId = request.projectId();
        if (SavedView.SCOPE_PROJECT.equals(scope)) {
            if (projectId == null) throw new IllegalArgumentException("프로젝트 보기에는 프로젝트가 필요합니다");
            projects.require(userId, projectId, AlmAction.EDIT);
        } else if (projectId != null) {
            projects.require(userId, projectId, AlmAction.VIEW);
        }
        ViewFilter filter = filter(request.filter());
        SavedView view = SavedView.of(userId, projectId, scope, name(request.name()), layout(request.layout()),
                filter == null ? null : filter.kind(), filter == null ? null : filter.query(),
                write(ViewDisplay.normalize(request.display())), now());
        return response(views.saveAndFlush(view), userId, true);
    }

    public ViewResponse update(long userId, long viewId, ViewUpdateRequest request) {
        SavedView view = requireVisible(userId, viewId);
        requireEditable(userId, view);
        if (!view.getVersion().equals(request.expectedVersion())) {
            throw new ConflictException("다른 사용자가 먼저 보기를 수정했습니다. 현재 "
                    + view.getVersion() + ", 요청 " + request.expectedVersion());
        }
        String scope = request.scope() == null ? view.getScope() : scope(request.scope());
        if (!scope.equals(view.getScope())) {
            if (SavedView.SCOPE_PROJECT.equals(scope)) {
                if (view.getProjectId() == null) {
                    throw new IllegalArgumentException("프로젝트가 없는 보기는 프로젝트에 공유할 수 없습니다");
                }
                projects.require(userId, view.getProjectId(), AlmAction.EDIT);
            } else if (view.getOwnerId() != userId) {
                // 남의 공유 보기를 내 것도 아닌 비공개로 만들면 만든 사람 말고는 아무도 못 본다
                throw new ForbiddenException("공유 보기를 비공개로 돌리는 것은 만든 사람만 할 수 있습니다");
            }
        }
        String kind = view.getFilterKind();
        String query = view.getFilterQuery();
        if (Boolean.TRUE.equals(request.clearFilter())) {
            kind = null;
            query = null;
        } else if (request.filter() != null) {
            ViewFilter filter = filter(request.filter());
            kind = filter.kind();
            query = filter.query();
        }
        String display = request.display() == null ? view.getDisplayJson() : write(ViewDisplay.normalize(request.display()));
        view.edit(scope,
                request.name() == null ? view.getName() : name(request.name()),
                request.layout() == null ? view.getLayout() : layout(request.layout()),
                kind, query, display, now());
        views.flush();
        return response(view, userId, canEdit(userId, view));
    }

    public void delete(long userId, long viewId) {
        SavedView view = requireVisible(userId, viewId);
        requireEditable(userId, view);
        views.delete(view);
    }

    // ── 권한 ──

    /** 볼 수 있는 보기 — 내 것이거나, 볼 수 있는 프로젝트의 공유 보기. 그 밖은 404 */
    private SavedView requireVisible(long userId, long viewId) {
        SavedView view = views.findById(viewId).orElseThrow(() -> new NotFoundException(NOT_FOUND + viewId));
        if (view.getOwnerId() == userId) return view;
        if (view.isProjectScoped() && projects.canView(userId, view.getProjectId())) return view;
        throw new NotFoundException(NOT_FOUND + viewId);
    }

    private boolean canEdit(long userId, SavedView view) {
        if (!view.isProjectScoped()) return view.getOwnerId() == userId;
        if (projects.hasGrant(userId, view.getProjectId(), AlmAction.ADMIN)) return true;
        return view.getOwnerId() == userId && projects.hasGrant(userId, view.getProjectId(), AlmAction.EDIT);
    }

    private void requireEditable(long userId, SavedView view) {
        if (canEdit(userId, view)) {
            // 보관된 프로젝트의 공유 보기는 읽기만 — 프로젝트 쓰기 가드와 같은 규칙
            if (view.isProjectScoped() && projects.requireProject(view.getProjectId()).isArchived()) {
                throw new ForbiddenException("보관된 프로젝트는 읽기만 할 수 있습니다");
            }
            return;
        }
        throw new ForbiddenException("공유된 보기는 만든 사람이나 프로젝트 관리자만 수정·삭제할 수 있습니다");
    }

    // ── 값 ──

    private ViewResponse response(SavedView view, long viewerId, boolean canEdit) {
        ViewFilter filter = view.getFilterKind() == null ? null : new ViewFilter(view.getFilterKind(), view.getFilterQuery());
        return new ViewResponse(view.getId(), view.getName(), view.getScope(), view.getProjectId(), view.getOwnerId(),
                view.getOwnerId() == viewerId, canEdit, view.getLayout(), filter, read(view.getDisplayJson()),
                view.getVersion(), view.getCreatedAt(), view.getUpdatedAt());
    }

    /** 공유 보기 먼저, 그 안에서 이름 순(한국어 Collator, 대소문자 무시) — 동률은 id */
    private static List<ViewResponse> sorted(List<ViewResponse> list) {
        Collator collator = Collator.getInstance(Locale.KOREAN);
        collator.setStrength(Collator.PRIMARY);
        Map<String, Integer> scopeOrder = new HashMap<>(Map.of(SavedView.SCOPE_PROJECT, 0, SavedView.SCOPE_PERSONAL, 1));
        return list.stream()
                .sorted(Comparator.<ViewResponse>comparingInt(v -> scopeOrder.getOrDefault(v.scope(), 2))
                        .thenComparing(ViewResponse::name, collator)
                        .thenComparingLong(ViewResponse::id))
                .toList();
    }

    private static String scope(String value) {
        if (value == null || value.isBlank()) return SavedView.SCOPE_PERSONAL;
        String trimmed = value.trim();
        if (!SavedView.SCOPE_PERSONAL.equals(trimmed) && !SavedView.SCOPE_PROJECT.equals(trimmed)) {
            throw new IllegalArgumentException("보기 공유 범위는 personal 또는 project입니다");
        }
        return trimmed;
    }

    private static String layout(String value) {
        if (value == null || value.isBlank()) return "list";
        String trimmed = value.trim();
        if (!"list".equals(trimmed) && !"board".equals(trimmed)) {
            throw new IllegalArgumentException("보기 레이아웃은 list 또는 board입니다");
        }
        return trimmed;
    }

    private static String name(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("보기 이름을 입력하세요");
        if (trimmed.length() > MAX_NAME) throw new IllegalArgumentException("보기 이름은 " + MAX_NAME + "자 이하여야 합니다");
        return trimmed;
    }

    /** 필터 — 저장 필터와 같은 규칙(종류 smart·aql, aql은 저장할 때 문법 검사) */
    private static ViewFilter filter(ViewFilter filter) {
        if (filter == null) return null;
        String kind = filter.kind() == null ? "" : filter.kind().trim();
        if (!SavedFilter.KIND_SMART.equals(kind) && !SavedFilter.KIND_AQL.equals(kind)) {
            throw new IllegalArgumentException("필터 종류는 smart 또는 aql입니다");
        }
        String query = filter.query() == null ? "" : filter.query().trim();
        if (query.isEmpty()) throw new IllegalArgumentException("필터 질의를 입력하세요");
        if (query.length() > MAX_QUERY) throw new IllegalArgumentException("필터 질의는 " + MAX_QUERY + "자 이하여야 합니다");
        if (SavedFilter.KIND_AQL.equals(kind)) AqlSyntax.requireValid(query);
        return new ViewFilter(kind, query);
    }

    private String write(ViewDisplay display) {
        try {
            return json.writeValueAsString(display);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("표시 옵션을 저장하지 못했습니다", e);
        }
    }

    /** 저장된 값은 정규화를 거쳤지만, 읽을 때도 정규화해 옛 값·손댄 값이 화면을 깨지 않게 한다(실패하면 기본값) */
    private ViewDisplay read(String text) {
        try {
            return ViewDisplay.normalize(json.readValue(text, ViewDisplay.class));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return ViewDisplay.normalize(null);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
