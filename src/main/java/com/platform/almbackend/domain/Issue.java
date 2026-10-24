package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "issue")
@SQLRestriction("archived_at is null") // 보관된 이슈는 모든 일반 조회에서 빠진다 — 보관함은 네이티브 쿼리로 읽는다
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Issue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "issue_number", nullable = false, updatable = false)
    private Long issueNumber;

    @Column(name = "issue_key", nullable = false, unique = true, length = 40, updatable = false)
    private String key;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    /** 이슈 타입 레지스트리 id(기본 task/story/bug/epic/subtask + 사용자 정의 it-*). enum으로 고정하지 않는다 */
    @Column(name = "issue_type", nullable = false, length = 40)
    private String type;

    /** 커스텀 워크플로 상태 ID. 카테고리 enum으로 고정하지 않는다. */
    @Column(nullable = false, length = 80)
    private String status;

    @Column(nullable = false, length = 40)
    private String priority;

    /** 보관 시각 — null이면 활성. 보관은 되돌릴 수 있다(휴지통과 다르다) */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "archived_by")
    private Long archivedBy;

    @Column(name = "assignee_id")
    private Long assigneeId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sprint_id")
    private Long sprintId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private IssueResolution resolution;

    /**
     * 해결일 — {@code resolution}이 <b>비어 있다가 채워지는 순간</b>의 시각이다. 해결이 풀리면 다시 null이 된다.
     * 값을 직접 넣지 않는다: 쓰는 길은 {@link #applyResolution}뿐이라 규칙이 한 곳에만 있다.
     *
     * <p>해결 사유를 다른 사유로 바꾸는 것(완료 → 중복)은 다시 해결한 것이 아니므로 시각을 건드리지 않는다.
     */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 수정 버전(fix version). null = 미지정 */
    @Column(name = "fix_version_id")
    private Long fixVersionId;

    @Column(name = "estimate_hours", precision = 10, scale = 2)
    private BigDecimal estimateHours;

    /** 시작일(V30, D3) — 타임라인 막대의 시작. 마감일보다 늦을 수 없다(서비스 400 + DB CHECK) */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** 스토리 포인트(V30) — 0 이상, 소수 한 자리. 예상 시간과 별개의 추정 단위다 */
    @Column(name = "story_points", precision = 6, scale = 1)
    private BigDecimal storyPoints;

    /** 플래그(막힘, V30, D2) — 보드에서 바로 켜고 끈다. 편집 폼의 version을 올리지 않는다({@link #flag}) */
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(nullable = false)
    private boolean flagged;

    /**
     * 트리아지 대기열(V35) — null이면 일반 워크플로 안에 있다. {@code PENDING}(대기)·{@code SNOOZED}(보류).
     * 대기열의 이슈는 보드·백로그·프로젝트 목록에 나오지 않고 트리아지 화면에서 수락·중복·거절·보류된다.
     */
    @Column(name = "triage_state", length = 12)
    private String triageState;

    /** 보류가 풀리는 시각 — 지나면 다시 대기로 보인다(읽을 때 판정, 행은 그대로 둔다) */
    @Column(name = "triage_snoozed_until")
    private Instant triageSnoozedUntil;

    /** 대기열에 들어온 길 — {@code EXTERNAL}(편집 권한 없는 사람이 만듦)·{@code MANUAL}(명시적으로 보냄) */
    @Column(name = "triage_source", length = 16)
    private String triageSource;

    // 목록 응답은 IssueResponses가 페이지 단위로 따로 읽는다. 그 밖의 경로(IssueService.list 등)에서
    // 이슈마다 지연 로딩되더라도 한 번에 최대 100건씩 묶어 읽게 하는 안전망이다
    @ElementCollection
    @BatchSize(size = 100)
    @CollectionTable(name = "issue_label", joinColumns = @JoinColumn(name = "issue_id"))
    @OrderColumn(name = "label_order")
    @Column(name = "label", nullable = false, length = 80)
    private List<String> labels = new ArrayList<>();

    /** 컴포넌트 id 목록(순서 유지) — 검증은 ComponentService가 한다 */
    @ElementCollection
    @BatchSize(size = 100)
    @CollectionTable(name = "issue_component", joinColumns = @JoinColumn(name = "issue_id"))
    @OrderColumn(name = "component_order")
    @Column(name = "component_id", nullable = false)
    private List<Long> componentIds = new ArrayList<>();

    @Column(name = "sort_order", nullable = false)
    private Long sortOrder;

    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public static Issue of(
            long projectId,
            long issueNumber,
            String key,
            String title,
            String description,
            String type,
            String status,
            String priority,
            Long assigneeId,
            long reporterId) {
        return of(projectId, issueNumber, key, title, description, type, status, priority,
                assigneeId, reporterId, null, null, null, null, List.of(), 1L);
    }

    public static Issue of(
            long projectId,
            long issueNumber,
            String key,
            String title,
            String description,
            String type,
            String status,
            String priority,
            Long assigneeId,
            long reporterId,
            Long parentId,
            Long sprintId,
            LocalDate dueDate,
            BigDecimal estimateHours,
            List<String> labels,
            long sortOrder) {
        Issue issue = new Issue();
        issue.projectId = projectId;
        issue.issueNumber = issueNumber;
        issue.key = key;
        issue.title = title;
        issue.description = description;
        issue.type = type;
        issue.status = status;
        issue.priority = priority;
        issue.assigneeId = assigneeId;
        issue.reporterId = reporterId;
        issue.parentId = parentId;
        issue.sprintId = sprintId;
        issue.dueDate = dueDate;
        issue.estimateHours = estimateHours;
        issue.labels = new ArrayList<>(labels);
        issue.sortOrder = sortOrder;
        issue.version = 1;
        return issue;
    }

    /** 설정 변경 이관 — 상태만 바꾸고 버전은 올리지 않는다(사용자 편집이 아니다) */
    public void replaceComponents(List<Long> ids) {
        this.componentIds.clear();
        this.componentIds.addAll(ids);
    }

    public void archive(long actorId, Instant at) {
        this.archivedAt = at;
        this.archivedBy = actorId;
    }

    public void restore() {
        this.archivedAt = null;
        this.archivedBy = null;
    }

    public void moveToStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void edit(
            String title,
            String description,
            String type,
            String status,
            String priority,
            Long assigneeId,
            Long parentId,
            Long sprintId,
            LocalDate dueDate,
            BigDecimal estimateHours,
            IssueResolution resolution,
            Long fixVersionId,
            List<String> labels,
            long sortOrder) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (type != null) this.type = type;
        if (status != null) this.status = status;
        if (priority != null) this.priority = priority;
        this.assigneeId = assigneeId;
        this.parentId = parentId;
        this.sprintId = sprintId;
        this.dueDate = dueDate;
        this.estimateHours = estimateHours;
        applyResolution(resolution);
        this.fixVersionId = fixVersionId;
        this.labels.clear();
        this.labels.addAll(labels);
        this.sortOrder = sortOrder;
        this.version += 1;
    }

    /**
     * 해결 사유를 바꾸는 유일한 길 — 해결일이 사유와 따로 놀지 않게 한 곳에 묶어 둔다.
     *
     * <ul>
     *   <li>미해결 → 해결: 지금을 해결일로 적는다.</li>
     *   <li>해결 → 미해결: 해결일을 지운다. 다시 해결하면 그때 시각으로 새로 적힌다.</li>
     *   <li>해결 → 다른 사유: 해결일은 그대로다(처음 해결한 때가 해결일이다).</li>
     * </ul>
     */
    /** 해결 규칙({@code ResolutionRule})이 정한 값을 넣는다 — 해결일은 {@link #applyResolution}이 맞춘다 */
    public void changeResolution(IssueResolution next) {
        applyResolution(next);
    }

    /** 전환 화면·후처리가 담당자를 바꾼다(전환 실행 경로 — 버전은 {@link #bumpVersion}으로 따로 올린다) */
    public void assignTo(Long assigneeId) {
        this.assigneeId = assigneeId;
    }

    /** 전환 화면에서 마감일을 바꾼다 */
    public void changeDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    /**
     * 사용자가 편집 폼에서 보는 값(담당자·마감일·수정 버전)을 전환이 바꿨을 때 — 열려 있던 남의 폼이 그 값을 조용히
     * 되돌리지 않게 409를 받도록 버전을 올린다. 상태·순서·해결만 바뀐 이동은 올리지 않는다(드래그가 남의 저장을
     * 409로 만들지 않는다는 기존 규칙).
     */
    public void bumpVersion() {
        this.version += 1;
    }

    private void applyResolution(IssueResolution next) {
        if (next == null) {
            this.resolvedAt = null;
        } else if (this.resolution == null) {
            // 저장 정밀도(Postgres timestamptz = 마이크로초)에 맞춰 잘라 둔다 — 안 그러면 저장 전후로
            // 값이 달라져 "방금 읽은 값과 다시 읽은 값이 다르다"가 된다
            this.resolvedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        this.resolution = next;
    }

    /**
     * 시작일·스토리 포인트 — 생성·수정 경로가 부른다(그 경로가 이미 version을 올렸다). 시작일↔마감일 순서는
     * 서비스가 먼저 확인한다.
     */
    public void applyPlanning(LocalDate startDate, BigDecimal storyPoints) {
        this.startDate = startDate;
        this.storyPoints = storyPoints;
    }

    /**
     * 플래그 — 보드·우클릭에서 바로 켜고 끄는 값이라 version을 올리지 않는다. PUT 본문에 없는 값이므로
     * 오래된 편집 폼의 저장이 이 값을 되돌리지 못한다(보드 이동과 같은 이유).
     */
    public void flag(boolean flagged) {
        this.flagged = flagged;
        this.updatedAt = Instant.now();
    }

    public static final String TRIAGE_PENDING = "PENDING";
    public static final String TRIAGE_SNOOZED = "SNOOZED";
    public static final String TRIAGE_EXTERNAL = "EXTERNAL";
    public static final String TRIAGE_MANUAL = "MANUAL";

    public boolean inTriage() { return triageState != null; }

    /**
     * 트리아지 대기열로 — 스프린트에서 빼 백로그 순서 끝에 둔다(대기열은 계획 밖이다). 생성 직후(아직 저장 전)에도,
     * 기존 이슈를 명시적으로 보낼 때도 쓴다.
     */
    public void sendToTriage(String source, long backlogOrder) {
        this.triageState = TRIAGE_PENDING;
        this.triageSnoozedUntil = null;
        this.triageSource = source;
        this.sprintId = null;
        this.sortOrder = backlogOrder;
        if (this.version != null && this.id != null) this.version += 1;
    }

    /** 보류 — until이 지나면 다시 대기로 보인다 */
    public void snoozeTriage(Instant until) {
        this.triageState = TRIAGE_SNOOZED;
        this.triageSnoozedUntil = until;
        this.version += 1;
    }

    /** 지금 보면 대기인가 보류인가 — 보류 시각이 지났으면 대기다 */
    public String effectiveTriageState(Instant now) {
        if (TRIAGE_SNOOZED.equals(triageState) && (triageSnoozedUntil == null || !triageSnoozedUntil.isAfter(now))) {
            return TRIAGE_PENDING;
        }
        return triageState;
    }

    /** 수락 — 대기열에서 빠져 지정 상태로 백로그 끝에 들어간다. 담당자·우선순위는 null이면 그대로 */
    public void acceptFromTriage(String status, long backlogOrder, Long assigneeId, String priority) {
        clearTriage();
        this.status = status;
        if (assigneeId != null) this.assigneeId = assigneeId;
        if (priority != null) this.priority = priority;
        this.sprintId = null;
        this.sortOrder = backlogOrder;
        this.version += 1;
    }

    /**
     * 거절·중복 — 대기열에서 빠지면서 바로 끝난다: 완료 의미 상태 + 해결 사유. 해결은 {@link #applyResolution}
     * 한 길로만 바뀐다(해결일 규칙).
     */
    public void closeFromTriage(String completeStatus, IssueResolution resolution) {
        clearTriage();
        this.status = completeStatus;
        applyResolution(resolution);
        this.version += 1;
    }

    private void clearTriage() {
        this.triageState = null;
        this.triageSnoozedUntil = null;
        this.triageSource = null;
    }

    /**
     * 보드 이동 — 상태와 그룹 내 순서만 바꾼다. 정렬은 사용자가 편집 폼에서 보고 있던 값이
     * 아니므로 `version`을 올리지 않는다. 올리면 드래그 한 번이 남의 편집 저장을 409로 만든다.
     */
    public void moveTo(String status, long sortOrder) {
        this.status = status;
        this.sortOrder = sortOrder;
    }

    /** 백로그/스프린트 랭크 이동 — 스프린트 소속과 그룹 내 순서만 바꾼다. */
    public void rankTo(Long sprintId, long sortOrder) {
        this.sprintId = sprintId;
        this.sortOrder = sortOrder;
    }

    /** 같은 그룹의 다른 이슈들을 1..n으로 다시 매길 때 쓴다. */
    public void resequence(long sortOrder) {
        this.sortOrder = sortOrder;
    }

    /** 스프린트 완료 시 미완료 이슈를 백로그로 되돌린다. */
    public void moveToBacklog(long sortOrder) {
        this.sprintId = null;
        this.sortOrder = sortOrder;
    }

    /** 릴리스 시 미완료 이슈를 다음 버전으로 넘긴다 — 사용자가 편집 폼에서 보던 값이 아니라 version은 올리지 않는다. */
    public void assignFixVersion(Long fixVersionId) {
        this.fixVersionId = fixVersionId;
    }

    /** 스프린트 완료 시 미완료 이슈를 다음 스프린트로 넘긴다. */
    public void moveToSprint(long sprintId, long sortOrder) {
        this.sprintId = sprintId;
        this.sortOrder = sortOrder;
    }
}
