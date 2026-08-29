package com.platform.almbackend.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;

/** 버전(릴리스). 이름은 프로젝트 안에서 유일하고, 상태 전이는 엔티티가 지킨다. */
@Entity
@Table(name = "project_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VersionStatus status;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(nullable = false)
    private Integer version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static ProjectVersion of(
            long projectId, String name, String description, LocalDate startDate, LocalDate releaseDate) {
        assertDates(startDate, releaseDate);
        ProjectVersion v = new ProjectVersion();
        v.projectId = projectId;
        v.name = name;
        v.description = description;
        v.startDate = startDate;
        v.releaseDate = releaseDate;
        v.status = VersionStatus.UNRELEASED;
        v.version = 1;
        return v;
    }

    public void edit(String name, String description, LocalDate startDate, LocalDate releaseDate) {
        assertDates(startDate, releaseDate);
        this.name = name;
        this.description = description;
        this.startDate = startDate;
        this.releaseDate = releaseDate;
        this.version += 1;
    }

    public void release(Instant now) {
        if (status == VersionStatus.RELEASED) {
            throw new IllegalStateException("이미 릴리스된 버전입니다");
        }
        if (status == VersionStatus.ARCHIVED) {
            throw new IllegalStateException("보관된 버전은 릴리스할 수 없습니다");
        }
        status = VersionStatus.RELEASED;
        releasedAt = now;
        version += 1;
    }

    public void archive() {
        if (status == VersionStatus.ARCHIVED) {
            throw new IllegalStateException("이미 보관된 버전입니다");
        }
        status = VersionStatus.ARCHIVED;
        version += 1;
    }

    /** DB 체크 제약과 같은 규칙을 먼저 판정해 한국어 사유를 준다. */
    private static void assertDates(LocalDate startDate, LocalDate releaseDate) {
        if (startDate != null && releaseDate != null && startDate.isAfter(releaseDate)) {
            throw new IllegalArgumentException("시작일은 릴리스일보다 늦을 수 없습니다");
        }
    }
}
