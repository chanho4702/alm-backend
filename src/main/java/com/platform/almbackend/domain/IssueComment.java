package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "issue_comment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public static IssueComment of(long issueId, long authorId, String body, Instant createdAt) {
        IssueComment comment = new IssueComment();
        comment.issueId = issueId;
        comment.authorId = authorId;
        comment.body = body;
        comment.createdAt = createdAt;
        return comment;
    }

    public void edit(String body, Instant at) {
        this.body = body;
        this.updatedAt = at;
    }
}
