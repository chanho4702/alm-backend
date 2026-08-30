package com.platform.almbackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 이슈 링크 타입 레지스트리 — outward/inward가 같으면 대칭(양방향) 링크. 기본 5종은 삭제 불가 */
@Entity
@Table(name = "link_type_def")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkTypeDef {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 80)
    private String outward;

    @Column(nullable = false, length = 80)
    private String inward;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "built_in", nullable = false)
    private boolean builtIn;

    public static LinkTypeDef of(String id, String name, String outward, String inward, int sortOrder) {
        LinkTypeDef def = new LinkTypeDef();
        def.id = id;
        def.name = name;
        def.outward = outward;
        def.inward = inward;
        def.sortOrder = sortOrder;
        def.builtIn = false;
        return def;
    }

    public boolean isSymmetric() { return outward.equals(inward); }
    public void markBuiltIn() { this.builtIn = true; }
    public void rename(String name) { this.name = name; }
    public void relabel(String outward, String inward) {
        if (outward != null) this.outward = outward;
        if (inward != null) this.inward = inward;
    }
    public void reorder(int sortOrder) { this.sortOrder = sortOrder; }
}
