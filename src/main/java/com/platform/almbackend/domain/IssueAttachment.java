package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 이슈 첨부 메타. 바이트는 오브젝트 스토리지에 storage_key(UUID)로 있고, 원본 파일명은 표시용이다
 * — 파일명을 경로에 쓰지 않아 경로 조작이 불가능하다.
 */
@Entity
@Table(name = "issue_attachment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "storage_bucket", length = 255)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false, unique = true, length = 64)
    private String storageKey;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static IssueAttachment of(
            Issue issue, String filename, String contentType, long sizeBytes,
            String storageBucket, String storageKey, String checksumSha256, long uploadedBy) {
        IssueAttachment a = new IssueAttachment();
        a.issueId = issue.getId();
        a.projectId = issue.getProjectId();
        a.filename = filename;
        a.contentType = contentType;
        a.sizeBytes = sizeBytes;
        a.storageBucket = storageBucket;
        a.storageKey = storageKey;
        a.checksumSha256 = checksumSha256;
        a.uploadedBy = uploadedBy;
        return a;
    }
}
