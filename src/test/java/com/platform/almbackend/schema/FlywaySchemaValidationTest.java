package com.platform.almbackend.schema;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.test.database.replace=none"
})
@ActiveProfiles("test")
@Import(com.platform.almbackend.TestConfig.class)
@Testcontainers
class FlywaySchemaValidationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;

    @Test
    @Transactional
    void 마이그레이션과_JPA_매핑이_실제_Postgres에서_일치한다() {
        Project project = projects.save(Project.of("OPS", "운영", ""));
        Sprint sprint = sprints.save(Sprint.of(project.getId(), 1L, "Sprint 1"));
        Issue issue = issues.save(Issue.of(project.getId(), 1, "OPS-1", "런북", "본문",
                IssueType.TASK, "todo", IssuePriority.MEDIUM, null, 1L,
                null, sprint.getId(), LocalDate.of(2026, 8, 20), new BigDecimal("2.50"),
                List.of("ops", "runbook"), 3L));
        issues.flush();

        assertThat(postgres.isRunning()).isTrue();
        Issue stored = issues.findById(issue.getId()).orElseThrow();
        assertThat(stored.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(stored.getEstimateHours()).isEqualByComparingTo("2.50");
        assertThat(stored.getLabels()).containsExactly("ops", "runbook");
        assertThat(stored.getSortOrder()).isEqualTo(3L);
        assertThat(stored.getSprintId()).isEqualTo(sprint.getId());
    }

    /**
     * 한 프로젝트에 진행 중 스프린트가 둘일 수 없다는 규칙은 부분 unique 인덱스가 최종 판정한다.
     * H2 스키마에는 없으므로 실제 Postgres에서만 확인된다.
     */
    @Test
    void 진행_중_스프린트_유일성은_DB가_강제한다() {
        Project project = projects.save(Project.of("SPR", "스프린트", ""));
        Sprint first = sprints.save(Sprint.of(project.getId(), 1L, "Sprint 1"));
        Sprint second = sprints.save(Sprint.of(project.getId(), 2L, "Sprint 2"));
        first.start(Instant.parse("2026-08-20T00:00:00Z"));
        sprints.saveAndFlush(first);

        second.start(Instant.parse("2026-08-20T01:00:00Z"));
        assertThatThrownBy(() -> sprints.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 스프린트를_지우면_이슈의_참조만_풀린다() {
        Project project = projects.save(Project.of("DET", "해제", ""));
        Sprint sprint = sprints.save(Sprint.of(project.getId(), 1L, "Sprint 1"));
        Issue issue = issues.saveAndFlush(Issue.of(project.getId(), 1, "DET-1", "이슈", "",
                IssueType.TASK, "todo", IssuePriority.LOW, null, 1L,
                null, sprint.getId(), null, null, List.of(), 1L));

        sprints.delete(sprint);
        sprints.flush();

        assertThat(issues.findById(issue.getId())).get()
                .satisfies(stored -> assertThat(stored.getSprintId()).isNull());
    }

    @Test
    void 프로젝트를_삭제하면_DB_cascade로_이슈도_사라진다() {
        Project project = projects.save(Project.of("CAS", "삭제", ""));
        Issue issue = issues.save(Issue.of(project.getId(), 1, "CAS-1", "삭제 대상", "",
                IssueType.TASK, "todo", IssuePriority.LOW, null, 1L));
        projects.delete(project);
        projects.flush();

        assertThat(issues.findById(issue.getId())).isEmpty();
    }
}
