package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 전역 상태 레지스트리 — 워크플로가 골라 쓴다. 이름·카테고리의 진실 */
@Entity
@Table(name = "status_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatusDef {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "category_id", nullable = false, length = 40)
    private String categoryId;

    @Column(nullable = false, length = 300)
    private String description;

    public static StatusDef of(String id, String name, String categoryId, String description) {
        StatusDef def = new StatusDef();
        def.id = id;
        def.name = name;
        def.categoryId = categoryId;
        def.description = description == null ? "" : description;
        return def;
    }

    public void rename(String name) { this.name = name; }
    public void moveTo(String categoryId) { this.categoryId = categoryId; }
    public void describe(String description) { this.description = description == null ? "" : description; }
}
