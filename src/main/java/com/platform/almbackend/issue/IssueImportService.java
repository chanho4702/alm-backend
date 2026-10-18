package com.platform.almbackend.issue;

import com.platform.almbackend.domain.Project;
import com.platform.almbackend.issue.dto.IssueImportRequest;
import com.platform.almbackend.issue.dto.IssueImportResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 이관·CSV 가져오기(2026-10-18 IssueService에서 분리 — 동작 불변) */
@Service
@RequiredArgsConstructor
public class IssueImportService {
    private final IssueRepository issues;
    private final ProjectRepository projects;
    private final ProjectService projectService;
    private final IssueService issueService;
    private final IssueLocks locks;
    private final PlatformTransactionManager transactionManager;

    /**
     * 이관·CSV 가져오기 — 항목마다 만들고 실패는 사유와 함께 분리한다(전부 롤백 없음). 키가 있으면
     * 보존하고 카운터를 그 번호 이상으로 앞당긴다.
     *
     * <p>행마다 자기 트랜잭션이다: 프로젝트 잠금 → 번호 할당 → 저장 → 커밋. 한 트랜잭션으로 돌리면 다른
     * {@code @Transactional} 빈(SchemeService 검증 등)이 던진 예외가 트랜잭션을 rollback-only로 만들어, 잡고
     * 넘어가도 마지막 커밋이 UnexpectedRollbackException으로 성공한 행까지 지운다. 그래서 이 메서드 자체는
     * 트랜잭션을 열지 않는다(NOT_SUPPORTED — 바깥 트랜잭션이 있어도 잠금을 쥔 채 행 트랜잭션을 기다리지 않게).
     * 권한은 처음에 한 번만 본다. 잠금은 행 트랜잭션 안에서만 잡으므로 같은 프로젝트의 동시 생성과 번호가 겹치지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IssueImportResponse importIssues(long userId, long projectId, IssueImportRequest request) {
        projectService.require(userId, projectId, AlmAction.EDIT);
        String projectKey = projects.findById(projectId)
                .orElseThrow(() -> new NotFoundException("프로젝트를 찾을 수 없습니다: " + projectId))
                .getKey();
        Pattern keyPattern = Pattern.compile("^" + Pattern.quote(projectKey) + "-(\\d+)$");
        TransactionTemplate rowTransaction = new TransactionTemplate(transactionManager);
        rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int created = 0;
        List<IssueImportResponse.Failure> failed = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            IssueImportRequest.Item item = request.items().get(i);
            String label = item.key() != null && !item.key().isBlank() ? item.key() : String.valueOf(item.title());
            try {
                rowTransaction.executeWithoutResult(status -> importRow(userId, projectId, keyPattern, item));
                created++;
            } catch (RuntimeException e) {
                failed.add(new IssueImportResponse.Failure(i + 1, label, e.getMessage()));
            }
        }
        return new IssueImportResponse(created, failed);
    }

    /** 가져오기 한 행 — 행 트랜잭션 안에서 돈다. 키 중복은 프로젝트 잠금 아래에서 DB로 확인한다(앞 행은 이미 커밋됐다) */
    private void importRow(long userId, long projectId, Pattern keyPattern, IssueImportRequest.Item item) {
        if (item.title() == null || item.title().isBlank()) {
            throw new IllegalArgumentException("이슈 제목을 입력하세요");
        }
        Project project = locks.lockProject(projectId);
        long number;
        if (item.key() != null && !item.key().isBlank()) {
            String key = item.key().trim().toUpperCase(Locale.ROOT);
            Matcher matcher = keyPattern.matcher(key);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("키는 " + project.getKey() + "-번호 형식이어야 합니다: " + item.key());
            }
            // 보관된 이슈도 키를 쥐고 있다 — @SQLRestriction 밖까지 본다
            if (issues.existsKeyIncludingArchived(key)) {
                throw new IllegalArgumentException("이미 있는 키입니다: " + key);
            }
            number = Long.parseLong(matcher.group(1));
            project.reserveIssueNumber(number);
        } else {
            number = project.nextIssueNumber();
        }
        issueService.createNumbered(userId, project, number, item.toCreate());
    }
}
