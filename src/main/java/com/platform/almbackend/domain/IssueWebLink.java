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

/** 이슈 외부 링크(PR·커밋·웹) — 에이전트 git 연결(P2a). 이슈↔이슈 링크({@link IssueLink})와 별개. */
@Entity
@Table(name = "issue_web_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueWebLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(nullable = false, length = 500, updatable = false)
    private String url;

    @Column(length = 200, updatable = false)
    private String title;

    @Column(nullable = false, length = 20, updatable = false)
    private String kind;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static IssueWebLink of(long issueId, String url, String title, String kind, long createdBy, Instant createdAt) {
        IssueWebLink link = new IssueWebLink();
        link.issueId = issueId;
        link.url = url;
        link.title = title;
        link.kind = kind;
        link.createdBy = createdBy;
        link.createdAt = createdAt;
        return link;
    }
}
