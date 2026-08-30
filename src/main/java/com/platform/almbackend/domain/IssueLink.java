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

/** blocks: source가 target을 차단(방향 있음) / relates: 양방향, 레코드 1개 */
@Entity
@Table(name = "issue_link")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false, updatable = false)
    private Long sourceId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(name = "link_type", nullable = false, length = 16, updatable = false)
    private String type;

    public static IssueLink of(long sourceId, long targetId, String type) {
        IssueLink link = new IssueLink();
        link.sourceId = sourceId;
        link.targetId = targetId;
        link.type = type;
        return link;
    }
}
