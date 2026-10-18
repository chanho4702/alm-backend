package com.platform.almbackend.issue;

import com.platform.almbackend.collab.IssueLinkService;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.LinkTypeDefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 이슈 복제(F-11, 지라 Clone issue) — 같은 프로젝트에 내용만 복사한 새 이슈를 만들고 원본과 "복제" 링크로 잇는다.
 *
 * <p>복사: 제목("복제: " 접두), 타입, 본문, 우선순위, 라벨, 컴포넌트, 상위 항목. 복사하지 않음: 댓글·워크로그·첨부·
 * 담당자·스프린트·마감일·예상 시간·수정 버전·해결, 상태(워크플로의 처음 상태로). 생성은 단건 생성 경로를 그대로 타므로
 * 번호·필수 필드·기본 담당자·이력·알림·이벤트가 같다. 링크까지 한 트랜잭션이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueCloneService {
    static final String PREFIX = "복제: ";
    /** 지라와 같은 링크 타입 — 복제본이 원본을 "복제함"(source=복제본, target=원본) */
    static final String CLONES = "clones";
    private static final int MAX_TITLE = 300;

    private final IssueRepository issues;
    private final IssueService issueService;
    private final IssueLinkService links;
    private final LinkTypeDefRepository linkTypes;
    private final ProjectService projectService;

    public IssueResponse cloneIssue(long userId, long issueId) {
        Issue original = issues.findById(issueId)
                .orElseThrow(() -> new com.platform.common.error.NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
        projectService.require(userId, original.getProjectId(), AlmAction.VIEW);
        String title = PREFIX + original.getTitle();
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE);
        IssueDetailsRequest details = new IssueDetailsRequest(
                original.getParentId(), null, null, null, null, null,
                List.copyOf(original.getLabels()), List.copyOf(original.getComponentIds()));
        IssueResponse copy = issueService.create(userId, original.getProjectId(), new IssueCreateRequest(
                title, original.getDescription(), original.getType(), null, original.getPriority(), null, details, null));
        // 관리자가 "복제" 링크 타입을 지웠으면 링크 없이 복제만 한다 — 복제 자체를 막을 이유는 아니다
        if (linkTypes.existsById(CLONES)) links.addLink(userId, copy.id(), original.getId(), CLONES);
        return copy;
    }
}
