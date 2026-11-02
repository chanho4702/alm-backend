package com.platform.almbackend.schema;

import com.platform.almbackend.DevSettingsRace;
import com.platform.almbackend.TestConfig;
import com.platform.almbackend.dev.DevSettingsService;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V43 개발 설정 낙관적 락을 실제 PostgreSQL 16(READ COMMITTED)에서 — 같은 expectedVersion의 동시 PUT은
 * 조건부 UPDATE·INSERT ON CONFLICT가 직렬화해 하나만 이기고 나머지는 409다(lost update 없음).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@Testcontainers
class DevSettingsPostgresRaceTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DevSettingsService service;
    @Autowired ProjectRepository projects;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    long projectId;

    @BeforeEach
    void setUp() {
        permissions.reset();
        jdbc.update("delete from project_dev_settings");
        projectId = projects.save(Project.of("RACE" + System.nanoTime() % 10000, "경합", "")).getId();
    }

    @Test
    void 첫_저장과_갱신_모두_동시_요청_중_하나만_이긴다() throws Exception {
        DevSettingsRace.Outcome first = DevSettingsRace.run(service, projectId, 0, 8);
        assertThat(first.unexpected()).isEmpty();
        assertThat(first.succeeded()).isEqualTo(1);
        assertThat(first.conflicted()).isEqualTo(7);

        DevSettingsRace.Outcome second = DevSettingsRace.run(service, projectId, 1, 8);
        assertThat(second.unexpected()).isEmpty();
        assertThat(second.succeeded()).isEqualTo(1);
        assertThat(second.conflicted()).isEqualTo(7);

        assertThat(jdbc.queryForObject("select version from project_dev_settings where project_id = ?",
                Integer.class, projectId)).isEqualTo(2);
    }
}
