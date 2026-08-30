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

/** 보드 = 보는 방법만 저장하는 필터 뷰. filter/columns는 JSON 문서(프론트 계약 그대로) */
@Entity
@Table(name = "board")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Board {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "board_type", nullable = false, length = 16)
    private String type;

    @Column(name = "filter_json", nullable = false, columnDefinition = "text")
    private String filterJson;

    @Column(name = "columns_json", nullable = false, columnDefinition = "text")
    private String columnsJson;

    @Column(nullable = false, length = 16)
    private String swimlane;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Board of(long projectId, String name, String type, String filterJson, String columnsJson,
                           String swimlane, boolean isDefault, Instant createdAt) {
        Board board = new Board();
        board.projectId = projectId;
        board.name = name;
        board.type = type;
        board.filterJson = filterJson;
        board.columnsJson = columnsJson;
        board.swimlane = swimlane;
        board.isDefault = isDefault;
        board.createdAt = createdAt;
        return board;
    }

    public void rename(String name) { this.name = name; }
    public void changeType(String type) { this.type = type; }
    public void replaceFilter(String filterJson) { this.filterJson = filterJson; }
    public void replaceColumns(String columnsJson) { this.columnsJson = columnsJson; }
    public void changeSwimlane(String swimlane) { this.swimlane = swimlane; }
    public void setDefault(boolean value) { this.isDefault = value; }
}
