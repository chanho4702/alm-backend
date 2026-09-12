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
 * V23의 백필({@code UPDATE issue SET resolved_at = updated_at WHERE resolution IS NOT NULL})을
 * 실제 Postgres에서 돌려 본다. 데이터 마이그레이션은 되돌릴 수 없고, 빈 테이블에 적용하면 아무 일도
 * 일어나지 않으므로({@link FlywaySchemaValidationTest}가 그 경우다) <b>V22 시점에 행을 넣어 두고</b>
 * V23을 올리는 방식이어야 한 줄이라도 검증된다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다 — V22 시점의 스키마에는 {@code resolved_at}이 없어 JPA
 * {@code ddl-auto=validate}가 먼저 깨진다. 여기서 보는 것은 SQL 그 자체다.
 */
@Testcontainers
class ResolvedAtBackfillTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** V23 직전 상태 */
    private static final String BEFORE_RESOLVED_AT = "22";
    private static final String WITH_RESOLVED_AT = "23";

    @Test
    void V23은_이미_해결된_이슈만_마지막_수정_시각으로_백필한다() throws Exception {
        migrateTo(BEFORE_RESOLVED_AT);

        Instant resolvedUpdatedAt = Instant.parse("2026-03-04T05:06:07.123456Z");
        Instant openUpdatedAt = Instant.parse("2026-05-06T07:08:09.987654Z");
        try (Connection db = connect()) {
            // V22 시점에는 아직 컬럼이 없다 — 있으면 이 테스트가 검증하려는 상태가 아니다
            assertThat(hasResolvedAtColumn(db)).isFalse();

            long projectId = insertProject(db, "MIG", "이관 검증");
            insertIssue(db, projectId, 1, "MIG-1", "이미 해결된 이슈", "DONE", resolvedUpdatedAt);
            insertIssue(db, projectId, 2, "MIG-2", "아직 미해결", null, openUpdatedAt);
        }

        migrateTo(WITH_RESOLVED_AT);

        try (Connection db = connect()) {
            assertThat(hasResolvedAtColumn(db)).isTrue();
            // 해결된 행: 마지막 수정 시각이 해결일로 들어온다(그 이상은 알 수 없어 근사치다)
            assertThat(resolvedAtOf(db, "MIG-1")).isEqualTo(resolvedUpdatedAt);
            // 미해결 행은 건드리지 않는다 — updated_at을 베껴 넣으면 "해결된 적 없는데 해결일이 있다"가 된다
            assertThat(resolvedAtOf(db, "MIG-2")).isNull();
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
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static boolean hasResolvedAtColumn(Connection db) throws Exception {
        try (Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM information_schema.columns "
                             + "WHERE table_name = 'issue' AND column_name = 'resolved_at'")) {
            return rows.next();
        }
    }

    private static long insertProject(Connection db, String key, String name) throws Exception {
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO project (project_key, name, created_at, updated_at) VALUES (?, ?, ?, ?) "
                        + "RETURNING id")) {
            insert.setString(1, key);
            insert.setString(2, name);
            insert.setTimestamp(3, now);
            insert.setTimestamp(4, now);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void insertIssue(Connection db, long projectId, long number, String key, String title,
                                    String resolution, Instant updatedAt) throws Exception {
        try (PreparedStatement insert = db.prepareStatement(
                // sort_order는 기본값 0이 ck_issue_sort_order_positive에 걸린다 — 직접 넣는다
                "INSERT INTO issue (project_id, issue_number, issue_key, title, issue_type, status, priority,"
                        + " reporter_id, sort_order, resolution, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'task', 'done', 'medium', 1, ?, ?, ?, ?)")) {
            insert.setLong(1, projectId);
            insert.setLong(2, number);
            insert.setString(3, key);
            insert.setString(4, title);
            insert.setLong(5, number);
            insert.setString(6, resolution);
            insert.setTimestamp(7, Timestamp.from(updatedAt));
            insert.setTimestamp(8, Timestamp.from(updatedAt));
            insert.executeUpdate();
        }
    }

    private static Instant resolvedAtOf(Connection db, String issueKey) throws Exception {
        try (PreparedStatement select = db.prepareStatement(
                "SELECT resolved_at FROM issue WHERE issue_key = ?")) {
            select.setString(1, issueKey);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("%s가 없다", issueKey).isTrue();
                Timestamp stored = rows.getTimestamp(1);
                return stored == null ? null : stored.toInstant();
            }
        }
    }
}
