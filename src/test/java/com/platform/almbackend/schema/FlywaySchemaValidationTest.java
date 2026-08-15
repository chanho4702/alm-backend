package com.platform.almbackend.schema;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssuePriority;
import com.platform.almbackend.domain.IssueType;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void 마이그레이션과_JPA_매핑이_실제_Postgres에서_일치한다() {
        Project project = projects.save(Project.of("OPS", "운영", ""));
        Issue issue = issues.save(Issue.of(project.getId(), 1, "OPS-1", "런북", "본문",
                IssueType.TASK, "todo", IssuePriority.MEDIUM, null, 1L));

        assertThat(postgres.isRunning()).isTrue();
        assertThat(issues.findById(issue.getId())).isPresent();
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

