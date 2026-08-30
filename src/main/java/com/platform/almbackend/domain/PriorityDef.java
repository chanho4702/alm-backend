package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 전역 우선순위 레지스트리 — sort_order가 높음→낮음 순서(정렬·리포트의 근거). 기본 5종은 삭제 불가 */
@Entity
@Table(name = "priority_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PriorityDef {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 40)
    private String icon;

    @Column(nullable = false, length = 16)
    private String color;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    public static PriorityDef of(String id, String name, String icon, String color, String description, int sortOrder) {
        PriorityDef def = new PriorityDef();
        def.id = id;
        def.name = name;
        def.icon = icon;
        def.color = color;
        def.description = description == null ? "" : description;
        def.sortOrder = sortOrder;
        def.builtIn = false;
        return def;
    }

    public void markBuiltIn() { this.builtIn = true; }
    public void rename(String name) { this.name = name; }
    public void restyle(String icon, String color) {
        if (icon != null) this.icon = icon;
        if (color != null) this.color = color;
    }
    public void describe(String description) { this.description = description == null ? "" : description; }
    public void reorder(int sortOrder) { this.sortOrder = sortOrder; }
}
