package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.permission.AlmAction;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.time.ZoneId;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 계획 설정(V39) — 사이클(스프린트 자동 생성·이월 기본값)과 추정 척도(허용 값·T셔츠 매핑·검증).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class PlanningSettingsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long VIEWER = 5;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        TestData.clear(jdbc);
        projectId = TestData.project(mvc, "PL");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    private JsonNode settings() throws Exception {
        return JSON.readTree(mvc.perform(get("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode save(String body) throws Exception {
        return JSON.readTree(mvc.perform(put("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void saveExpecting400(String body, String message) throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString(message)));
    }

    private long issue(String title, String status, Long sprintId, String points) throws Exception {
        String details = sprintId == null ? "{}" : "{\"sprintId\":" + sprintId + "}";
        String planning = points == null ? "" : ",\"planning\":{\"storyPoints\":" + points + "}";
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"" + status
                                + "\",\"priority\":\"MEDIUM\",\"details\":" + details + planning + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode complete(long sprintId, String body) throws Exception {
        var request = post("/api/alm/sprints/{id}/complete", sprintId).with(asUser(1, "Alice"));
        if (body != null) request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Long sprintOf(long issueId) throws Exception {
        JsonNode node = JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        return node.get("sprintId").isNull() ? null : node.get("sprintId").asLong();
    }

    // ── 설정 ──

    @Test
    void 행이_없으면_기본값_version_0이고_기존_동작과_같다() throws Exception {
        JsonNode s = settings();
        assertThat(s.get("version").asInt()).isZero();
        assertThat(s.at("/sprintAutomation/autoCreateNext").asBoolean()).isFalse();
        assertThat(s.at("/sprintAutomation/lengthDays").asInt()).isEqualTo(14);
        assertThat(s.at("/sprintAutomation/cooldownDays").asInt()).isZero();
        assertThat(s.at("/sprintAutomation/defaultCarryOver").asText()).isEqualTo("backlog");
        assertThat(s.get("estimationScale").asText()).isEqualTo("linear");
        assertThat(s.get("allowedPoints").isNull()).isTrue();
        assertThat(s.get("tshirtPoints").toString()).isEqualTo("{\"XS\":1,\"S\":2,\"M\":3,\"L\":5,\"XL\":8}");
    }

    @Test
    void 쓰기는_프로젝트_관리자만_읽기는_열람자도_version이_어긋나면_409() throws Exception {
        permissions.denyAction(VIEWER, projectId, AlmAction.EDIT);
        mvc.perform(get("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(VIEWER, "Viewer")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(VIEWER, "Viewer"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estimationScale\":\"fibonacci\",\"expectedVersion\":0}"))
                .andExpect(status().isForbidden());

        assertThat(save("{\"estimationScale\":\"fibonacci\",\"expectedVersion\":0}").get("version").asInt()).isEqualTo(1);
        mvc.perform(put("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estimationScale\":\"tshirt\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/alm/projects/{id}/planning-settings", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estimationScale\":\"tshirt\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 보낸_항목만_바뀐다() throws Exception {
        save("{\"sprintAutomation\":{\"autoCreateNext\":true,\"lengthDays\":7},\"expectedVersion\":0}");
        JsonNode s = save("{\"sprintAutomation\":{\"cooldownDays\":2},\"estimationScale\":\"fibonacci\",\"expectedVersion\":1}");
        assertThat(s.at("/sprintAutomation/autoCreateNext").asBoolean()).isTrue();
        assertThat(s.at("/sprintAutomation/lengthDays").asInt()).isEqualTo(7);
        assertThat(s.at("/sprintAutomation/cooldownDays").asInt()).isEqualTo(2);
        assertThat(s.get("allowedPoints").toString()).isEqualTo("[0,1,2,3,5,8,13,21]");
    }

    @Test
    void 설정_값_검증() throws Exception {
        saveExpecting400("{\"sprintAutomation\":{\"lengthDays\":0},\"expectedVersion\":0}", "1~90일");
        saveExpecting400("{\"sprintAutomation\":{\"cooldownDays\":31},\"expectedVersion\":0}", "0~30일");
        saveExpecting400("{\"sprintAutomation\":{\"defaultCarryOver\":\"trash\"},\"expectedVersion\":0}", "next·backlog");
        saveExpecting400("{\"estimationScale\":\"powers\",\"expectedVersion\":0}", "알 수 없는 추정 척도");
        saveExpecting400("{\"estimationScale\":\"custom\",\"expectedVersion\":0}", "값이 하나 이상");
        saveExpecting400("{\"estimationScale\":\"custom\",\"customValues\":[1,2,2],\"expectedVersion\":0}", "겹칩니다");
        saveExpecting400("{\"estimationScale\":\"custom\",\"customValues\":[1.25],\"expectedVersion\":0}", "소수 첫째 자리");
        saveExpecting400("{\"estimationScale\":\"custom\",\"customValues\":[10000],\"expectedVersion\":0}", "0~9999");
        saveExpecting400("{\"tshirtPoints\":{\"XS\":1,\"S\":2,\"M\":3,\"L\":5},\"expectedVersion\":0}", "다섯 개");
        saveExpecting400("{\"tshirtPoints\":{\"XS\":1,\"S\":3,\"M\":3,\"L\":5,\"XL\":8},\"expectedVersion\":0}", "커져야");
        assertThat(settings().get("version").asInt()).isZero(); // 하나도 저장되지 않았다
    }

    // ── 추정 척도 ──

    @Test
    void 피보나치_척도는_밖의_값을_400으로_막고_손대지_않은_옛_값은_그대로_둔다() throws Exception {
        long legacy = issue("옛 값", "todo", null, "4"); // linear(기본) 때 넣은 값
        save("{\"estimationScale\":\"fibonacci\",\"expectedVersion\":0}");

        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"새\",\"description\":\"\",\"details\":{},\"planning\":{\"storyPoints\":4}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("스토리 포인트 4은(는) 이 프로젝트의 추정 척도(피보나치)에 없습니다 — 허용: 0, 1, 2, 3, 5, 8, 13, 21"));
        issue("척도 안", "todo", null, "5");

        JsonNode current = JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", legacy).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        // 다른 필드만 고치면(포인트 그대로) 통과
        mvc.perform(put("/api/alm/issues/{id}", legacy).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"옛 값 수정\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\",\"priority\":\"MEDIUM\","
                                + "\"details\":{},\"expectedVersion\":" + current.get("version").asInt() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.storyPoints").value(4));
        // 포인트를 척도 밖 값으로 바꾸면 400
        mvc.perform(put("/api/alm/issues/{id}", legacy).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"옛 값 수정\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\",\"priority\":\"MEDIUM\","
                                + "\"details\":{},\"planning\":{\"storyPoints\":6},\"expectedVersion\":" + (current.get("version").asInt() + 1) + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void T셔츠는_크기를_포인트_숫자로_매핑하고_합계는_숫자로_센다() throws Exception {
        JsonNode s = save("{\"estimationScale\":\"tshirt\",\"tshirtPoints\":{\"XS\":0.5,\"S\":1,\"M\":2,\"L\":4,\"XL\":10},\"expectedVersion\":0}");
        assertThat(s.get("allowedPoints").toString()).isEqualTo("[0.5,1,2,4,10]");
        assertThat(s.get("tshirtPoints").toString()).isEqualTo("{\"XS\":0.5,\"S\":1,\"M\":2,\"L\":4,\"XL\":10}");

        issue("M", "todo", null, "2");
        issue("XL", "todo", null, "10");
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"없는 크기\",\"description\":\"\",\"details\":{},\"planning\":{\"storyPoints\":3}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("T셔츠")));
        mvc.perform(get("/api/alm/projects/{id}/estimation/summary", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].storyPoints").value(12));
    }

    @Test
    void 사용자_지정_척도는_오름차순으로_저장하고_그_값만_받는다() throws Exception {
        JsonNode s = save("{\"estimationScale\":\"custom\",\"customValues\":[8,1,0.5,3],\"expectedVersion\":0}");
        assertThat(s.get("customValues").toString()).isEqualTo("[0.5,1,3,8]");
        assertThat(s.get("allowedPoints").toString()).isEqualTo("[0.5,1,3,8]");
        issue("허용", "todo", null, "0.5");
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"밖\",\"description\":\"\",\"details\":{},\"planning\":{\"storyPoints\":2}}"))
                .andExpect(status().isBadRequest());
        // linear로 돌리면 제한 없음 — custom 값은 남아 있다
        s = save("{\"estimationScale\":\"linear\",\"expectedVersion\":1}");
        assertThat(s.get("allowedPoints").isNull()).isTrue();
        assertThat(s.get("customValues").toString()).isEqualTo("[0.5,1,3,8]");
        issue("자유", "todo", null, "2");
    }

    // ── 사이클 ──

    @Test
    void 자동_생성을_켜면_다음_스프린트가_없을_때_만들고_미완료는_기본값_next로_간다() throws Exception {
        save("{\"sprintAutomation\":{\"autoCreateNext\":true,\"lengthDays\":7,\"cooldownDays\":2,\"defaultCarryOver\":\"next\"},\"expectedVersion\":0}");
        long sprint = TestData.sprint(mvc, projectId, "Sprint 3");
        long open = issue("남은 일", "todo", sprint, null);
        long done = issue("끝난 일", "done", sprint, null);
        mvc.perform(post("/api/alm/sprints/{id}/start", sprint).with(asUser(1, "Alice"))).andExpect(status().isOk());

        JsonNode result = complete(sprint, null);

        assertThat(result.get("state").asText()).isEqualTo("DONE");              // 스프린트 필드는 예전처럼 맨 위에
        assertThat(result.get("id").asLong()).isEqualTo(sprint);
        JsonNode carry = result.get("carryOver");
        assertThat(carry.get("nextSprintAutoCreated").asBoolean()).isTrue();
        assertThat(carry.get("target").asText()).isEqualTo("sprint");
        assertThat(carry.get("movedCount").asInt()).isEqualTo(1);
        long next = carry.get("nextSprintId").asLong();
        assertThat(carry.get("sprintId").asLong()).isEqualTo(next);
        assertThat(sprintOf(open)).isEqualTo(next);
        assertThat(sprintOf(done)).isEqualTo(sprint);

        JsonNode created = JSON.readTree(mvc.perform(get("/api/alm/sprints/{id}", next).with(asUser(1, "Alice")))
                .andReturn().getResponse().getContentAsString());
        assertThat(created.get("name").asText()).isEqualTo("Sprint 4");
        assertThat(created.get("state").asText()).isEqualTo("PLANNED");
        LocalDate completedOn = LocalDate.ofInstant(java.time.Instant.parse(result.get("completedAt").asText()), ZoneId.of("Asia/Seoul"));
        assertThat(created.get("plannedStart").asText()).isEqualTo(completedOn.plusDays(2).toString());
        assertThat(created.get("plannedEnd").asText()).isEqualTo(completedOn.plusDays(8).toString());
    }

    @Test
    void 예정_스프린트가_이미_있으면_만들지_않고_그것으로_이월한다_요청의_backlog_선택이_기본값을_이긴다() throws Exception {
        save("{\"sprintAutomation\":{\"autoCreateNext\":true,\"defaultCarryOver\":\"next\"},\"expectedVersion\":0}");
        long s1 = TestData.sprint(mvc, projectId, "결제 1");
        long s2 = TestData.sprint(mvc, projectId, "다음 것");
        long open = issue("남은 일", "todo", s1, null);
        mvc.perform(post("/api/alm/sprints/{id}/start", s1).with(asUser(1, "Alice"))).andExpect(status().isOk());

        JsonNode carry = complete(s1, null).get("carryOver");
        assertThat(carry.get("nextSprintAutoCreated").asBoolean()).isFalse();
        assertThat(carry.get("nextSprintId").asLong()).isEqualTo(s2);
        assertThat(sprintOf(open)).isEqualTo(s2);
        assertThat(jdbc.queryForObject("select count(*) from sprint where project_id = ?", Long.class, projectId)).isEqualTo(2);

        // 두 번째 사이클: 요청이 backlog를 고르면 설정이 next여도 백로그로 — 다음 스프린트는 그래도 만든다("결제 1" 다음은 "Sprint 3")
        mvc.perform(post("/api/alm/sprints/{id}/start", s2).with(asUser(1, "Alice"))).andExpect(status().isOk());
        carry = complete(s2, "{\"moveUnfinishedTo\":\"backlog\"}").get("carryOver");
        assertThat(carry.get("target").asText()).isEqualTo("backlog");
        assertThat(carry.get("sprintId").isNull()).isTrue();
        assertThat(carry.get("nextSprintAutoCreated").asBoolean()).isTrue();
        assertThat(sprintOf(open)).isNull();
        JsonNode created = JSON.readTree(mvc.perform(get("/api/alm/sprints/{id}", carry.get("nextSprintId").asLong())
                .with(asUser(1, "Alice"))).andReturn().getResponse().getContentAsString());
        assertThat(created.get("name").asText()).isEqualTo("Sprint 3"); // "다음 것"은 끝이 숫자가 아니다 → Sprint N
    }

    @Test
    void 설정이_없으면_예전처럼_백로그로_가고_아무것도_만들지_않는다_명시_스프린트_id는_언제나_이긴다() throws Exception {
        long s1 = TestData.sprint(mvc, projectId, "S1");
        long open = issue("남은 일", "todo", s1, null);
        mvc.perform(post("/api/alm/sprints/{id}/start", s1).with(asUser(1, "Alice"))).andExpect(status().isOk());
        JsonNode carry = complete(s1, null).get("carryOver");
        assertThat(carry.get("target").asText()).isEqualTo("backlog");
        assertThat(carry.get("nextSprintId").isNull()).isTrue();
        assertThat(carry.get("nextSprintAutoCreated").asBoolean()).isFalse();
        assertThat(sprintOf(open)).isNull();

        save("{\"sprintAutomation\":{\"defaultCarryOver\":\"next\"},\"expectedVersion\":0}");
        long s2 = TestData.sprint(mvc, projectId, "S2");
        long s3 = TestData.sprint(mvc, projectId, "S3");
        long s4 = TestData.sprint(mvc, projectId, "S4");
        long moved = issue("옮길 것", "todo", s2, null);
        mvc.perform(post("/api/alm/sprints/{id}/start", s2).with(asUser(1, "Alice"))).andExpect(status().isOk());
        carry = complete(s2, "{\"moveUnfinishedToSprintId\":" + s4 + "}").get("carryOver");
        assertThat(carry.get("sprintId").asLong()).isEqualTo(s4);
        assertThat(carry.get("nextSprintId").asLong()).isEqualTo(s3);
        assertThat(sprintOf(moved)).isEqualTo(s4);

        mvc.perform(post("/api/alm/sprints/{id}/complete", s3).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"moveUnfinishedTo\":\"somewhere\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 다음_스프린트_이름_규칙() {
        assertThat(com.platform.almbackend.sprint.SprintService.nextName("Sprint 9", 12)).isEqualTo("Sprint 10");
        assertThat(com.platform.almbackend.sprint.SprintService.nextName("결제 7 ", 3)).isEqualTo("결제 8");
        assertThat(com.platform.almbackend.sprint.SprintService.nextName("릴리스 준비", 5)).isEqualTo("Sprint 5");
        assertThat(com.platform.almbackend.sprint.SprintService.nextName(null, 2)).isEqualTo("Sprint 2");
    }
}
