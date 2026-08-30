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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "worklog")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Worklog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issue_id", nullable = false, updatable = false)
    private Long issueId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private Long authorId;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal hours;

    @Column(nullable = false, length = 500)
    private String comment;

    @Column(name = "worked_on", nullable = false)
    private LocalDate workedOn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Worklog of(long issueId, long authorId, BigDecimal hours, String comment,
                             LocalDate workedOn, Instant createdAt) {
        Worklog worklog = new Worklog();
        worklog.issueId = issueId;
        worklog.authorId = authorId;
        worklog.hours = hours;
        worklog.comment = comment == null ? "" : comment;
        worklog.workedOn = workedOn;
        worklog.createdAt = createdAt;
        return worklog;
    }
}
