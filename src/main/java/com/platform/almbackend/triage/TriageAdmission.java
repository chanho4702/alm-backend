package com.platform.almbackend.triage;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이슈 생성의 권한 관문 — 트리아지를 켠 프로젝트는 편집 권한이 없는 <b>열람자</b>도 이슈를 올릴 수 있고,
 * 그 이슈는 대기열(EXTERNAL)로 간다. 트리아지가 꺼져 있으면 예전과 똑같이 EDIT가 필요하다(같은 403 문구).
 *
 * <p>org 불능(503)은 그대로 올린다 — "권한 없음"으로 바꿔 대기열로 보내지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TriageAdmission {

    private final ProjectService projects;

    /**
     * @return 대기열로 보낼 길({@link Issue#TRIAGE_EXTERNAL}/{@link Issue#TRIAGE_MANUAL}), 일반 생성이면 null
     */
    public String admit(long userId, long projectId, Boolean requested) {
        // 예외로 갈라지지 않는다 — 트랜잭션 안에서 거부 예외가 프록시를 지나면 바깥 트랜잭션이 rollback-only가 된다
        if (!projects.hasGrant(userId, projectId, AlmAction.EDIT)) {
            Project project = projects.requireProject(projectId);
            if (project.isTriageEnabled() && !project.isArchived() && projects.hasGrant(userId, projectId, AlmAction.VIEW)) {
                return Issue.TRIAGE_EXTERNAL;
            }
            // 트리아지가 꺼져 있거나 열람도 못 하면 예전과 같은 거부(같은 문구·계정 상태 문구)
            projects.require(userId, projectId, AlmAction.EDIT);
        }
        projects.require(userId, projectId, AlmAction.EDIT); // 보관·없는 프로젝트 가드
        if (!Boolean.TRUE.equals(requested)) return null;
        if (!projects.requireProject(projectId).isTriageEnabled()) {
            throw new IllegalArgumentException("트리아지를 켜지 않은 프로젝트입니다");
        }
        return Issue.TRIAGE_MANUAL;
    }
}
