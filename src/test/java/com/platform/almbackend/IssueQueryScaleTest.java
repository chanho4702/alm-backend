package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.domain.Component;
import com.platform.almbackend.repository.ComponentRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.repository.StatusDefRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검색 의미(분류·openOnly·마감/수정 범위·정렬)·N+1 회귀·집계·라벨 후보 계약(2026-10-16).
 * 분류 계약: 사용자 정의 상태 "review"가 진행 중(inprogress) 분류면 statusCategories=inprogress에 잡혀야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueQueryScaleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired StatusDefRepository statusDefs;
    @Autowired ComponentRepository components;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired EntityManagerFactory emf;

    private long projectId;
    private long otherProjectId;
    private long number;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        components.deleteAllInBatch();
        projects.deleteAllInBatch();
        seeder.resetToDefaults();
        permissions.reset();
        // 사용자 정의 상태 — 진행 중 분류에 속한 "검토"
        statusDefs.save(StatusDef.of("review", "검토", "inprogress", ""));
        projectId = project("alm", "ALM 제품");
        otherProjectId = project("ops", "운영");
        number = 0;
    }

    @AfterEach
    void restore() {
        permissions.reset();
        seeder.resetToDefaults();
    }

    private long project(String key, String name) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    /** 레지스트리 검증을 거치지 않고 바로 저장 — 상태·마감일을 자유롭게 만든다 */
    private Issue issue(long project, String title, String status, String priority, Long assignee,
                        LocalDate due, List<String> labels, List<Long> components) {
        long n = ++number;
        Issue issue = Issue.of(project, n, (project == projectId ? "ALM-" : "OPS-") + n, title, "설명", "task",
                status, priority, assignee, 1L, null, null, due, BigDecimal.ONE, labels, n);
        issue.replaceComponents(components);
        return issues.save(issue);
    }

    /** 실제 컴포넌트 행 — Postgres에는 issue_component → component FK가 있다 */
    private long component(long project, String name) {
        return components.save(Component.of(project, name, "", null, null, Instant.now())).getId();
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asLong()));
        return out;
    }

    private JsonNode getJson(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    // ── 항목 3: 검색 의미 ──

    @Test
    void 사용자_정의_상태는_분류로_잡히고_상태_id는_그대로_통한다() throws Exception {
        issue(projectId, "검토 중", "review", "high", null, null, List.of(), List.of());
        issue(projectId, "진행", "inprogress", "high", null, null, List.of(), List.of());
        issue(projectId, "할 일", "todo", "high", null, null, List.of(), List.of());
        issue(projectId, "끝", "done", "high", null, null, List.of(), List.of());

        mvc.perform(get("/api/alm/issues/search").param("statusCategories", "inprogress").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        // kind로도 같다
        mvc.perform(get("/api/alm/issues/search").param("statusCategories", "active").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2));
        // 상태 id 그대로 — 분류로 해석하지 않는다
        mvc.perform(get("/api/alm/issues/search").param("statuses", "inprogress").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("진행"));
        // 상태 id ∪ 분류
        mvc.perform(get("/api/alm/issues/search").param("statuses", "todo")
                        .param("statusCategories", "inprogress").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(3));
        // 모르는 분류는 아무것도 잡지 않는다("전부"가 아니다)
        mvc.perform(get("/api/alm/issues/search").param("statusCategories", "nope").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(0));
        // openOnly는 완료 분류를 뺀다
        mvc.perform(get("/api/alm/issues/search").param("openOnly", "true").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void 마감일_수정시각_범위는_경계를_지키고_마감일_없음은_빠진다() throws Exception {
        issue(projectId, "a", "todo", "high", null, LocalDate.of(2026, 10, 1), List.of(), List.of());
        issue(projectId, "b", "todo", "high", null, LocalDate.of(2026, 10, 10), List.of(), List.of());
        issue(projectId, "c", "todo", "high", null, LocalDate.of(2026, 10, 20), List.of(), List.of());
        issue(projectId, "d", "todo", "high", null, null, List.of(), List.of());

        mvc.perform(get("/api/alm/issues/search").param("dueBefore", "2026-10-10").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].title").value("a"));
        mvc.perform(get("/api/alm/issues/search").param("dueFrom", "2026-10-10").param("dueTo", "2026-10-20")
                        .with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/alm/issues/search").param("updatedSince", Instant.now().minusSeconds(3600).toString())
                        .with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(get("/api/alm/issues/search").param("updatedSince", Instant.now().plusSeconds(3600).toString())
                        .with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/alm/issues/search").param("dueBefore", "10/10/2026").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 정렬은_페이지를_넘어_일관되고_마감일_없음은_항상_뒤() throws Exception {
        issue(projectId, "Charlie", "done", "low", null, LocalDate.of(2026, 10, 3), List.of(), List.of());
        issue(projectId, "alpha", "review", "high", null, null, List.of(), List.of());
        issue(projectId, "Bravo", "todo", "medium", null, LocalDate.of(2026, 10, 1), List.of(), List.of());
        issue(projectId, "delta", "inprogress", "high", null, LocalDate.of(2026, 10, 2), List.of(), List.of());
        issue(projectId, "echo", "todo", "low", null, null, List.of(), List.of());

        // 제목 — 대소문자 무시, 2건씩 넘겨도 total은 전체
        assertThat(titles("title", "asc")).containsExactly("alpha", "Bravo", "Charlie", "delta", "echo");
        // 마감일 — 오름·내림 모두 마감일 없음이 뒤, 같은 null끼리는 id 순
        assertThat(titles("due", "asc")).containsExactly("Bravo", "delta", "Charlie", "alpha", "echo");
        assertThat(titles("due", "desc")).containsExactly("Charlie", "delta", "Bravo", "alpha", "echo");
        // 상태 — 분류 순(할 일 → 진행 → 완료), 같은 분류 안에서는 상태 id 순(inprogress < review)
        assertThat(titles("status", "asc")).containsExactly("Bravo", "echo", "delta", "alpha", "Charlie");

        mvc.perform(get("/api/alm/issues/search").param("sort", "assignee").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    /** 2건 페이지를 끝까지 넘기며 모은 제목 — 각 페이지 total이 전체 건수인지도 본다 */
    private List<String> titles(String sort, String dir) throws Exception {
        List<String> out = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = getJson(get("/api/alm/issues/search").param("sort", sort).param("dir", dir)
                    .param("size", "2").param("page", String.valueOf(page)).with(asUser(1, "Alice")));
            assertThat(body.get("total").asLong()).isEqualTo(5);
            body.get("items").forEach(item -> out.add(item.get("title").asText()));
        }
        return out;
    }

    @Test
    void 라벨_컴포넌트_조건은_행을_불리지_않아_total과_정렬이_맞다() throws Exception {
        long c1 = component(projectId, "웹");
        long c2 = component(projectId, "서버");
        issue(projectId, "a", "todo", "high", null, null, List.of("x", "y"), List.of(c1, c2));
        issue(projectId, "b", "todo", "low", null, null, List.of("x"), List.of(c2));
        issue(projectId, "c", "todo", "medium", null, null, List.of("z"), List.of());

        // 라벨 둘 다 걸리는 이슈 a가 두 번 세지지 않는다 — 우선순위 정렬(CASE 식)과도 함께 돈다
        mvc.perform(get("/api/alm/issues/search").param("labels", "x", "y").param("componentIds", String.valueOf(c1), String.valueOf(c2))
                        .param("sort", "priority").param("dir", "asc").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("a"))
                .andExpect(jsonPath("$.items[1].title").value("b"));
    }

    // ── 항목 5: N+1 회귀 ──

    @Test
    void 검색_페이지는_건수와_무관하게_질의_수가_일정하고_컬렉션이_정확하다() throws Exception {
        List<Long> comps = new ArrayList<>();
        for (int i = 0; i <= 60; i++) comps.add(component(projectId, "c" + i));
        for (int i = 0; i < 60; i++) {
            // 컴포넌트 순서를 id 역순으로 — 저장 순서(component_order)를 지키는지 본다
            issue(projectId, "이슈 " + i, "todo", "high", null, null,
                    List.of("l" + i, "공통", "a" + i), List.of(comps.get(i + 1), comps.get(i)));
        }
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        boolean was = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long small = statements(statistics, 5);
            long large = statements(statistics, 50);
            // 페이지 + count + 라벨 IN + 컴포넌트 IN (권한은 페이크) — 크기와 무관한 상수
            assertThat(large).isLessThanOrEqualTo(5);
            assertThat(large).isEqualTo(small);
            assertThat(statistics.getCollectionFetchCount()).isZero();
        } finally {
            statistics.setStatisticsEnabled(was);
        }

        JsonNode body = getJson(get("/api/alm/issues/search").param("sort", "key").param("dir", "asc")
                .param("size", "50").with(asUser(1, "Alice")));
        assertThat(body.get("total").asLong()).isEqualTo(60);
        assertThat(body.get("items")).hasSize(50);
        for (int i = 0; i < 50; i++) {
            JsonNode item = body.get("items").get(i);
            assertThat(item.get("title").asText()).isEqualTo("이슈 " + i);
            assertThat(JSON.convertValue(item.get("labels"), List.class)).containsExactly("l" + i, "공통", "a" + i);
            assertThat(longs(item.get("componentIds"))).containsExactly(comps.get(i + 1), comps.get(i));
        }
    }

    private long statements(Statistics statistics, int size) throws Exception {
        statistics.clear();
        JsonNode body = getJson(get("/api/alm/issues/search").param("sort", "key").param("dir", "asc")
                .param("size", String.valueOf(size)).with(asUser(1, "Alice")));
        assertThat(body.get("items")).hasSize(size);
        long count = statistics.getPrepareStatementCount();
        System.out.println("QUERY-COUNT search size=" + size + " sql=" + count
                + " collectionFetches=" + statistics.getCollectionFetchCount());
        return count;
    }

    // ── 항목 6: 집계·라벨 ──

    @Test
    void 집계는_사용자_정의_상태를_분류로_묶고_권한_범위를_지킨다() throws Exception {
        issue(projectId, "a", "review", "high", 2L, null, List.of(), List.of());
        issue(projectId, "b", "inprogress", "high", 2L, null, List.of(), List.of());
        issue(projectId, "c", "todo", "low", null, null, List.of(), List.of());
        issue(projectId, "d", "done", "low", 3L, null, List.of(), List.of());
        issue(otherProjectId, "e", "todo", "high", 2L, null, List.of(), List.of());

        JsonNode byCategory = getJson(get("/api/alm/issues/aggregate").param("groupBy", "statusCategory")
                .with(asUser(1, "Alice")));
        assertThat(byCategory.get("total").asLong()).isEqualTo(5);
        assertThat(byCategory.get("buckets").toString())
                .isEqualTo("[{\"key\":\"inprogress\",\"count\":2},{\"key\":\"todo\",\"count\":2},{\"key\":\"done\",\"count\":1}]");

        // 권한 범위 — 접근 가능한 프로젝트만 센다
        permissions.setAccessScope(AccessScope.of(Set.of(projectId)));
        JsonNode byAssignee = getJson(get("/api/alm/issues/aggregate").param("groupBy", "assignee")
                .param("openOnly", "true").with(asUser(1, "Alice")));
        assertThat(byAssignee.get("total").asLong()).isEqualTo(3);
        assertThat(byAssignee.get("buckets").toString())
                .isEqualTo("[{\"key\":\"2\",\"count\":2},{\"key\":\"unassigned\",\"count\":1}]");

        // 분류 필터 + 상태 집계
        JsonNode byStatus = getJson(get("/api/alm/issues/aggregate").param("groupBy", "status")
                .param("statusCategories", "inprogress").with(asUser(1, "Alice")));
        assertThat(byStatus.get("buckets").toString())
                .isEqualTo("[{\"key\":\"inprogress\",\"count\":1},{\"key\":\"review\",\"count\":1}]");

        // 볼 수 있는 프로젝트가 없으면 0
        permissions.setAccessScope(AccessScope.of(Set.of()));
        mvc.perform(get("/api/alm/issues/aggregate").param("groupBy", "priority").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.buckets.length()").value(0));

        // 명시한 프로젝트에 권한이 없으면 403
        permissions.reset();
        permissions.setAllowed(false);
        mvc.perform(get("/api/alm/issues/aggregate").param("groupBy", "priority")
                        .param("projectIds", String.valueOf(projectId)).with(asUser(1, "Alice")))
                .andExpect(status().isForbidden());

        permissions.reset();
        mvc.perform(get("/api/alm/issues/aggregate").param("groupBy", "reporter").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void 라벨_후보는_보관되지_않은_이슈에서_중복없이_정렬된다() throws Exception {
        issue(projectId, "a", "todo", "high", null, null, List.of("beta", "Alpha"), List.of());
        issue(projectId, "b", "todo", "high", null, null, List.of("alpha", "beta"), List.of());
        Issue archived = issue(projectId, "c", "todo", "high", null, null, List.of("숨김"), List.of());
        issue(otherProjectId, "d", "todo", "high", null, null, List.of("다른 프로젝트"), List.of());
        mvc.perform(post("/api/alm/issues/{id}/archive", archived.getId()).with(asUser(1, "Alice")))
                .andExpect(status().isOk());

        JsonNode labels = getJson(get("/api/alm/projects/{id}/labels", projectId).with(asUser(1, "Alice")));
        assertThat(labels.toString()).isEqualTo("[\"Alpha\",\"alpha\",\"beta\"]");

        permissions.setAllowed(false);
        mvc.perform(get("/api/alm/projects/{id}/labels", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isForbidden());
    }
}
