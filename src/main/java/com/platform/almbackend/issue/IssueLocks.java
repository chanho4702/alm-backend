package com.platform.almbackend.issue;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.common.error.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이슈 쓰기 경로의 행 조회·잠금 — 생성·수정·삭제·이동·랭크·가져오기가 같은 규칙으로 잠근다.
 * 호출자의 트랜잭션 안에서만 쓴다(여기는 트랜잭션을 열지 않는다).
 */
@Component
@RequiredArgsConstructor
class IssueLocks {
    private final IssueRepository issues;
    private final ProjectRepository projects;
    private final EntityManager entityManager;

    Issue requireIssue(long issueId) {
        return issues.findById(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
    }

    /**
     * 프로젝트 행 잠금(이슈 번호 카운터·그룹 재정렬의 직렬화 지점). 권한 확인이 이미 같은 영속성 컨텍스트에
     * 프로젝트를 올려 두므로 {@code select ... for update}만으로는 낡은 카운터를 그대로 돌려받는다 —
     * 잠근 뒤 DB 값으로 다시 읽는다({@link #refreshForUpdate}).
     */
    Project lockProject(long projectId) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId));
        return refreshForUpdate(project, "프로젝트를 찾을 수 없습니다: " + projectId);
    }

    /** 이슈 행 잠금 — 버전 비교는 반드시 잠근 뒤 다시 읽은 값으로 한다 */
    Issue lockIssue(long issueId) {
        return refreshForUpdate(requireIssue(issueId), "이슈를 찾을 수 없습니다: " + issueId);
    }

    /**
     * 행을 PESSIMISTIC_WRITE로 잠그면서 DB의 현재 값으로 다시 읽는다. 이미 관리 중인 엔티티는 잠금 조회
     * ({@code findByIdForUpdate})가 잠금만 잡고 상태는 1차 캐시 것을 돌려준다 — 그 사이 다른 트랜잭션이
     * 커밋한 version·카운터를 못 보고 expectedVersion 검사를 통과시키거나(남의 저장 덮어쓰기) 번호를 겹치게 한다.
     */
    private <T> T refreshForUpdate(T entity, String notFoundMessage) {
        try {
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException e) {
            throw new NotFoundException(notFoundMessage);
        }
        return entity;
    }
}
