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

/**
 * 저장된 보기(V31, D1) — 필터 + 표시 옵션을 이름 붙여 둔 것. 공유 범위는 {@link #SCOPE_PERSONAL}(만든 사람만)·
 * {@link #SCOPE_PROJECT}(그 프로젝트 열람자 모두). 결과는 저장하지 않는다 — 여는 사람이 자기 권한으로 다시 실행한다.
 *
 * <p>버전은 수동 {@code expectedVersion} 비교다(이슈·프로젝트와 같은 방식). 프로젝트는 만든 뒤 바꾸지 않는다.
 */
@Entity
@Table(name = "saved_view")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedView {

    public static final String SCOPE_PERSONAL = "personal";
    public static final String SCOPE_PROJECT = "project";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "project_id", updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 12)
    private String scope;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 8)
    private String layout;

    @Column(name = "filter_kind", length = 8)
    private String filterKind;

    @Column(name = "filter_query", length = 4000)
    private String filterQuery;

    @Column(name = "display_json", nullable = false, columnDefinition = "text")
    private String displayJson;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SavedView of(long ownerId, Long projectId, String scope, String name, String layout,
                               String filterKind, String filterQuery, String displayJson, Instant now) {
        SavedView view = new SavedView();
        view.ownerId = ownerId;
        view.projectId = projectId;
        view.scope = scope;
        view.name = name;
        view.layout = layout;
        view.filterKind = filterKind;
        view.filterQuery = filterQuery;
        view.displayJson = displayJson;
        view.version = 1;
        view.createdAt = now;
        view.updatedAt = now;
        return view;
    }

    public boolean isProjectScoped() {
        return SCOPE_PROJECT.equals(scope);
    }

    /** 값 검증은 서비스가 끝냈다. 필터는 둘 다 null이면 "필터 없음"이다 */
    public void edit(String scope, String name, String layout, String filterKind, String filterQuery,
                     String displayJson, Instant now) {
        this.scope = scope;
        this.name = name;
        this.layout = layout;
        this.filterKind = filterKind;
        this.filterQuery = filterQuery;
        this.displayJson = displayJson;
        this.version += 1;
        this.updatedAt = now;
    }
}
