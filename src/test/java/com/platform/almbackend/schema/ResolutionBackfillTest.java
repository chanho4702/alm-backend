package com.platform.almbackend.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V29 해결 백필을 실제 Postgres에서 — V28 시점에 행을 넣고 V29를 올린다({@link ResolvedAtBackfillTest}와 같은 방식).
 * 데이터 마이그레이션은 되돌릴 수 없어, 무엇을 바꾸고 무엇을 <b>안</b> 바꾸는지를 둘 다 본다.
 *
 * <p>운영 반영 전 건수 추정은 같은 술어의 SELECT COUNT(*)로 한다(README "V29 해결 백필" 절).
 */
@Testcontainers
class ResolutionBackfillTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void V29는_완료_의미_상태인데_해결이_빈_이슈만_DONE으로_채운다() throws Exception {
        migrateTo("28");

        Instant updatedAt = Instant.parse("2026-03-04T05:06:07.123456Z");
        Instant enteredDone = Instant.parse("2026-02-01T00:00:00.000001Z");
        Instant olderStatusChange = Instant.parse("2026-01-01T00:00:00Z");
        try (Connection db = connect()) {
            long projectId = insertProject(db);
            // 사용자 정의 상태 — 카테고리 done(kind complete)에 속한다
            exec(db, "INSERT INTO status_def (id, name, category_id) VALUES ('st-shipped', '배포됨', 'done')");
            // 사용자 정의 상태 — 진행 중 의미
            exec(db, "INSERT INTO status_def (id, name, category_id) VALUES ('st-review', '검토', 'inprogress')");

            long withLog = insertIssue(db, projectId, 1, "done", null, updatedAt);
            insertIssue(db, projectId, 2, "done", null, updatedAt);                 // 이력 없음 → updated_at
            insertIssue(db, projectId, 3, "st-shipped", null, updatedAt);           // 사용자 정의 완료 상태
            insertIssue(db, projectId, 4, "todo", null, updatedAt);                 // 완료 아님 → 그대로
            insertIssue(db, projectId, 5, "st-review", null, updatedAt);            // 완료 아님 → 그대로
            long wontDo = insertIssue(db, projectId, 6, "done", "WONT_DO", updatedAt); // 이미 해결 → 그대로
            setResolvedAt(db, wontDo, olderStatusChange);
            insertIssue(db, projectId, 7, "todo", "DONE", updatedAt);               // 반대 방향은 승인 범위 밖 → 그대로

            // MIG-1: 완료로 들어온 마지막 상태 변경이 해결일이 된다(다른 상태로의 변경·옛 변경은 무시)
            insertStatusChange(db, withLog, projectId, "todo", "done", olderStatusChange);
            insertStatusChange(db, withLog, projectId, "done", "inprogress", olderStatusChange.plusSeconds(60));
            insertStatusChange(db, withLog, projectId, "inprogress", "done", enteredDone);
        }

        migrateTo("29");

        try (Connection db = connect()) {
            assertRow(db, "MIG-1", "DONE", enteredDone);
            assertRow(db, "MIG-2", "DONE", updatedAt);
            assertRow(db, "MIG-3", "DONE", updatedAt);
            assertRow(db, "MIG-4", null, null);
            assertRow(db, "MIG-5", null, null);
            assertRow(db, "MIG-6", "WONT_DO", olderStatusChange);
            assertRow(db, "MIG-7", "DONE", null);
            // 수정 시각은 건드리지 않는다(백필은 사용자 편집이 아니다)
            try (Statement st = db.createStatement();
                 ResultSet rows = st.executeQuery("SELECT count(*) FROM issue WHERE updated_at <> '2026-03-04T05:06:07.123456Z'")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
        }
    }

    // ── 도우미 ──

    private static void migrateTo(String version) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void exec(Connection db, String sql) throws Exception {
        try (Statement st = db.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static long insertProject(Connection db) throws Exception {
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO project (project_key, name, created_at, updated_at) VALUES ('MIG', '백필 검증', ?, ?) RETURNING id")) {
            insert.setTimestamp(1, now);
            insert.setTimestamp(2, now);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertIssue(Connection db, long projectId, long number, String status, String resolution,
                                    Instant updatedAt) throws Exception {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO issue (project_id, issue_number, issue_key, title, issue_type, status, priority,"
                        + " reporter_id, sort_order, resolution, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '이슈', 'task', ?, 'medium', 1, ?, ?, ?, ?) RETURNING id")) {
            insert.setLong(1, projectId);
            insert.setLong(2, number);
            insert.setString(3, "MIG-" + number);
            insert.setString(4, status);
            insert.setLong(5, number);
            insert.setString(6, resolution);
            insert.setTimestamp(7, Timestamp.from(updatedAt));
            insert.setTimestamp(8, Timestamp.from(updatedAt));
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void setResolvedAt(Connection db, long issueId, Instant at) throws Exception {
        try (PreparedStatement update = db.prepareStatement("UPDATE issue SET resolved_at = ? WHERE id = ?")) {
            update.setTimestamp(1, Timestamp.from(at));
            update.setLong(2, issueId);
            update.executeUpdate();
        }
    }

    private static void insertStatusChange(Connection db, long issueId, long projectId, String from, String to,
                                           Instant at) throws Exception {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO issue_change_log (issue_id, project_id, field, from_value, to_value, actor_id, changed_at)"
                        + " VALUES (?, ?, 'STATUS', ?, ?, 1, ?)")) {
            insert.setLong(1, issueId);
            insert.setLong(2, projectId);
            insert.setString(3, from);
            insert.setString(4, to);
            insert.setTimestamp(5, Timestamp.from(at));
            insert.executeUpdate();
        }
    }

    private static void assertRow(Connection db, String key, String resolution, Instant resolvedAt) throws Exception {
        try (PreparedStatement select = db.prepareStatement("SELECT resolution, resolved_at FROM issue WHERE issue_key = ?")) {
            select.setString(1, key);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("%s가 없다", key).isTrue();
                assertThat(rows.getString(1)).as("%s 해결", key).isEqualTo(resolution);
                Timestamp stored = rows.getTimestamp(2);
                assertThat(stored == null ? null : stored.toInstant()).as("%s 해결일", key).isEqualTo(resolvedAt);
            }
        }
    }
}
