package com.platform.almbackend.issue;

import com.platform.almbackend.collab.CollaborationService;
import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.event.AlmEvents;
import com.platform.almbackend.event.EventRelay;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 플래그(막힘, D2) — 보드·우클릭·팔레트에서 바로 켜고 끈다. 편집 폼의 version을 올리지 않는다
 * ({@link Issue#flag}): 보드에서 플래그를 켰다고 남이 열어 둔 편집 폼이 409가 되면 안 된다.
 * 이유를 주면 코멘트로 남긴다(지라 "플래그 추가 + 코멘트"와 같은 흐름).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueFlagService {
    private final IssueLocks locks;
    private final ProjectService projectService;
    private final IssueActivityService activityLog;
    private final CollaborationService collaboration;
    private final EventRelay events;
    private final IssueDomainEventPublisher domainEvents;

    public IssueResponse flag(long userId, long issueId, boolean flagged, String comment) {
        Issue snapshot = locks.requireIssue(issueId);
        projectService.require(userId, snapshot.getProjectId(), AlmAction.EDIT);
        Issue issue = locks.lockIssue(issueId);
        if (issue.isFlagged() == flagged) return IssueResponse.from(issue); // 같은 값 — 기록·이벤트 없음
        IssueDomainEventPublisher.Before before = IssueDomainEventPublisher.Before.of(issue);
        issue.flag(flagged);
        activityLog.record(issueId, userId, "flag", flagged ? "막힘" : "막힘 해제",
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (comment != null && !comment.isBlank()) {
            String prefix = flagged ? "플래그 추가: " : "플래그 해제: ";
            collaboration.addComment(userId, issueId, prefix + comment.trim(), List.of());
        }
        events.afterCommit(AlmEvents.issueUpdated(userId, issue));
        domainEvents.changed(userId, before, issue, null, null);
        return IssueResponse.from(issue);
    }
}
