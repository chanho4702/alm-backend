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

    /**
     * 프론트 아이콘 맵의 lucide 키. 빈 문자열이면 화면이 카테고리 의미(new/active/complete)의
     * 기본 아이콘으로 폴백한다 — 상태를 색만으로 구분하지 않기 위한 값이다.
     */
    @Column(nullable = false, length = 40)
    private String icon;

    public static StatusDef of(String id, String name, String categoryId, String description) {
        return of(id, name, categoryId, description, "");
    }

    public static StatusDef of(String id, String name, String categoryId, String description, String icon) {
        StatusDef def = new StatusDef();
        def.id = id;
        def.name = name;
        def.categoryId = categoryId;
        def.description = description == null ? "" : description;
        def.icon = icon == null ? "" : icon.trim();
        return def;
    }

    public void rename(String name) { this.name = name; }
    public void moveTo(String categoryId) { this.categoryId = categoryId; }
    public void describe(String description) { this.description = description == null ? "" : description; }
    /** 빈 문자열은 "아이콘 없음"이라는 뜻이라 유효한 값이다 — 카테고리 기본으로 폴백한다 */
    public void reicon(String icon) { this.icon = icon == null ? "" : icon.trim(); }
}
