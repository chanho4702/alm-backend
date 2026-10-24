package com.platform.almbackend.board;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Board;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.domain.SprintState;
import com.platform.almbackend.issue.IssueResponses;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.BoardRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.settings.SchemeService;
import com.platform.almbackend.settings.SettingsBody;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 보드 — 보는 방법(필터·컬럼·스윔레인)만 저장한다. scrum은 활성 스프린트 이슈, kanban은
 * 프로젝트 전체. 마지막 보드는 못 지운다(프론트 목업과 같은 규칙).
 *
 * <p><b>컬럼 두 방식</b>(2026-10-24 C4, columns_json 그대로 — 마이그레이션 없음):
 * <ul>
 *   <li>{@code status}(구 형식): 열 = 상태 1:1, 저장 값은 상태별 이름·WIP 덮어쓰기뿐. 열 구성은 워크플로 상태 목록에서
 *       파생된다(상태가 늘면 열도 는다).</li>
 *   <li>{@code mapped}(지라 보드 열 매핑): 열마다 {@code statuses[]} — 열 하나에 여러 상태, 열 순서·이름은 보드 소유.
 *       어느 열에도 없는 상태는 {@code unmappedStatuses}이고 그 상태의 이슈는 보드에 나오지 않는다.</li>
 * </ul>
 * 응답의 {@code resolvedColumns}는 두 방식 모두 최종 열 목록이다(화면은 이것만 그리면 된다).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BoardService {
    private static final Set<String> TYPES = Set.of("scrum", "kanban");
    private static final Set<String> SWIMLANES = Set.of("none", "assignee", "epic");
    private static final TypeReference<List<Column>> COLUMNS = new TypeReference<>() {};

    private final BoardRepository boards;
    private final IssueRepository issues;
    private final SprintRepository sprints;
    private final ProjectService projectService;
    private final ObjectMapper json;
    private final IssueResponses issueResponses;
    private final SchemeService settings;

    public record Filter(List<String> assigneeIds, List<String> types, List<String> labels) {
        public static Filter empty() { return new Filter(List.of(), List.of(), List.of()); }
        public List<String> assigneeIds() { return assigneeIds == null ? List.of() : assigneeIds; }
        public List<String> types() { return types == null ? List.of() : types; }
        public List<String> labels() { return labels == null ? List.of() : labels; }
    }
    /**
     * 저장 열. 구 형식은 {@code status}만, 매핑 형식은 {@code statuses}(+ 보드가 붙인 {@code id})를 쓴다.
     * 응답에서 매핑 형식의 {@code status}는 첫 상태(구 클라이언트 호환)다.
     */
    public record Column(String id, String status, String name, List<String> statuses, Integer wipLimit) {
        public Column(String status, String name, Integer wipLimit) { this(null, status, name, null, wipLimit); }
    }
    /** 최종 열 — 보드가 실제로 그리는 순서·이름·상태 */
    public record ResolvedColumn(String id, String name, List<String> statuses, Integer wipLimit) {}
    public record BoardResponse(long id, long projectId, String name, String type, Filter filter,
                                List<Column> columns, String swimlane, boolean isDefault, Instant createdAt,
                                String columnMode, List<ResolvedColumn> resolvedColumns, List<String> unmappedStatuses) {}
    public record BoardRequest(String name, String type, Filter filter, List<Column> columns, String swimlane, Boolean isDefault) {}

    @Transactional(readOnly = true)
    public List<BoardResponse> list(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return boards.findByProjectIdOrderByIsDefaultDescCreatedAtAscIdAsc(projectId).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public BoardResponse get(long userId, long boardId) {
        Board board = requireBoard(boardId);
        projectService.require(userId, board.getProjectId(), AlmAction.VIEW);
        return response(board);
    }

    /** 새 프로젝트의 기본 보드 */
    public void createDefault(long projectId) {
        if (boards.countByProjectId(projectId) > 0) return;
        boards.save(Board.of(projectId, "메인 보드", "scrum", write(Filter.empty()), write(List.of()), "none", true, now()));
    }

    public BoardResponse create(long userId, long projectId, BoardRequest request) {
        projectService.require(userId, projectId, AlmAction.EDIT);
        String name = requireName(request.name());
        String type = request.type() == null ? "scrum" : request.type();
        if (!TYPES.contains(type)) throw new IllegalArgumentException("보드 종류는 scrum/kanban 중 하나입니다");
        Board board = boards.save(Board.of(projectId, name, type, write(Filter.empty()), write(List.of()), "none", false, now()));
        return response(board);
    }

    public BoardResponse update(long userId, long boardId, BoardRequest request) {
        Board board = requireBoard(boardId);
        projectService.require(userId, board.getProjectId(), AlmAction.EDIT);
        if (request.name() != null) board.rename(requireName(request.name()));
        if (request.type() != null) {
            if (!TYPES.contains(request.type())) throw new IllegalArgumentException("보드 종류는 scrum/kanban 중 하나입니다");
            board.changeType(request.type());
        }
        if (request.columns() != null) {
            board.replaceColumns(write(normalizeColumns(board.getProjectId(), request.columns())));
        }
        if (request.filter() != null) board.replaceFilter(write(request.filter()));
        if (request.swimlane() != null) {
            if (!SWIMLANES.contains(request.swimlane())) throw new IllegalArgumentException("스윔레인은 none/assignee/epic 중 하나입니다");
            board.changeSwimlane(request.swimlane());
        }
        if (Boolean.TRUE.equals(request.isDefault())) {
            for (Board other : boards.findByProjectIdOrderByIsDefaultDescCreatedAtAscIdAsc(board.getProjectId())) {
                other.setDefault(other.getId().equals(board.getId()));
            }
        }
        return response(board);
    }

    public void delete(long userId, long boardId) {
        Board board = requireBoard(boardId);
        projectService.require(userId, board.getProjectId(), AlmAction.EDIT);
        List<Board> siblings = boards.findByProjectIdOrderByIsDefaultDescCreatedAtAscIdAsc(board.getProjectId());
        if (siblings.size() <= 1) throw new IllegalArgumentException("마지막 보드는 삭제할 수 없습니다");
        boards.delete(board);
        if (board.isDefault()) {
            siblings.stream().filter(b -> !b.getId().equals(board.getId()))
                    .min(Comparator.comparing(Board::getCreatedAt).thenComparing(Board::getId))
                    .ifPresent(b -> b.setDefault(true));
        }
    }

    /**
     * 보드에 보이는 이슈 — scrum: 활성 스프린트(없으면 빈 목록), kanban: 전체 + 저장 필터.
     * 조건은 전부 DB 술어로 내린다(감사 A-07): 예전에는 프로젝트 전 이슈를 메모리에 올려 자바 스트림으로
     * 걸렀다. 보드는 DnD·인라인 생성마다 다시 읽는 가장 잦은 경로다. 스크럼은 (project, sprint, sort) 인덱스,
     * 담당자는 V27 (assignee, project) 인덱스, 라벨은 EXISTS(V27 issue_label(label))로 탄다.
     */
    @Transactional(readOnly = true)
    public List<IssueResponse> issues(long userId, long boardId) {
        Board board = requireBoard(boardId);
        projectService.require(userId, board.getProjectId(), AlmAction.VIEW);
        long projectId = board.getProjectId();
        Long activeSprintId = null;
        if ("scrum".equals(board.getType())) {
            Sprint active = sprints.findByProjectIdAndState(projectId, SprintState.ACTIVE).orElse(null);
            if (active == null) return List.of();
            activeSprintId = active.getId();
        }
        Filter filter = read(board.getFilterJson());
        Specification<Issue> spec = boardSpec(projectId, activeSprintId, filter);
        // 열 매핑 보드(C4): 어느 열에도 없는 상태의 이슈는 보드에 나오지 않는다(지라와 같다)
        BoardResponse layout = response(board);
        if ("mapped".equals(layout.columnMode())) {
            List<String> placed = layout.resolvedColumns().stream().flatMap(c -> c.statuses().stream()).toList();
            if (placed.isEmpty()) return List.of();
            spec = spec.and((root, query, cb) -> root.get("status").in(placed));
        }
        List<Issue> visible = issues.findAll(spec, Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("key")));
        return issueResponses.of(visible);
    }

    /** 보드 조건 — 저장 필터의 의미는 예전 메모리 필터와 같다(담당자 "unassigned"=미지정, 라벨은 하나라도 겹치면) */
    static Specification<Issue> boardSpec(long projectId, Long sprintId, Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("projectId"), projectId));
            // 트리아지 대기열(V35)은 보드에 나오지 않는다 — 수락돼야 워크플로에 들어온다
            where.add(cb.isNull(root.get("triageState")));
            if (sprintId != null) where.add(cb.equal(root.get("sprintId"), sprintId));
            if (!filter.assigneeIds().isEmpty()) {
                List<Long> ids = new ArrayList<>();
                boolean unassigned = false;
                for (String raw : filter.assigneeIds()) {
                    if ("unassigned".equals(raw)) { unassigned = true; continue; }
                    try {
                        ids.add(Long.valueOf(raw));
                    } catch (NumberFormatException ignored) {
                        // 저장된 필터의 잘못된 값은 아무도 잡지 않는다(예전 문자열 비교와 같은 결과)
                    }
                }
                List<Predicate> any = new ArrayList<>();
                if (!ids.isEmpty()) any.add(root.get("assigneeId").in(ids));
                if (unassigned) any.add(cb.isNull(root.get("assigneeId")));
                where.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
            }
            if (!filter.types().isEmpty()) where.add(root.get("type").in(filter.types()));
            if (!filter.labels().isEmpty()) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<Issue> correlated = sub.correlate(root);
                sub.select(cb.literal(1)).where(correlated.join("labels").in(filter.labels()));
                where.add(cb.exists(sub));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** 열 하나라도 statuses를 보내면 매핑 형식이다 — 그때는 전부 매핑 형식이어야 한다(섞지 않는다) */
    private static boolean isMapped(List<Column> columns) {
        return columns.stream().anyMatch(c -> c != null && c.statuses() != null);
    }

    /** 저장 전 검증·정리 — 구 형식은 예전 규칙 그대로, 매핑 형식은 상태 중복·없는 상태를 막고 열 id를 붙인다 */
    private List<Column> normalizeColumns(long projectId, List<Column> columns) {
        for (Column column : columns) {
            if (column == null) throw new IllegalArgumentException("컬럼이 비어 있습니다");
            if (column.name() == null || column.name().isBlank()) throw new IllegalArgumentException("컬럼 이름을 입력하세요");
            if (column.wipLimit() != null && column.wipLimit() < 1) throw new IllegalArgumentException("WIP 제한은 1 이상의 정수여야 합니다");
        }
        if (!isMapped(columns)) {
            Set<String> seen = new HashSet<>();
            for (Column column : columns) {
                if (column.status() == null || !seen.add(column.status())) throw new IllegalArgumentException("컬럼은 상태마다 하나여야 합니다");
            }
            return columns;
        }
        Set<String> known = new HashSet<>();
        for (SettingsBody.WorkflowStatus s : settings.body(projectId).statuses()) known.add(s.id());
        Set<String> seenStatus = new HashSet<>();
        Set<String> seenId = new HashSet<>();
        List<Column> out = new ArrayList<>();
        for (Column column : columns) {
            if (column.statuses() == null) {
                throw new IllegalArgumentException("열 매핑을 쓰는 보드는 모든 열에 상태 목록(statuses)이 있어야 합니다");
            }
            List<String> statuses = new ArrayList<>(new LinkedHashSet<>(column.statuses()));
            for (String status : statuses) {
                if (!known.contains(status)) throw new IllegalArgumentException("이 프로젝트에 없는 상태입니다: " + status);
                if (!seenStatus.add(status)) throw new IllegalArgumentException("상태 하나는 한 열에만 넣을 수 있습니다: " + status);
            }
            String id = column.id() == null || column.id().isBlank()
                    ? "col-" + UUID.randomUUID().toString().substring(0, 8) : column.id().trim();
            if (!seenId.add(id)) throw new IllegalArgumentException("같은 열 id가 두 번 있습니다: " + id);
            out.add(new Column(id, null, column.name().trim(), statuses, column.wipLimit()));
        }
        return out;
    }

    private BoardResponse response(Board board) {
        List<Column> stored;
        try {
            stored = json.readValue(board.getColumnsJson(), COLUMNS);
        } catch (JsonProcessingException e) {
            stored = List.of();
        }
        List<SettingsBody.WorkflowStatus> statuses = settings.body(board.getProjectId()).statuses().stream()
                .sorted(Comparator.comparingInt(SettingsBody.WorkflowStatus::order)).toList();
        Set<String> known = new HashSet<>();
        for (SettingsBody.WorkflowStatus s : statuses) known.add(s.id());
        boolean mapped = isMapped(stored);
        List<Column> columns = new ArrayList<>();
        List<ResolvedColumn> resolved = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        if (mapped) {
            Set<String> placed = new HashSet<>();
            for (Column c : stored) {
                // 워크플로에서 빠진 상태는 열에서도 뺀다(설정 변경이 보드 JSON까지 고치지 않는다)
                List<String> live = new ArrayList<>();
                if (c.statuses() != null) for (String id : c.statuses()) if (known.contains(id)) live.add(id);
                placed.addAll(live);
                columns.add(new Column(c.id(), live.isEmpty() ? null : live.get(0), c.name(), live, c.wipLimit()));
                resolved.add(new ResolvedColumn(c.id(), c.name(), live, c.wipLimit()));
            }
            for (SettingsBody.WorkflowStatus s : statuses) if (!placed.contains(s.id())) unmapped.add(s.id());
        } else {
            columns.addAll(stored);
            Map<String, Column> overrides = new HashMap<>();
            for (Column c : stored) if (c.status() != null) overrides.put(c.status(), c);
            for (SettingsBody.WorkflowStatus s : statuses) {
                Column o = overrides.get(s.id());
                String name = o != null && o.name() != null && !o.name().isBlank() ? o.name() : s.name();
                resolved.add(new ResolvedColumn(s.id(), name, List.of(s.id()), o == null ? null : o.wipLimit()));
            }
        }
        return new BoardResponse(board.getId(), board.getProjectId(), board.getName(), board.getType(),
                read(board.getFilterJson()), columns, board.getSwimlane(), board.isDefault(), board.getCreatedAt(),
                mapped ? "mapped" : "status", resolved, unmapped);
    }

    private Filter read(String filterJson) {
        try {
            return json.readValue(filterJson, Filter.class);
        } catch (JsonProcessingException e) {
            return Filter.empty();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("보드 설정을 저장할 수 없습니다");
        }
    }

    private Board requireBoard(long boardId) {
        return boards.findById(boardId).orElseThrow(() -> new NotFoundException("보드를 찾을 수 없습니다"));
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("보드 이름을 입력하세요");
        return name.trim();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
