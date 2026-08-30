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

/** 대시보드 — 소유자가 가젯을 배치하고, 공유하면 모두가 읽는다. 가젯 배치는 JSON(프론트 계약) */
@Entity
@Table(name = "dashboard")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dashboard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean shared;

    @Column(name = "gadgets_json", nullable = false, columnDefinition = "text")
    private String gadgetsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Dashboard of(long ownerId, String name, boolean shared, String gadgetsJson, Instant at) {
        Dashboard dashboard = new Dashboard();
        dashboard.ownerId = ownerId;
        dashboard.name = name;
        dashboard.shared = shared;
        dashboard.gadgetsJson = gadgetsJson;
        dashboard.createdAt = at;
        dashboard.updatedAt = at;
        return dashboard;
    }

    public void rename(String name, Instant at) { this.name = name; this.updatedAt = at; }
    public void share(boolean shared, Instant at) { this.shared = shared; this.updatedAt = at; }
    public void replaceGadgets(String gadgetsJson, Instant at) { this.gadgetsJson = gadgetsJson; this.updatedAt = at; }
}
