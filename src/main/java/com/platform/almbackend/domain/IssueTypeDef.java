package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 전역 이슈 타입 레지스트리 — level(epic/standard/subtask)이 부모-자식 규칙의 근거. 기본 5종은 계층 고정·삭제 불가 */
@Entity
@Table(name = "issue_type_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IssueTypeDef {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 40)
    private String icon;

    @Column(nullable = false, length = 16)
    private String color;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    public static IssueTypeDef of(String id, String name, String icon, String color, String level,
                                  String description, int sortOrder) {
        IssueTypeDef def = new IssueTypeDef();
        def.id = id;
        def.name = name;
        def.icon = icon;
        def.color = color;
        def.level = level;
        def.description = description == null ? "" : description;
        def.sortOrder = sortOrder;
        def.builtIn = false;
        return def;
    }

    public void rename(String name) { this.name = name; }
    public void restyle(String icon, String color) {
        if (icon != null) this.icon = icon;
        if (color != null) this.color = color;
    }
    public void changeLevel(String level) { this.level = level; }
    public void describe(String description) { this.description = description == null ? "" : description; }
    public void reorder(int sortOrder) { this.sortOrder = sortOrder; }
}
