package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 이슈 한 건의 필드 변경 한 줄. 추가만 하고 수정·삭제하지 않는다(이슈가 지워질 때만 cascade).
 * 값은 문자열로 남긴다 — 상태 id는 워크플로 스킴 소유라 서버가 의미를 모르고, 스프린트는 id 문자열이다.
 */
@Entity
@Table(name = "issue_change_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueChangeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "sprint_id")
    private Long sprintId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ChangeField field;

    @Column(name = "from_value", length = 80)
    private String fromValue;

    @Column(name = "to_value", length = 80)
    private String toValue;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private Long actorId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    public static IssueChangeLog of(
            Issue issue, Long sprintId, ChangeField field, String fromValue, String toValue,
            long actorId, Instant changedAt) {
        IssueChangeLog log = new IssueChangeLog();
        log.issueId = issue.getId();
        log.projectId = issue.getProjectId();
        log.sprintId = sprintId;
        log.field = field;
        log.fromValue = fromValue;
        log.toValue = toValue;
        log.actorId = actorId;
        log.changedAt = changedAt;
        return log;
    }
}
