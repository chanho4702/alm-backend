package com.platform.almbackend.event.domain;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueResolution;
import com.platform.almbackend.settings.SettingsBody;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link IssueDomainEvent} 발행 창구 — 이슈 서비스들이 변경 트랜잭션 안에서 부른다. 리스너가 커밋 뒤에만 반응하려면
 * {@code @TransactionalEventListener(AFTER_COMMIT)}로 받는다({@link IssueDomainEvent} 설명 참고).
 */
@Component
@RequiredArgsConstructor
public class IssueDomainEventPublisher {
    private final ApplicationEventPublisher publisher;

    /** 변경 전 값 — 엔티티를 바꾸기 전에 뜬다. {@link IssueUpdatedEvent#changedFields()} 계산용 */
    public record Before(String title, String description, String type, String status, String priority,
                         Long assigneeId, Long parentId, Long sprintId, LocalDate dueDate, BigDecimal estimateHours,
                         IssueResolution resolution, Long fixVersionId, List<String> labels, List<Long> componentIds,
                         LocalDate startDate, BigDecimal storyPoints, boolean flagged, String triageState) {
        public static Before of(Issue issue) {
            return new Before(issue.getTitle(), issue.getDescription(), issue.getType(), issue.getStatus(),
                    issue.getPriority(), issue.getAssigneeId(), issue.getParentId(), issue.getSprintId(),
                    issue.getDueDate(), issue.getEstimateHours(), issue.getResolution(), issue.getFixVersionId(),
                    List.copyOf(issue.getLabels()), List.copyOf(issue.getComponentIds()),
                    issue.getStartDate(), issue.getStoryPoints(), issue.isFlagged(), issue.getTriageState());
        }
    }

    /**
     * {@link IssueUpdatedEvent#changedFields()}의 전체 어휘 — 자동화 "필드 변경" 트리거가 고를 수 있는 값이다.
     * 저장된 규칙이 이 이름을 들고 있으므로 이름을 바꾸지 말고 늘리기만 한다.
     */
    public static final List<String> CHANGED_FIELDS = List.of(
            "title", "description", "type", "status", "priority", "assignee", "parent", "sprint", "dueDate",
            "estimate", "resolution", "fixVersion", "labels", "components",
            // V30·V35(2026-10-24 웨이브 D)
            "startDate", "storyPoints", "flagged", "triage");

    public void created(long actorId, Issue issue) {
        publisher.publishEvent(new IssueCreatedEvent(issue.getId(), issue.getProjectId(), issue.getKey(),
                issue.getType(), issue.getStatus(), actorId, now(), version(issue)));
    }

    /** 삭제 또는 보관(archived) — 화면에서 사라진다. 자동화는 듣지 않는다 */
    public void deleted(long actorId, Issue issue, boolean archived) {
        publisher.publishEvent(new IssueDeletedEvent(issue.getId(), issue.getProjectId(), issue.getKey(), archived, actorId, now()));
    }

    /** 보관함에서 되돌림 — 화면에 다시 나타난다. 생성이 아니라 자동화 ISSUE_CREATED를 깨우지 않는다 */
    public void restored(long actorId, Issue issue) {
        publisher.publishEvent(new IssueRestoredEvent(issue.getId(), issue.getProjectId(), issue.getKey(), actorId, now(), version(issue)));
    }

    /**
     * 순서만 바뀐 이동(같은 열 안 재정렬·같은 스프린트 안 랭크) — 필드 변경이 아니라 {@link IssueUpdatedEvent}가 나가지 않는 경우에만 낸다.
     * 같은 랭크 그룹의 다른 이슈 sortOrder도 함께 바뀌었으므로 받는 쪽은 그룹(sprintId) 순서를 다시 읽는다.
     */
    public void rankedIfUnchanged(long actorId, Before before, Issue after) {
        if (!changedFields(before, after).isEmpty()) return;
        publisher.publishEvent(new IssueRankedEvent(after.getId(), after.getProjectId(), after.getKey(),
                after.getSprintId(), actorId, now(), version(after)));
    }

    /**
     * 변경 — 바뀐 필드가 있으면 {@link IssueUpdatedEvent}, 상태가 바뀌었으면 {@link IssueTransitionedEvent}를 먼저 낸다.
     *
     * @param body         상태 의미(kind)를 읽을 프로젝트 설정. 상태가 바뀌지 않는 경로는 null이어도 된다
     * @param transitionId 쓰인 전환 id(전환 목록이 빈 워크플로면 null)
     */
    public void changed(long actorId, Before before, Issue after, SettingsBody body, String transitionId) {
        Instant at = now();
        if (!Objects.equals(before.status(), after.getStatus())) {
            publisher.publishEvent(new IssueTransitionedEvent(after.getId(), after.getProjectId(), after.getKey(),
                    before.status(), after.getStatus(), kind(body, before.status()), kind(body, after.getStatus()),
                    transitionId, actorId, at));
        }
        Set<String> changed = changedFields(before, after);
        if (!changed.isEmpty()) {
            publisher.publishEvent(new IssueUpdatedEvent(after.getId(), after.getProjectId(), after.getKey(),
                    changed, actorId, at, version(after)));
        }
    }

    static Set<String> changedFields(Before b, Issue a) {
        Set<String> out = new LinkedHashSet<>();
        if (!Objects.equals(b.title(), a.getTitle())) out.add("title");
        if (!Objects.equals(b.description(), a.getDescription())) out.add("description");
        if (!Objects.equals(b.type(), a.getType())) out.add("type");
        if (!Objects.equals(b.status(), a.getStatus())) out.add("status");
        if (!Objects.equals(b.priority(), a.getPriority())) out.add("priority");
        if (!Objects.equals(b.assigneeId(), a.getAssigneeId())) out.add("assignee");
        if (!Objects.equals(b.parentId(), a.getParentId())) out.add("parent");
        if (!Objects.equals(b.sprintId(), a.getSprintId())) out.add("sprint");
        if (!Objects.equals(b.dueDate(), a.getDueDate())) out.add("dueDate");
        if (b.estimateHours() == null ? a.getEstimateHours() != null
                : a.getEstimateHours() == null || b.estimateHours().compareTo(a.getEstimateHours()) != 0) out.add("estimate");
        if (!Objects.equals(b.resolution(), a.getResolution())) out.add("resolution");
        if (!Objects.equals(b.fixVersionId(), a.getFixVersionId())) out.add("fixVersion");
        if (!Objects.equals(b.labels(), List.copyOf(a.getLabels()))) out.add("labels");
        if (!Objects.equals(b.componentIds(), List.copyOf(a.getComponentIds()))) out.add("components");
        if (!Objects.equals(b.startDate(), a.getStartDate())) out.add("startDate");
        if (b.storyPoints() == null ? a.getStoryPoints() != null
                : a.getStoryPoints() == null || b.storyPoints().compareTo(a.getStoryPoints()) != 0) out.add("storyPoints");
        if (b.flagged() != a.isFlagged()) out.add("flagged");
        if (!Objects.equals(b.triageState(), a.getTriageState())) out.add("triage");
        return out;
    }

    private static String kind(SettingsBody body, String statusId) {
        if (body == null) return "done".equals(statusId) ? "complete" : "new";
        return body.statuses().stream().filter(s -> s.id().equals(statusId)).map(SettingsBody.WorkflowStatus::kind)
                .filter(Objects::nonNull).findFirst()
                .orElse("done".equals(statusId) ? "complete" : "new");
    }

    private static long version(Issue issue) {
        return issue.getVersion() == null ? 0 : issue.getVersion();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
