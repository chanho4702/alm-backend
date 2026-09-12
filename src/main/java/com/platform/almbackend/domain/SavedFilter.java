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
 * 저장 필터 — 사이드바에 꽂아 두는 내 검색 하나. 스마트 검색 문자열({@code smart})이거나
 * AQL({@code aql})이며, 무엇을 저장했는지는 {@code kind}가 말한다.
 *
 * <p>소유자 본인만 보고 고친다. 공유 필터는 없다 — 남의 것을 지목하면 404다(있다는 사실도 알리지 않는다).
 */
@Entity
@Table(name = "saved_filter")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedFilter {

    /** 스마트 검색 문자열 */
    public static final String KIND_SMART = "smart";
    /** AQL 문자열 — 저장할 때 문법을 검사한다 */
    public static final String KIND_AQL = "aql";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 8)
    private String kind;

    @Column(name = "query", nullable = false, length = 4000)
    private String query;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static SavedFilter of(long ownerId, String name, String kind, String query, Instant now) {
        SavedFilter filter = new SavedFilter();
        filter.ownerId = ownerId;
        filter.name = name;
        filter.kind = kind;
        filter.query = query;
        filter.createdAt = now;
        filter.updatedAt = now;
        return filter;
    }

    /** 부분 수정 — 안 보낸 항목은 그대로 둔다. 값 검증은 서비스가 이미 끝냈다 */
    public void edit(String name, String kind, String query, Instant now) {
        if (name != null) this.name = name;
        if (kind != null) this.kind = kind;
        if (query != null) this.query = query;
        this.updatedAt = now;
    }
}
