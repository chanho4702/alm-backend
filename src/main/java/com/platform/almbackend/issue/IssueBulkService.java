package com.platform.almbackend.issue;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.issue.dto.IssueBulkUpdateRequest;
import com.platform.almbackend.issue.dto.IssueBulkUpdateResponse;
import com.platform.almbackend.issue.dto.IssueBulkUpdateResponse.Code;
import com.platform.almbackend.issue.dto.IssueDetailsRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.common.error.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이슈 일괄 편집(감사 A-11) — 예전 프론트는 이슈마다 GET 두 번 + PUT을 직렬로 보냈다(100건이면 300번).
 *
 * <p>행마다 자기 트랜잭션에서 단건 수정({@link IssueService#update})을 그대로 탄다 — 권한·보관 가드·전이 규칙·
 * 스프린트/버전 검증·변경 이력·알림·이벤트가 단건 PUT과 똑같다. 한 행의 실패가 다른 행을 되돌리지 않는다
 * (CSV 가져오기와 같은 이유로 이 메서드는 트랜잭션을 열지 않는다).
 *
 * <p>보낸 필드만 바꾼다. 나머지 필드는 행을 처리하는 순간 DB에서 읽은 값으로 채우고, 그 값의 버전(또는 요청이 준
 * expectedVersion)을 기대 버전으로 보낸다 — 읽은 뒤 잠그기 전에 누가 고쳤으면 그 행은 CONFLICT다(덮어쓰지 않는다).
 */
@Service
@RequiredArgsConstructor
public class IssueBulkService {
    private static final Pattern VERSION_CONFLICT = Pattern.compile("현재 (\\d+)");

    private final IssueRepository issues;
    private final IssueService issueService;
    private final ProjectService projectService;
    private final PlatformTransactionManager transactionManager;
    private final IssueFlagService flags;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IssueBulkUpdateResponse update(long userId, IssueBulkUpdateRequest request) {
        IssueBulkUpdateRequest.Changes changes = request.changes();
        if (changes.isEmpty()) throw new IllegalArgumentException("바꿀 내용이 없습니다");
        Set<Long> seen = new HashSet<>();
        for (IssueBulkUpdateRequest.Item item : request.items()) {
            if (!seen.add(item.issueId())) throw new IllegalArgumentException("같은 이슈가 두 번 들어 있습니다: " + item.issueId());
        }
        Map<Long, String> visibleKeys = visibleKeys(userId, seen);

        TransactionTemplate rowTransaction = new TransactionTemplate(transactionManager);
        rowTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<IssueBulkUpdateResponse.Row> rows = new ArrayList<>(request.items().size());
        int updated = 0;
        for (IssueBulkUpdateRequest.Item item : request.items()) {
            long issueId = item.issueId();
            String key = visibleKeys.get(issueId);
            try {
                IssueResponse saved = rowTransaction.execute(status -> applyRow(userId, item, changes));
                rows.add(new IssueBulkUpdateResponse.Row(issueId, saved.key(), true, Code.OK, null, null, saved));
                updated++;
            } catch (ConflictException e) {
                rows.add(failure(issueId, key, Code.CONFLICT, e.getMessage(), currentVersion(e.getMessage())));
            } catch (ForbiddenException e) {
                rows.add(failure(issueId, key, Code.FORBIDDEN, e.getMessage(), null));
            } catch (NotFoundException e) {
                rows.add(failure(issueId, key, Code.NOT_FOUND, e.getMessage(), null));
            } catch (IllegalArgumentException e) {
                rows.add(failure(issueId, key, Code.INVALID, e.getMessage(), null));
            } catch (ServiceUnavailableException e) {
                rows.add(failure(issueId, key, Code.UNAVAILABLE, e.getMessage(), null));
            } catch (RuntimeException e) {
                rows.add(failure(issueId, key, Code.ERROR, "저장하지 못했습니다", null));
            }
        }
        return new IssueBulkUpdateResponse(updated, rows.size() - updated, rows);
    }

    /** 한 행 — 행 트랜잭션 안에서 돈다 */
    private IssueResponse applyRow(long userId, IssueBulkUpdateRequest.Item item, IssueBulkUpdateRequest.Changes changes) {
        IssueResponse saved = changes.hasFieldChanges() ? applyFields(userId, item, changes) : null;
        // 플래그는 version과 무관한 값이다(보드 규칙) — 플래그만 바꾸는 행은 expectedVersion을 보지 않는다
        if (changes.has("flagged")) saved = flags.flag(userId, item.issueId(), changes.getFlagged(), null);
        return saved;
    }

    private IssueResponse applyFields(long userId, IssueBulkUpdateRequest.Item item, IssueBulkUpdateRequest.Changes changes) {
        Issue current = issues.findById(item.issueId())
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + item.issueId()));
        List<String> labels = new ArrayList<>(current.getLabels());
        if (changes.has("removeLabels") && changes.getRemoveLabels() != null) labels.removeAll(changes.getRemoveLabels());
        if (changes.has("addLabels") && changes.getAddLabels() != null) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(labels);
            merged.addAll(changes.getAddLabels());
            labels = new ArrayList<>(merged);
        }
        IssueDetailsRequest details = new IssueDetailsRequest(
                current.getParentId(),
                changes.has("sprintId") ? changes.getSprintId() : current.getSprintId(),
                current.getDueDate(),
                current.getEstimateHours(),
                current.getResolution(),
                changes.has("fixVersionId") ? changes.getFixVersionId() : current.getFixVersionId(),
                labels,
                null);
        IssueUpdateRequest update = new IssueUpdateRequest(
                current.getTitle(),
                current.getDescription(),
                current.getType(),
                changes.has("status") && changes.getStatus() != null ? changes.getStatus() : current.getStatus(),
                changes.has("priority") && changes.getPriority() != null ? changes.getPriority() : current.getPriority(),
                changes.has("assigneeId") ? changes.getAssigneeId() : current.getAssigneeId(),
                details,
                item.expectedVersion() != null ? item.expectedVersion() : current.getVersion(),
                null);
        return issueService.update(userId, item.issueId(), update);
    }

    /** 실패 행에 키를 붙이되, 볼 수 없는 프로젝트의 이슈는 키도 숨긴다 */
    private Map<Long, String> visibleKeys(long userId, Set<Long> ids) {
        Map<Long, String> keys = new HashMap<>();
        Map<Long, Boolean> visible = new HashMap<>();
        for (Issue issue : issues.findAllById(ids)) {
            boolean canSee;
            try {
                canSee = visible.computeIfAbsent(issue.getProjectId(), pid -> projectService.canView(userId, pid));
            } catch (ServiceUnavailableException e) {
                canSee = false;
            }
            if (canSee) keys.put(issue.getId(), issue.getKey());
        }
        return keys;
    }

    private static IssueBulkUpdateResponse.Row failure(long issueId, String key, Code code, String error, Integer currentVersion) {
        return new IssueBulkUpdateResponse.Row(issueId, key, false, code, error, currentVersion, null);
    }

    /** 충돌 문구("현재 N, 요청 M")에서 서버 버전을 꺼낸다 */
    private static Integer currentVersion(String message) {
        if (message == null) return null;
        Matcher m = VERSION_CONFLICT.matcher(message);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }
}
