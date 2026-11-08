package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.PriorityDef;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.issue.IssueSearchService;
import com.platform.almbackend.issue.dto.IssueCardResponse;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.PriorityDefRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.platform.almbackend.TestAuth.asAdmin;
import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이슈 카드 배치 계약(W3 스펙 §4.1) — 위키 스마트 링크가 쓴다. 볼 수 있는 기존 이슈만 요청 순으로, 나머지는
 * 403/404 없이 조용히 빠진다. 표시값은 프로젝트의 해석된 설정·레지스트리에서, 담당자 이름은 org에서 한 번에.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueCardControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired ProjectSettingsRepository projectSettings;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired IssueTypeDefRepository typeDefs;
    @Autowired PriorityDefRepository priorityDefs;
    @Autowired StatusCategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired IssueSearchService search;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.FakeMemberDirectory directory;
    @Autowired TestConfig.SettingsSeeder seeder;

    private long almId;
    private long platId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projectSettings.deleteAllInBatch();
        projects.deleteAllInBatch();
        seeder.resetToDefaults();
        permissions.reset();
        directory.reset();
        almId = project("alm");
        platId = project("plat");
        create(almId, "로그인 버그", "BUG", "todo", "HIGH", 7L, "2026-11-30");   // ALM-1
        create(almId, "보드 개선", "STORY", "inprogress", "MEDIUM", null, null); // ALM-2
        create(almId, "문서 정리", "TASK", "done", "LOW", 8L, null);            // ALM-3
        create(platId, "게이트웨이 정리", "TASK", "todo", "MEDIUM", 7L, null);   // PLAT-1
    }

    /** 레지스트리·스킴은 다른 테스트 클래스와 같은 DB다 — 바꾼 구성을 되돌린다 */
    @AfterEach
    void restore() {
        seeder.resetToDefaults();
        permissions.reset();
        directory.reset();
    }

    private long project(String key) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + " 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private void create(long projectId, String title, String type, String status, String priority, Long assignee,
                        String due) throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"설명\",\"type\":\"" + type
                                + "\",\"status\":\"" + status + "\",\"priority\":\"" + priority
                                + "\",\"assigneeId\":" + assignee + ",\"details\":{"
                                + (due == null ? "" : "\"dueDate\":\"" + due + "\"") + "}}"))
                .andExpect(status().isCreated());
    }

    private JsonNode cards(String keys) throws Exception {
        String body = mvc.perform(get("/api/alm/issues/cards").param("keys", keys).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private static List<String> keysOf(JsonNode cards) {
        return IntStream.range(0, cards.size()).mapToObj(i -> cards.get(i).get("key").asText()).toList();
    }

    @Test
    void 카드는_요청_순서로_표시값과_담당자_이름까지_채워진다() throws Exception {
        directory.put(7, "Alice Kim", "alice@test.com", "ACTIVE");
        IssueTypeDef bug = typeDefs.findById("bug").orElseThrow();
        PriorityDef high = priorityDefs.findById("high").orElseThrow();
        StatusCategory todo = categories.findById("todo").orElseThrow();

        mvc.perform(get("/api/alm/issues/cards").param("keys", "PLAT-1,ALM-1").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("PLAT-1"))
                .andExpect(jsonPath("$[0].projectId").value(platId))
                .andExpect(jsonPath("$[0].projectKey").value("PLAT"))
                .andExpect(jsonPath("$[1].key").value("ALM-1"))
                .andExpect(jsonPath("$[1].projectId").value(almId))
                .andExpect(jsonPath("$[1].projectKey").value("ALM"))
                .andExpect(jsonPath("$[1].title").value("로그인 버그"))
                .andExpect(jsonPath("$[1].type.id").value("bug"))
                .andExpect(jsonPath("$[1].type.name").value(bug.getName()))
                .andExpect(jsonPath("$[1].type.icon").value(bug.getIcon()))
                .andExpect(jsonPath("$[1].type.color").value(bug.getColor()))
                .andExpect(jsonPath("$[1].priority.id").value("high"))
                .andExpect(jsonPath("$[1].priority.name").value(high.getName()))
                .andExpect(jsonPath("$[1].priority.icon").value(high.getIcon()))
                .andExpect(jsonPath("$[1].priority.color").value(high.getColor()))
                .andExpect(jsonPath("$[1].status.id").value("todo"))
                .andExpect(jsonPath("$[1].status.kind").value("new"))
                .andExpect(jsonPath("$[1].status.color").value(todo.getColor()))
                .andExpect(jsonPath("$[1].status.icon").value("circle"))
                .andExpect(jsonPath("$[1].assignee.id").value(7))
                .andExpect(jsonPath("$[1].assignee.displayName").value("Alice Kim"))
                .andExpect(jsonPath("$[1].dueDate").value("2026-11-30"))
                .andExpect(jsonPath("$[1].resolved").value(false))
                .andExpect(jsonPath("$[1].url").value("/alm/browse/ALM-1"));

        // 담당자 없음·마감 없음은 null, 완료 분류면 resolved
        JsonNode both = cards("ALM-2,ALM-3");
        assertThat(both.get(0).get("assignee").isNull()).isTrue();
        assertThat(both.get(0).get("dueDate").isNull()).isTrue();
        assertThat(both.get(0).get("status").get("kind").asText()).isEqualTo("active");
        assertThat(both.get(0).get("resolved").asBoolean()).isFalse();
        assertThat(both.get(1).get("status").get("kind").asText()).isEqualTo("complete");
        assertThat(both.get(1).get("resolved").asBoolean()).isTrue();
    }

    @Test
    void 키는_정규화되고_형식이_틀린_항목은_무시하며_중복은_하나다() throws Exception {
        JsonNode result = cards(" alm-2 , not-a-key, ALM-2,1ABC-3,A-1,ALM-01x,, alm-1");
        assertThat(keysOf(result)).containsExactly("ALM-2", "ALM-1");

        // 반복 파라미터도 같은 규칙
        String body = mvc.perform(get("/api/alm/issues/cards").param("keys", "alm-3", "ALM-1")
                        .with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(keysOf(JSON.readTree(body))).containsExactly("ALM-3", "ALM-1");
    }

    @Test
    void 빈_입력은_빈_배열이다() throws Exception {
        mvc.perform(get("/api/alm/issues/cards").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(cards("").size()).isZero();
        assertThat(cards("???,  ").size()).isZero();
    }

    @Test
    void 유효_키가_50개를_넘으면_400() throws Exception {
        String fifty = IntStream.rangeClosed(1, 50).mapToObj(i -> "ALM-" + i).collect(Collectors.joining(","));
        mvc.perform(get("/api/alm/issues/cards").param("keys", fifty).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/alm/issues/cards").param("keys", fifty + ",ALM-51").with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("keys는 최대 50개"));
    }

    @Test
    void 없는_키와_권한_없는_프로젝트의_키는_조용히_빠진다() throws Exception {
        assertThat(keysOf(cards("ALM-99,ALM-1,NOPE-1"))).containsExactly("ALM-1");

        permissions.deny(1, platId);
        assertThat(keysOf(cards("PLAT-1,ALM-2"))).containsExactly("ALM-2");

        permissions.setAllowed(false);
        mvc.perform(get("/api/alm/issues/cards").param("keys", "PLAT-1,ALM-2").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 권한_판정의_org_불능은_빈_결과가_아니라_503이다() throws Exception {
        permissions.setUnavailable(true);
        mvc.perform(get("/api/alm/issues/cards").param("keys", "ALM-1").with(asUser(1, "Alice")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 보관된_이슈와_휴지통_프로젝트의_이슈는_빠진다() throws Exception {
        long alm3 = issues.findByKey("ALM-3").orElseThrow().getId();
        mvc.perform(post("/api/alm/issues/{id}/archive", alm3).with(asUser(1, "Alice")))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(delete("/api/alm/projects/{id}", platId).with(asUser(1, "Alice")))
                .andExpect(status().is2xxSuccessful());

        assertThat(keysOf(cards("ALM-3,PLAT-1,ALM-1"))).containsExactly("ALM-1");
    }

    @Test
    void 레지스트리_해석은_프로젝트의_커스텀_설정을_따르고_모르는_ID는_이름만_ID다() throws Exception {
        permissions.setGlobalAdmins(9);
        String created = mvc.perform(post("/api/alm/settings/statuses").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"검토 중\",\"categoryId\":\"inprogress\",\"icon\":\"flask\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String review = JSON.readTree(created).get("id").asText();

        mvc.perform(put("/api/alm/projects/{id}/settings/custom", platId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":true}")).andExpect(status().isOk());
        mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", platId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"statuses":[{"id":"todo","name":"할 일","category":"todo","order":1},
                                             {"id":"%s","name":"검토 중","category":"inprogress","order":2},
                                             {"id":"done","name":"완료","category":"done","order":3}],
                                 "transitions":[],
                                 "layout":{},"enabledTypes":["task","story","bug","epic","subtask"]}""".formatted(review)))
                .andExpect(status().isOk());
        create(platId, "검토할 일", "TASK", review, "MEDIUM", null, null); // PLAT-2
        // 레지스트리에서 사라진 옛 값 — 이름은 ID 그대로, 아이콘·색은 null
        jdbc.update("update issue set priority = 'ghost', issue_type = 'phantom' where issue_key = 'PLAT-1'");

        StatusCategory inprogress = categories.findById("inprogress").orElseThrow();
        mvc.perform(get("/api/alm/issues/cards").param("keys", "PLAT-2,PLAT-1").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status.id").value(review))
                .andExpect(jsonPath("$[0].status.name").value("검토 중"))
                .andExpect(jsonPath("$[0].status.kind").value(inprogress.getKind()))
                .andExpect(jsonPath("$[0].status.color").value(inprogress.getColor()))
                .andExpect(jsonPath("$[0].status.icon").value("flask"))
                .andExpect(jsonPath("$[1].priority.id").value("ghost"))
                .andExpect(jsonPath("$[1].priority.name").value("ghost"))
                .andExpect(jsonPath("$[1].priority.icon").value(nullValue()))
                .andExpect(jsonPath("$[1].priority.color").value(nullValue()))
                .andExpect(jsonPath("$[1].type.id").value("phantom"))
                .andExpect(jsonPath("$[1].type.name").value("phantom"))
                .andExpect(jsonPath("$[1].type.icon").value(nullValue()));
    }

    @Test
    void 담당자_이름은_한_번에_묻고_트랜잭션_밖에서_묻는다() throws Exception {
        directory.put(7, "Alice Kim", "alice@test.com", "ACTIVE");
        directory.put(8, "Bob Lee", "bob@test.com", "ACTIVE");
        JsonNode result = cards("ALM-1,ALM-3,PLAT-1");
        assertThat(result.get(0).get("assignee").get("displayName").asText()).isEqualTo("Alice Kim");
        assertThat(result.get(1).get("assignee").get("displayName").asText()).isEqualTo("Bob Lee");
        assertThat(result.get(2).get("assignee").get("displayName").asText()).isEqualTo("Alice Kim");

        // 계정 상태 게이트의 단건 조회(요청자 1)는 빼고 센다
        List<TestConfig.FakeMemberDirectory.Call> nameLookups = directory.calls().stream()
                .filter(call -> !call.ids().equals(List.of(1L))).toList();
        assertThat(nameLookups).hasSize(1);
        assertThat(nameLookups.get(0).ids()).containsExactlyInAnyOrder(7L, 8L);
        assertThat(nameLookups.get(0).inTransaction()).isFalse();
    }

    @Test
    void org가_이름을_못_읽어도_카드는_그대로이고_이름만_null이다() throws Exception {
        directory.put(7, "Alice Kim", "alice@test.com", "ACTIVE");
        // 가용성 장애가 아닌 실패 — 계정 게이트는 통과하고 이름 조회만 빈다
        directory.setFailed(true);
        mvc.perform(get("/api/alm/issues/cards").param("keys", "ALM-1").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("ALM-1"))
                .andExpect(jsonPath("$[0].assignee.id").value(7))
                .andExpect(jsonPath("$[0].assignee.displayName").value(nullValue()));

        // 가용성 장애 — HTTP로는 계정 게이트가 먼저 503을 내므로 서비스로 직접 확인한다
        directory.setFailed(false);
        directory.setUnavailable(true);
        List<IssueCardResponse> cards = search.cards(1, List.of("ALM-1,ALM-2"));
        assertThat(cards).extracting(IssueCardResponse::key).containsExactly("ALM-1", "ALM-2");
        assertThat(cards.get(0).assignee().id()).isEqualTo(7);
        assertThat(cards.get(0).assignee().displayName()).isNull();
    }
}
