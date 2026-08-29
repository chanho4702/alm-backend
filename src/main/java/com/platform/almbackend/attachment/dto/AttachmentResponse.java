package com.platform.almbackend.attachment.dto;

import com.platform.almbackend.domain.IssueAttachment;

import java.time.Instant;

public record AttachmentResponse(
        long id,
        long issueId,
        String filename,
        String contentType,
        long sizeBytes,
        long uploadedBy,
        Instant createdAt
) {
    public static AttachmentResponse from(IssueAttachment a) {
        return new AttachmentResponse(a.getId(), a.getIssueId(), a.getFilename(), a.getContentType(),
                a.getSizeBytes(), a.getUploadedBy(), a.getCreatedAt());
    }
}
