package com.platform.almbackend.attachment;

import com.platform.almbackend.attachment.dto.AttachmentResponse;
import com.platform.almbackend.common.NotFoundException;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueAttachment;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueAttachmentRepository;
import com.platform.almbackend.repository.IssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 이슈 첨부. 권한은 이슈가 속한 프로젝트 기준(올리기·지우기 EDIT, 보기·내려받기 VIEW).
 * 오브젝트는 메타 트랜잭션이 확정된 뒤에 지운다 — 롤백된 삭제가 바이트만 날리지 않게.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AttachmentService {
    private final IssueAttachmentRepository attachments;
    private final IssueRepository issues;
    private final ProjectService projectService;
    private final AttachmentStorage storage;

    public record DownloadItem(IssueAttachment meta, Resource resource) {}

    public AttachmentResponse upload(long userId, long issueId, MultipartFile file) {
        Issue issue = requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.EDIT);
        if (file.isEmpty()) throw new IllegalArgumentException("빈 파일은 올릴 수 없습니다");
        try {
            String contentType;
            try (InputStream probe = file.getInputStream()) {
                contentType = AttachmentMediaTypes.detect(probe);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            AttachmentStorage.StoredObject stored;
            try (InputStream raw = file.getInputStream();
                 DigestInputStream input = new DigestInputStream(raw, digest)) {
                stored = storage.store(input, file.getSize(), contentType);
            }
            // 메타 저장이 롤백되면 바이트만 남는다 — 롤백 시 오브젝트를 되돌린다
            deleteAfterRollback(stored.bucket(), stored.key());
            String filename = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                    ? "unnamed" : file.getOriginalFilename();
            IssueAttachment saved = attachments.save(IssueAttachment.of(issue, filename, contentType,
                    file.getSize(), stored.bucket(), stored.key(),
                    HexFormat.of().formatHex(digest.digest()), userId));
            return AttachmentResponse.from(saved);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(long userId, long issueId) {
        Issue issue = requireIssue(issueId);
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        return attachments.findByIssueIdOrderByCreatedAtAscIdAsc(issueId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DownloadItem download(long userId, long attachmentId) {
        IssueAttachment a = requireAttachment(attachmentId);
        projectService.require(userId, a.getProjectId(), AlmAction.VIEW);
        return new DownloadItem(a, storage.open(a.getStorageBucket(), a.getStorageKey()));
    }

    @Transactional(readOnly = true)
    public DownloadItem inline(long userId, long attachmentId) {
        DownloadItem item = download(userId, attachmentId);
        if (!AttachmentMediaTypes.isSafeInline(item.meta().getContentType())) {
            throw new IllegalArgumentException("인라인 표시를 허용하지 않는 첨부 형식입니다");
        }
        return item;
    }

    public void delete(long userId, long attachmentId) {
        IssueAttachment a = requireAttachment(attachmentId);
        projectService.require(userId, a.getProjectId(), AlmAction.EDIT);
        attachments.delete(a);
        deleteAfterCommit(a.getStorageBucket(), a.getStorageKey());
    }

    /** 이슈 삭제 연쇄 — 호출자(IssueService)가 이미 권한을 확인했다. */
    public void deleteAllForIssue(long issueId) {
        for (IssueAttachment a : attachments.findByIssueIdOrderByCreatedAtAscIdAsc(issueId)) {
            attachments.delete(a);
            deleteAfterCommit(a.getStorageBucket(), a.getStorageKey());
        }
    }

    /** 프로젝트 삭제 연쇄 — 이슈 벌크 삭제 전에 메타를 지워야 FK가 남지 않는다. */
    public void deleteAllForProject(long projectId) {
        List<IssueAttachment> rows = attachments.findByProjectId(projectId);
        attachments.deleteAllInBatch(rows);
        for (IssueAttachment a : rows) deleteAfterCommit(a.getStorageBucket(), a.getStorageKey());
    }

    private Issue requireIssue(long issueId) {
        return issues.findById(issueId)
                .orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다: " + issueId));
    }

    private IssueAttachment requireAttachment(long attachmentId) {
        return attachments.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("첨부를 찾을 수 없습니다: " + attachmentId));
    }

    private void deleteAfterCommit(String bucket, String key) {
        runAfter(true, bucket, key);
    }

    private void deleteAfterRollback(String bucket, String key) {
        runAfter(false, bucket, key);
    }

    private void runAfter(boolean onCommit, String bucket, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (onCommit) removeQuietly(bucket, key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                boolean committed = status == STATUS_COMMITTED;
                if (committed == onCommit) removeQuietly(bucket, key);
            }
        });
    }

    private void removeQuietly(String bucket, String key) {
        if (!storage.delete(bucket, key)) {
            // 고아 오브젝트는 나중에 정리 작업이 거둘 수 있게 키를 남긴다
            log.warn("첨부 오브젝트 삭제 실패 bucket={} key={}", bucket, key);
        }
    }
}
