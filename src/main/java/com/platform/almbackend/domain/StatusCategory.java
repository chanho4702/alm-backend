package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 전역 상태 카테고리 — kind(new/active/complete)가 완료 판정의 근거. 기본 3개는 의미 고정·삭제 불가 */
@Entity
@Table(name = "status_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StatusCategory {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, length = 16)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    public static StatusCategory of(String id, String name, String kind, String color, int sortOrder) {
        StatusCategory category = new StatusCategory();
        category.id = id;
        category.name = name;
        category.kind = kind;
        category.color = color;
        category.sortOrder = sortOrder;
        category.builtIn = false;
        return category;
    }

    public void rename(String name) { this.name = name; }
    public void recolor(String color) { this.color = color; }
    public void changeKind(String kind) { this.kind = kind; }
    public void reorder(int sortOrder) { this.sortOrder = sortOrder; }
}
