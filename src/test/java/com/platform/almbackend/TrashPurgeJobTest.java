package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.project.TrashPurgeJob;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 휴지통 자동 비우기 — 보존 기간(기본 60일)이 지난 것만 지우고, 남은 것에는 purgeAt이 실린다 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class TrashPurgeJobTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrashPurgeJob job;
    @Autowired TestConfig.FakePermissionClient permissions;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from issue");
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
    }

    private long createProject(String key) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + " 프로젝트\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private void trash(long projectId) throws Exception {
        mvc.perform(delete("/api/alm/projects/{id}", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
    }

    /** 휴지통에 들어간 시각을 뒤로 밀어 "보존 기간이 지난" 상태를 만든다 */
    private void backdate(long projectId, Duration ago) {
        jdbc.update("update project set deleted_at = ? where id = ?",
                Timestamp.from(Instant.now().minus(ago)), projectId);
    }

    @Test
    void 보존_기간이_지난_프로젝트만_영구_삭제된다() throws Exception {
        long old = createProject("old");
        long fresh = createProject("fresh");
        trash(old);
        trash(fresh);
        backdate(old, Duration.ofDays(61));

        int purged = job.purgeExpired(Instant.now());

        assertThat(purged).isEqualTo(1);
        assertThat(projects.findTrashedById(old)).isEmpty();
        assertThat(projects.findTrashedById(fresh)).isPresent();
        mvc.perform(get("/api/alm/projects/trash").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(fresh));
    }

    @Test
    void 보존_기간_안이면_아무것도_지우지_않는다() throws Exception {
        long project = createProject("keep");
        trash(project);
        backdate(project, Duration.ofDays(59));

        assertThat(job.purgeExpired(Instant.now())).isZero();
        assertThat(projects.findTrashedById(project)).isPresent();
    }

    @Test
    void 선정_뒤_복원되어_다시_버려진_프로젝트는_보존_기간이_새로_시작돼_지우지_않는다() throws Exception {
        // 잡이 만료 id를 뽑은 다음(임계값 고정) 사용자가 복원 → 재삭제하면 deleted_at이 새로 찍힌다.
        // 삭제 직전 임계값을 다시 보지 않으면 오늘 버린 프로젝트가 영구 삭제된다.
        long project = createProject("race");
        trash(project);
        backdate(project, Duration.ofDays(61));
        Instant threshold = Instant.now().minus(Duration.ofDays(60));
        assertThat(projects.findTrashedIdsDeletedBefore(threshold, 500)).containsExactly(project);

        mvc.perform(post("/api/alm/projects/{id}/restore", project).with(asUser(1, "Alice")))
                .andExpect(status().isOk());
        trash(project); // deleted_at = 지금

        int purged = job.purgeExpired(threshold.plus(Duration.ofDays(60)));
        assertThat(purged).isZero();
        assertThat(projects.findTrashedById(project)).isPresent();
    }

    @Test
    void 정확히_보존_기간_경계에서는_아직_지우지_않는다() throws Exception {
        long project = createProject("edge");
        trash(project);
        // 서비스가 deleted_at을 마이크로초로 절삭해 저장하므로 같은 정밀도로 맞춘다(나노가 남으면 DB 값이 더 작아진다)
        Instant deletedAt = Instant.now().minus(Duration.ofDays(60)).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("update project set deleted_at = ? where id = ?", Timestamp.from(deletedAt), project);

        // deleted_at < threshold 라 같은 시각은 대상이 아니다
        assertThat(job.purgeExpired(deletedAt.plus(Duration.ofDays(60)))).isZero();
        assertThat(projects.findTrashedById(project)).isPresent();
    }

    @Test
    void 휴지통_항목에만_purgeAt이_실린다() throws Exception {
        long project = createProject("pa");
        mvc.perform(get("/api/alm/projects/{id}", project).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.purgeAt").doesNotExist());

        trash(project);
        String trashed = mvc.perform(get("/api/alm/projects/trash").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].purgeAt").exists())
                .andReturn().getResponse().getContentAsString();

        Instant deletedAt = Instant.parse(JSON.readTree(trashed).get(0).get("deletedAt").asText());
        Instant purgeAt = Instant.parse(JSON.readTree(trashed).get(0).get("purgeAt").asText());
        assertThat(purgeAt).isEqualTo(deletedAt.plus(Duration.ofDays(60)));
    }
}
