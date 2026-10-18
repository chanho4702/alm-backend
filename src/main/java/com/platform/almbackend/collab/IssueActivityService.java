package com.platform.almbackend.collab;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueActivity;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueActivityRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.settings.SchemeService;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 이슈 활동 기록 — 추가만 한다. 문구의 사용자는 id로 남긴다(디렉터리 연동 전). 프론트 목업의 recordChanges와
 * 같은 종류(type) 어휘를 쓴다. (2026-10-18 CollaborationService에서 분리 — 동작 불변)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueActivityService {
    private final IssueRepository issues;
    private final IssueActivityRepository activities;
    private final ProjectService projectService;
    private final SchemeService settings;

    public record ActivityResponse(long id, long issueId, long actorId, String type, String detail, Instant occurredAt) {
        static ActivityResponse from(IssueActivity a) {
            return new ActivityResponse(a.getId(), a.getIssueId(), a.getActorId(), a.getType(), a.getDetail(), a.getOccurredAt());
        }
    }

    @Transactional(readOnly = true)
    public List<ActivityResponse> activity(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        return activities.findByIssueIdOrderByOccurredAtAscIdAsc(issueId).stream().map(ActivityResponse::from).toList();
    }

    public void record(long issueId, long actorId, String type, String detail, Instant at) {
        activities.save(IssueActivity.of(issueId, actorId, type, detail, at));
    }

    /** 이슈 생성 활동 */
    public void recordCreated(long actorId, Issue issue) {
        record(issue.getId(), actorId, "created", issue.getKey(), now());
    }

    /**
     * 이슈 수정 전후를 비교해 바뀐 필드마다 한 줄 — 상태·타입은 이름으로, 사용자는 id로 남긴다.
     * 프론트 목업의 recordChanges와 같은 종류(type) 어휘를 쓴다.
     */
    public void recordUpdate(long actorId, Snapshot before, Issue after) {
        Instant now = now();
        long issueId = after.getId();
        if (!Objects.equals(before.status(), after.getStatus())) {
            record(issueId, actorId, "status", statusName(after.getProjectId(), before.status()) + " → " + statusName(after.getProjectId(), after.getStatus()), now);
        }
        if (!Objects.equals(before.assigneeId(), after.getAssigneeId())) {
            record(issueId, actorId, "assignee", userLabel(before.assigneeId()) + " → " + userLabel(after.getAssigneeId()), now);
        }
        if (!Objects.equals(before.priority(), after.getPriority())) {
            record(issueId, actorId, "priority", settings.priorityName(before.priority()) + " → " + settings.priorityName(after.getPriority()), now);
        }
        if (!Objects.equals(before.sprintId(), after.getSprintId())) {
            record(issueId, actorId, "sprint", idLabel(before.sprintId(), "백로그") + " → " + idLabel(after.getSprintId(), "백로그"), now);
        }
        if (!Objects.equals(before.dueDate(), after.getDueDate())) {
            record(issueId, actorId, "duedate", (before.dueDate() == null ? "미지정" : before.dueDate().toString()) + " → " + (after.getDueDate() == null ? "미지정" : after.getDueDate().toString()), now);
        }
        if (!Objects.equals(before.labels(), after.getLabels())) {
            record(issueId, actorId, "labels", after.getLabels().isEmpty() ? "라벨 없음" : String.join(", ", after.getLabels()), now);
        }
        if (!Objects.equals(before.type(), after.getType())) {
            record(issueId, actorId, "issuetype", before.type() + " → " + after.getType(), now);
        }
        if (!Objects.equals(before.parentId(), after.getParentId())) {
            record(issueId, actorId, "parent", idLabel(before.parentId(), "없음") + " → " + idLabel(after.getParentId(), "없음"), now);
        }
        String beforeResolution = before.resolution();
        String afterResolution = after.getResolution() == null ? null : after.getResolution().name();
        if (!Objects.equals(beforeResolution, afterResolution)) {
            record(issueId, actorId, "resolution", (beforeResolution == null ? "미해결" : beforeResolution) + " → " + (afterResolution == null ? "미해결" : afterResolution), now);
        }
        if (!Objects.equals(before.fixVersionId(), after.getFixVersionId())) {
            record(issueId, actorId, "fixversion", idLabel(before.fixVersionId(), "없음") + " → " + idLabel(after.getFixVersionId(), "없음"), now);
        }
    }

    /** 수정 전 값 — 엔티티를 바꾸기 전에 뜬다 */
    public record Snapshot(String status, Long assigneeId, String priority, Long sprintId, LocalDate dueDate,
                           List<String> labels, String type, Long parentId, String resolution, Long fixVersionId) {
        public static Snapshot of(Issue issue) {
            return new Snapshot(issue.getStatus(), issue.getAssigneeId(),
                    issue.getPriority(), issue.getSprintId(),
                    issue.getDueDate(), List.copyOf(issue.getLabels()), issue.getType(), issue.getParentId(),
                    issue.getResolution() == null ? null : issue.getResolution().name(), issue.getFixVersionId());
        }
    }

    private String statusName(long projectId, String statusId) {
        return settings.body(projectId).statuses().stream()
                .filter(s -> s.id().equals(statusId)).map(s -> s.name()).findFirst().orElse(statusId);
    }

    private static String userLabel(Long userId) {
        return userId == null ? "미지정" : "사용자 " + userId;
    }

    private static String idLabel(Long id, String empty) {
        return id == null ? empty : "#" + id;
    }

    private Issue require(long userId, long issueId, AlmAction action) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        projectService.require(userId, issue.getProjectId(), action);
        return issue;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
