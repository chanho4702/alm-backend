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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V25(issue_web_link 유니크, AGP-49)를 실제 Postgres에서 돌린다 — V24 시점에 중복 행을 넣어 두고 올려야
 * 정리 DELETE가 한 줄이라도 검증된다. 서비스가 쓰는 {@code ON CONFLICT DO NOTHING}이 동시 트랜잭션에서
 * 예외 없이 0행으로 끝나는지도 여기서 본다(H2는 락 대기 의미가 다르다).
 */
@Testcontainers
class WebLinkUniqueMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String INSERT_IF_ABSENT =
            "INSERT INTO issue_web_link (issue_id, url, title, kind, created_by, created_at)"
                    + " VALUES (?, ?, NULL, 'PR', 1, now()) ON CONFLICT DO NOTHING";

    @Test
    void V25는_중복을_최소_id만_남기고_정리한_뒤_유니크를_건다() throws Exception {
        cleanAndMigrateTo("24");
        long issueA;
        long issueB;
        List<Long> dupIds = new ArrayList<>();
        long otherIssueSameUrl;
        long caseVariant;
        try (Connection db = connect()) {
            long projectId = insertProject(db);
            issueA = insertIssue(db, projectId, 1);
            issueB = insertIssue(db, projectId, 2);
            for (int i = 0; i < 3; i++) dupIds.add(insertLink(db, issueA, "https://x.test/pull/1"));
            otherIssueSameUrl = insertLink(db, issueB, "https://x.test/pull/1");
            caseVariant = insertLink(db, issueA, "https://x.test/PULL/1");
        }

        migrateTo("25");

        try (Connection db = connect()) {
            // 이슈 A의 중복 셋 중 최소 id만 남는다 — 대소문자가 다른 URL은 다른 링크다
            assertThat(linkIds(db, issueA)).containsExactlyInAnyOrder(dupIds.get(0), caseVariant);
            // 다른 이슈의 같은 URL은 중복이 아니다
            assertThat(linkIds(db, issueB)).containsExactly(otherIssueSameUrl);

            assertThatThrownBy(() -> insertLink(db, issueA, "https://x.test/pull/1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_issue_web_link_issue_url");
        }
    }

    @Test
    void ON_CONFLICT는_먼저_삽입한_트랜잭션이_커밋되면_예외없이_0행으로_끝난다() throws Exception {
        cleanAndMigrateTo("25");
        long issueId;
        try (Connection db = connect()) {
            issueId = insertIssue(db, insertProject(db), 1);
        }
        String url = "https://x.test/commit/abc";

        try (Connection first = connect(); Connection second = connect()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            assertThat(insertIfAbsent(first, issueId, url)).isEqualTo(1);

            // 두 번째는 첫 트랜잭션이 끝날 때까지 기다린다
            CompletableFuture<Integer> racing = CompletableFuture.supplyAsync(() -> {
                try {
                    return insertIfAbsent(second, issueId, url);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(300);
            assertThat(racing).isNotDone();

            first.commit();
            assertThat(racing.get(10, TimeUnit.SECONDS)).isZero();
            // 같은 트랜잭션에서 이어서 조회해도 된다(중단되지 않았다) — READ COMMITTED라 커밋된 행이 보인다
            assertThat(linkIds(second, issueId)).hasSize(1);
            second.commit();
        }
    }

    // ── 도우미 ──

    /** 컨테이너를 두 테스트가 같이 쓴다 — 실행 순서와 무관하게 빈 스키마에서 시작한다 */
    private static void cleanAndMigrateTo(String version) {
        flyway(version).clean();
        migrateTo(version);
    }

    private static void migrateTo(String version) {
        flyway(version).migrate();
    }

    private static Flyway flyway(String version) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion(version))
                .load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static long insertProject(Connection db) throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        String key = "W" + System.nanoTime() % 100000;
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO project (project_key, name, created_at, updated_at) VALUES (?, '웹링크', ?, ?) RETURNING id")) {
            insert.setString(1, key);
            insert.setTimestamp(2, now);
            insert.setTimestamp(3, now);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertIssue(Connection db, long projectId, long number) throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        try (PreparedStatement insert = db.prepareStatement(
                // sort_order는 기본값 0이 ck_issue_sort_order_positive에 걸린다 — 직접 넣는다
                "INSERT INTO issue (project_id, issue_number, issue_key, title, issue_type, status, priority,"
                        + " reporter_id, sort_order, created_at, updated_at)"
                        + " VALUES (?, ?, ?, '웹링크', 'task', 'todo', 'medium', 1, ?, ?, ?) RETURNING id")) {
            insert.setLong(1, projectId);
            insert.setLong(2, number);
            insert.setString(3, "W-" + projectId + "-" + number);
            insert.setLong(4, number);
            insert.setTimestamp(5, now);
            insert.setTimestamp(6, now);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long insertLink(Connection db, long issueId, String url) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO issue_web_link (issue_id, url, kind, created_by, created_at)"
                        + " VALUES (?, ?, 'PR', 1, now()) RETURNING id")) {
            insert.setLong(1, issueId);
            insert.setString(2, url);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static int insertIfAbsent(Connection db, long issueId, String url) throws SQLException {
        try (PreparedStatement insert = db.prepareStatement(INSERT_IF_ABSENT)) {
            insert.setLong(1, issueId);
            insert.setString(2, url);
            return insert.executeUpdate();
        }
    }

    private static List<Long> linkIds(Connection db, long issueId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement select = db.prepareStatement("SELECT id FROM issue_web_link WHERE issue_id = ?")) {
            select.setLong(1, issueId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }
}
