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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 보드 — 보는 방법(필터·컬럼 오버라이드·스윔레인)만 저장한다. scrum은 활성 스프린트 이슈, kanban은
 * 프로젝트 전체. 컬럼은 상태마다 최대 1개 오버라이드, 마지막 보드는 못 지운다(프론트 목업과 같은 규칙).
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

    public record Filter(List<String> assigneeIds, List<String> types, List<String> labels) {
        public static Filter empty() { return new Filter(List.of(), List.of(), List.of()); }
        public List<String> assigneeIds() { return assigneeIds == null ? List.of() : assigneeIds; }
        public List<String> types() { return types == null ? List.of() : types; }
        public List<String> labels() { return labels == null ? List.of() : labels; }
    }
    public record Column(String status, String name, Integer wipLimit) {}
    public record BoardResponse(long id, long projectId, String name, String type, Filter filter,
                                List<Column> columns, String swimlane, boolean isDefault, Instant createdAt) {}
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
            validateColumns(request.columns());
            board.replaceColumns(write(request.columns()));
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
        List<Issue> visible = issues.findAll(spec, Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("key")));
        return issueResponses.of(visible);
    }

    /** 보드 조건 — 저장 필터의 의미는 예전 메모리 필터와 같다(담당자 "unassigned"=미지정, 라벨은 하나라도 겹치면) */
    static Specification<Issue> boardSpec(long projectId, Long sprintId, Filter filter) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("projectId"), projectId));
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

    private static void validateColumns(List<Column> columns) {
        Set<String> seen = new HashSet<>();
        for (Column column : columns) {
            if (column.status() == null || !seen.add(column.status())) throw new IllegalArgumentException("컬럼은 상태마다 하나여야 합니다");
            if (column.name() == null || column.name().isBlank()) throw new IllegalArgumentException("컬럼 이름을 입력하세요");
            if (column.wipLimit() != null && column.wipLimit() < 1) throw new IllegalArgumentException("WIP 제한은 1 이상의 정수여야 합니다");
        }
    }

    private BoardResponse response(Board board) {
        List<Column> columns;
        try {
            columns = json.readValue(board.getColumnsJson(), COLUMNS);
        } catch (JsonProcessingException e) {
            columns = List.of();
        }
        return new BoardResponse(board.getId(), board.getProjectId(), board.getName(), board.getType(),
                read(board.getFilterJson()), columns, board.getSwimlane(), board.isDefault(), board.getCreatedAt());
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
