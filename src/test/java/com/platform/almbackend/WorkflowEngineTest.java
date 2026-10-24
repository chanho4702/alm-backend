package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.event.domain.IssueTransitionedEvent;
import com.platform.almbackend.event.domain.IssueUpdatedEvent;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SprintRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 워크플로 엔진(2026-10-24 A1·A2·A4·C1~C5) — 해결 규칙이 모든 상태 변경 경로에서 같고, 전환 목록·조건·검증·
 * 화면·후처리·타입별 워크플로·보드 열 매핑이 계약대로 동작하는지.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@RecordApplicationEvents
class WorkflowEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALL_TYPES = "[\"task\",\"story\",\"bug\",\"epic\",\"subtask\"]";
    private static final String BASE_STATUSES =
            "{\"id\":\"todo\",\"name\":\"할 일\",\"category\":\"todo\",\"order\":1},"
                    + "{\"id\":\"inprogress\",\"name\":\"진행 중\",\"category\":\"inprogress\",\"order\":2},"
                    + "{\"id\":\"done\",\"name\":\"완료\",\"category\":\"done\",\"order\":3}";

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired ProjectSettingsRepository projectSettings;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired ApplicationEvents events;

    private long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projectSettings.deleteAllInBatch();
        projects.deleteAllInBatch();
        seeder.resetToDefaults();
        permissions.reset();
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"wf\",\"name\":\"워크플로\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
    }

    @AfterEach
    void restore() {
        permissions.reset();
        issues.deleteAllInBatch();
        seeder.resetToDefaults();
    }

    // ── A1 해결 규칙: 모든 경로 ──

    @Test
    void PUT으로_완료에_들어가면_해결이_완료로_채워지고_나가면_비워진다() throws Exception {
        JsonNode issue = createIssue("작업", "task", "todo", null);
        long id = issue.get("id").asLong();

        JsonNode done = json(update(id, "done", "{}", 1).andExpect(status().isOk()));
        assertThat(done.get("resolution").asText()).isEqualTo("DONE");
        mvc.perform(get("/api/alm/issues/{id}", id).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.resolution").value("DONE"))
                .andExpect(jsonPath("$.resolvedAt").value(notNullValue()));

        // 화면이 들고 있던 해결(DONE)을 그대로 실어 되돌려도 거부하지 않고 비운다(지정이 아니라 운반된 값이다)
        update(id, "todo", "{\"resolution\":\"DONE\"}", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()));
    }

    @Test
    void 완료가_아닌_이슈에_해결을_지정하면_400() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        update(id, "inprogress", "{\"resolution\":\"WONT_DO\"}", 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("완료된 이슈에만 해결을 설정할 수 있습니다"));
    }

    @Test
    void 완료_안에서_해결을_바꾸면_명시한_값이_이긴다() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        update(id, "done", "{\"resolution\":\"DUPLICATE\"}", 1).andExpect(jsonPath("$.resolution").value("DUPLICATE"));
        update(id, "done", "{\"resolution\":\"CANNOT_REPRODUCE\"}", 2)
                .andExpect(jsonPath("$.resolution").value("CANNOT_REPRODUCE"));
    }

    @Test
    void 보드_이동도_해결_규칙을_탄다() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        move(id, "done").andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("DONE"))
                .andExpect(jsonPath("$.resolvedAt").value(notNullValue()))
                // 보드 이동은 편집 폼의 값을 바꾸지 않으면 버전을 올리지 않는다(기존 규칙)
                .andExpect(jsonPath("$.version").value(1));
        move(id, "inprogress").andExpect(jsonPath("$.resolution").doesNotExist())
                .andExpect(jsonPath("$.resolvedAt").value(nullValue()));
    }

    @Test
    void 일괄_편집도_해결_규칙을_탄다() throws Exception {
        long a = createIssue("가", "task", "todo", null).get("id").asLong();
        long b = createIssue("나", "task", "todo", null).get("id").asLong();
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + a + "},{\"issueId\":" + b + "}],\"changes\":{\"status\":\"done\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.results[0].issue.resolution").value("DONE"))
                .andExpect(jsonPath("$.results[1].issue.resolution").value("DONE"));
        mvc.perform(post("/api/alm/issues/bulk").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"issueId\":" + a + "}],\"changes\":{\"status\":\"todo\"}}"))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].issue.resolution").doesNotExist());
    }

    @Test
    void 완료_상태로_만들면_해결이_완료다() throws Exception {
        JsonNode created = createIssue("작업", "task", "done", null);
        assertThat(created.get("resolution").asText()).isEqualTo("DONE");
        assertThat(created.get("resolvedAt").isNull()).isFalse();
    }

    @Test
    void AQL_resolved는_보드로_완료시킨_이슈를_찾는다() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        move(id, "done").andExpect(status().isOk());
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"resolved IS NOT EMPTY\",\"page\":0,\"size\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    // ── A2 가용 전환 ──

    @Test
    void 전환_목록이_빈_워크플로는_다른_모든_상태로_가는_암묵_전환을_준다() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        mvc.perform(get("/api/alm/issues/{id}/transitions", id).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.typeWorkflow").value(false))
                .andExpect(jsonPath("$.transitions.length()").value(2))
                .andExpect(jsonPath("$.transitions[*].id").value(containsInAnyOrder("implicit:inprogress", "implicit:done")))
                .andExpect(jsonPath("$.transitions[0].isGlobal").value(true))
                .andExpect(jsonPath("$.transitions[0].available").value(true))
                .andExpect(jsonPath("$.transitions[0].hasScreen").value(false));
        // 암묵 전환도 실행할 수 있다
        mvc.perform(post("/api/alm/issues/{id}/transitions", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transitionId\":\"implicit:done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.resolution").value("DONE"));
    }

    @Test
    void 편집_권한이_없으면_전환은_보이되_모두_비활성이고_실행은_403() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        permissions.grantAdminOnly(1, projectId); // 영향 없음 — 아래는 EDIT 자체를 막는다
        permissions.setAllowed(false);
        // VIEW도 막히므로 목록은 403 — 권한 없음은 403, 규칙 거부는 400으로 갈린다
        mvc.perform(get("/api/alm/issues/{id}/transitions", id).with(asUser(5, "Eve")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/issues/{id}/transitions", id).with(asUser(5, "Eve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transitionId\":\"implicit:done\"}"))
                .andExpect(status().isForbidden());
    }

    // ── C1~C3 조건·검증·화면·후처리 ──

    private static final String RULED_TRANSITIONS = "["
            + "{\"id\":\"t-start\",\"name\":\"시작\",\"from\":[\"todo\"],\"to\":\"inprogress\","
            + "\"postFunctions\":[{\"type\":\"assignToActor\"}]},"
            + "{\"id\":\"t-done\",\"name\":\"끝내기\",\"from\":[\"inprogress\"],\"to\":\"done\","
            + "\"conditions\":[{\"type\":\"assignee\"}],\"validators\":[\"dueDate\"],"
            + "\"screenFields\":[\"resolution\",\"comment\",\"dueDate\"]},"
            + "{\"id\":\"t-reset\",\"name\":\"처음으로\",\"from\":[],\"to\":\"todo\","
            + "\"conditions\":[{\"type\":\"role\",\"role\":\"ADMIN\"}],\"postFunctions\":[{\"type\":\"clearAssignee\"}]}"
            + "]";

    @Test
    void 조건_검증_화면_후처리가_계약대로_동작한다() throws Exception {
        useCustomBody(body(BASE_STATUSES, RULED_TRANSITIONS, ""));
        permissions.grantAdminOnly(1, projectId);
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();

        // todo에서는 시작(t-start)만 — 전역 t-reset은 대상이 지금 상태라 빠진다
        mvc.perform(get("/api/alm/issues/{id}/transitions", id).with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.transitions.length()").value(1))
                .andExpect(jsonPath("$.transitions[0].id").value("t-start"))
                .andExpect(jsonPath("$.transitions[0].name").value("시작"))
                .andExpect(jsonPath("$.transitions[0].toName").value("진행 중"))
                .andExpect(jsonPath("$.transitions[0].toKind").value("active"));

        // 후처리: 담당자를 실행자로 — 편집 폼 값이 바뀌었으니 버전이 오른다
        mvc.perform(post("/api/alm/issues/{id}/transitions", id).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"transitionId\":\"t-start\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("inprogress"))
                .andExpect(jsonPath("$.assigneeId").value(2))
                .andExpect(jsonPath("$.version").value(2));

        // 담당자가 아닌 사람: t-done 비활성 + 이유, t-reset은 관리자만
        mvc.perform(get("/api/alm/issues/{id}/transitions", id).with(asUser(3, "Carol")))
                .andExpect(jsonPath("$.transitions.length()").value(2))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-done')].available").value(false))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-done')].unavailableReason").value("담당자만 이 전환을 할 수 있습니다"))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-done')].hasScreen").value(true))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-done')].missingFields[0]").value("dueDate"))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-reset')].available").value(false))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-reset')].unavailableReason").value("프로젝트 관리자만 이 전환을 할 수 있습니다"))
                .andExpect(jsonPath("$.transitions[?(@.id=='t-reset')].isGlobal").value(true));

        // 같은 규칙이 보드 이동·PUT에도 걸린다 — 조건 거부는 403이 아니라 400
        move(id, "done", 3).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("담당자만 이 전환을 할 수 있습니다"));

        // 검증: 마감일 필수
        transition(id, 2, "{\"transitionId\":\"t-done\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("마감일은 필수입니다"));
        // 같은 검증이 보드 이동에도
        move(id, "done", 2).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("마감일은 필수입니다"));
        // 화면에 없는 칸은 거부
        transition(id, 2, "{\"transitionId\":\"t-done\",\"fields\":{\"assigneeId\":7,\"dueDate\":\"2026-11-01\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 전환 화면에 없는 칸입니다: 담당자"));

        // 화면 값(해결·마감일)과 코멘트를 한 요청으로
        transition(id, 2, "{\"transitionId\":\"t-done\",\"fields\":{\"resolution\":\"WONT_DO\",\"dueDate\":\"2026-11-01\"},"
                + "\"comment\":\"범위에서 뺍니다\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.resolution").value("WONT_DO"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-01"));
        mvc.perform(get("/api/alm/issues/{id}/comments", id).with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].body").value("범위에서 뺍니다"));

        // 관리자만 되돌릴 수 있다 — 후처리로 담당자가 비고, 완료에서 나가 해결도 비워진다
        transition(id, 3, "{\"transitionId\":\"t-reset\"}").andExpect(status().isBadRequest());
        transition(id, 1, "{\"transitionId\":\"t-reset\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.assigneeId").value(nullValue()))
                .andExpect(jsonPath("$.resolution").doesNotExist());

        // 정의되지 않은 이동(todo → done)은 기존 문구 그대로
        move(id, "done", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("할 일에서 완료로 옮길 수 없습니다"));
        // 지금 상태에서 못 하는 전환 id
        transition(id, 1, "{\"transitionId\":\"t-done\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("지금 상태(할 일)에서는 할 수 없는 전환입니다: 끝내기"));
        transition(id, 1, "{\"transitionId\":\"nope\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("없는 전환입니다: nope"));
    }

    /**
     * Codex P2: id 없는 전환 여럿이 같은 대상으로 가면 모두 합성 id {@code implicit:<대상>}을 받는다. 실행 조회가 출발 상태를
     * 보기 전에 첫 것을 고르면, 목록에서 "할 수 있음"으로 보인 전환이 실행에서 거부됐다. 목록(evaluate)이 준 id는 같은 규칙
     * (출발 상태 → 조건)으로 실행돼야 한다.
     */
    @Test
    void id_없는_전환들이_같은_대상이면_실행도_지금_출발_상태의_전환을_쓴다() throws Exception {
        useCustomBody(body(BASE_STATUSES,
                "[{\"from\":[\"todo\"],\"to\":\"done\",\"postFunctions\":[{\"type\":\"clearAssignee\"}]},"
                        + "{\"from\":[\"inprogress\"],\"to\":\"done\",\"postFunctions\":[{\"type\":\"assignToActor\"}]},"
                        + "{\"from\":[],\"to\":\"todo\"},"
                        + "{\"id\":\"t-go\",\"from\":[\"todo\"],\"to\":\"inprogress\"}]", ""));
        long id = createIssue("작업", "task", "inprogress", null).get("id").asLong();

        String listed = mvc.perform(get("/api/alm/issues/{id}/transitions", id).with(asUser(2, "Bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transitions.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        JsonNode toDone = null;
        for (JsonNode t : JSON.readTree(listed).get("transitions")) {
            if ("done".equals(t.get("to").asText())) toDone = t;
        }
        assertThat(toDone).isNotNull();
        assertThat(toDone.get("id").asText()).isEqualTo("implicit:done");
        assertThat(toDone.get("available").asBoolean()).isTrue();

        // 목록이 준 id 그대로 실행 — 진행 중에서 출발하는 전환(후처리: 담당자를 실행자로)이 쓰여야 한다
        transition(id, 2, "{\"transitionId\":\"implicit:done\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.assigneeId").value(2));

        // 출발 상태가 맞는 전환이 하나도 없으면 기존 문구 그대로
        transition(id, 2, "{\"transitionId\":\"t-go\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("지금 상태(완료)에서는 할 수 없는 전환입니다: 진행 중"));
    }

    /** 목록이 "할 수 있음"으로 준 전환 id는 모두 실행 조회에서도 같은 전환으로 통과한다(같은 규칙). */
    @Test
    void 목록의_가능한_전환_id는_모두_실행된다() throws Exception {
        String transitions = "[{\"from\":[\"todo\"],\"to\":\"done\"},{\"from\":[\"inprogress\"],\"to\":\"done\"},"
                + "{\"from\":[\"done\"],\"to\":\"inprogress\"},{\"from\":[],\"to\":\"todo\"}]";
        useCustomBody(body(BASE_STATUSES, transitions, ""));
        for (String start : List.of("todo", "inprogress", "done")) {
            long probe = createIssue("목록", "task", start, null).get("id").asLong();
            String listed = mvc.perform(get("/api/alm/issues/{id}/transitions", probe).with(asUser(1, "Alice")))
                    .andReturn().getResponse().getContentAsString();
            for (JsonNode t : JSON.readTree(listed).get("transitions")) {
                if (!t.get("available").asBoolean()) continue;
                long fresh = createIssue("실행", "task", start, null).get("id").asLong();
                transition(fresh, 1, "{\"transitionId\":\"" + t.get("id").asText() + "\"}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value(t.get("to").asText()));
            }
        }
    }

    @Test
    void 전환_실행의_expectedVersion이_어긋나면_409() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        transition(id, 1, "{\"transitionId\":\"implicit:inprogress\",\"expectedVersion\":7}")
                .andExpect(status().isConflict());
    }

    @Test
    void 하위_이슈가_모두_완료여야_하는_조건() throws Exception {
        useCustomBody(body(BASE_STATUSES, "[{\"id\":\"t-close\",\"from\":[],\"to\":\"done\","
                + "\"conditions\":[{\"type\":\"subtasksDone\"}]},{\"id\":\"t-open\",\"from\":[],\"to\":\"todo\"}]", ""));
        long parent = createIssue("상위", "story", "todo", null).get("id").asLong();
        long child = createIssue("하위", "subtask", "todo", parent).get("id").asLong();

        mvc.perform(get("/api/alm/issues/{id}/transitions", parent).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.transitions[0].id").value("t-close"))
                // 이름이 빈 전환은 대상 상태 이름(지라 기본 워크플로처럼)
                .andExpect(jsonPath("$.transitions[0].name").value("완료"))
                .andExpect(jsonPath("$.transitions[0].available").value(false))
                .andExpect(jsonPath("$.transitions[0].unavailableReason").value("완료되지 않은 하위 이슈가 1개 있습니다"));
        update(parent, "done", "{}", 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("완료되지 않은 하위 이슈가 1개 있습니다"));

        transition(child, 1, "{\"transitionId\":\"t-close\"}").andExpect(status().isOk());
        transition(parent, 1, "{\"transitionId\":\"t-close\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("DONE"));
    }

    @Test
    void 해결_설정_후처리는_완료_전환의_기본_해결을_바꾼다() throws Exception {
        useCustomBody(body(BASE_STATUSES, "[{\"id\":\"t-wont\",\"name\":\"하지 않음\",\"from\":[],\"to\":\"done\","
                + "\"postFunctions\":[{\"type\":\"setResolution\",\"value\":\"WONT_DO\"}]},"
                + "{\"id\":\"t-open\",\"from\":[],\"to\":\"todo\"}]", ""));
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        move(id, "done").andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("WONT_DO"));
    }

    @Test
    void 전환_규칙의_어휘를_벗어난_본문은_저장_거부() throws Exception {
        customize();
        putBody(body(BASE_STATUSES, "[{\"id\":\"x\",\"from\":[],\"to\":\"done\",\"conditions\":[{\"type\":\"moon\"}]}]", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("없는 전환 조건입니다: moon"));
        putBody(body(BASE_STATUSES, "[{\"id\":\"x\",\"from\":[],\"to\":\"inprogress\",\"screenFields\":[\"resolution\"]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("해결 칸은 완료 상태로 가는 전환에만 넣을 수 있습니다"));
        putBody(body(BASE_STATUSES, "[{\"id\":\"x\",\"from\":[],\"to\":\"done\",\"validators\":[\"attachments\"]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("전환 검증에 쓸 수 없는 필드입니다: attachments"));
        putBody(body(BASE_STATUSES, "[{\"id\":\"x\",\"from\":[],\"to\":\"todo\","
                + "\"postFunctions\":[{\"type\":\"setResolution\",\"value\":\"DONE\"}]}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("해결 설정 후처리는 완료 상태로 가는 전환에만 쓸 수 있습니다"));
        putBody(body(BASE_STATUSES, "[{\"id\":\"x\",\"from\":[\"ghost\"],\"to\":\"done\"}]", ""))
                .andExpect(status().isBadRequest());
        // 저장된 규칙은 응답에 그대로 돌아온다(프론트 편집기가 다시 읽는다)
        putBody(body(BASE_STATUSES, RULED_TRANSITIONS, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.transitions[1].conditions[0].type").value("assignee"))
                .andExpect(jsonPath("$.body.transitions[1].validators[0]").value("dueDate"))
                .andExpect(jsonPath("$.body.transitions[1].screenFields.length()").value(3))
                .andExpect(jsonPath("$.body.transitions[0].postFunctions[0].type").value("assignToActor"));
    }

    // ── 상태 삭제 저장 — 서버도 지운 상태의 잔여 참조를 정리한다(2026-10-24, 프론트 pruneWorkflowRefs와 같은 규칙) ──

    @Test
    void 상태를_지우며_저장하면_그_상태를_가리키는_전환_출발_타입_워크플로_배치를_덜고_규칙은_보존한다() throws Exception {
        String withReview = BASE_STATUSES + ",{\"id\":\"review\",\"name\":\"검토\",\"category\":\"inprogress\",\"order\":4}";
        String transitions = "[{\"id\":\"t-review\",\"from\":[\"inprogress\"],\"to\":\"review\"},"
                + "{\"id\":\"t-done\",\"from\":[\"inprogress\",\"review\"],\"to\":\"done\","
                + "\"conditions\":[{\"type\":\"assignee\"}],\"validators\":[\"assignee\"]},"
                + "{\"id\":\"t-from-review\",\"from\":[\"review\"],\"to\":\"todo\"},"
                + "{\"id\":\"t-any\",\"from\":[],\"to\":\"todo\"}]";
        String typed = ",\"workflowsByType\":{\"bug\":{\"statuses\":[\"todo\",\"inprogress\",\"review\",\"done\"],"
                + "\"transitions\":[{\"id\":\"b1\",\"from\":[\"todo\"],\"to\":\"review\"},{\"id\":\"b2\",\"from\":[],\"to\":\"done\"}]}}";
        useCustomBody("{\"statuses\":[" + withReview + "],\"transitions\":" + transitions
                + ",\"layout\":{\"review\":{\"x\":1,\"y\":2},\"todo\":{\"x\":0,\"y\":0}},\"enabledTypes\":" + ALL_TYPES + typed + "}");

        // 검토를 지우고 나머지는 옛 본문 그대로 보낸다(API 직접 호출 — 프론트 정리를 거치지 않음)
        String bodyText = putBody("{\"statuses\":[" + BASE_STATUSES + "],\"transitions\":" + transitions
                        + ",\"layout\":{\"review\":{\"x\":1,\"y\":2},\"todo\":{\"x\":0,\"y\":0}},\"enabledTypes\":" + ALL_TYPES + typed + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode saved = JSON.readTree(bodyText).get("body");
        List<String> ids = new java.util.ArrayList<>();
        saved.get("transitions").forEach(t -> ids.add(t.get("id").asText()));
        assertThat(ids).containsExactly("t-done", "t-any");        // 대상이 검토인 것·출발을 전부 잃은 것은 빠진다
        JsonNode done = saved.get("transitions").get(0);
        assertThat(done.get("from").toString()).isEqualTo("[\"inprogress\"]");
        assertThat(done.get("conditions").get(0).get("type").asText()).isEqualTo("assignee"); // 규칙 보존
        assertThat(done.get("validators").toString()).isEqualTo("[\"assignee\"]");
        JsonNode bug = saved.at("/workflowsByType/bug");
        assertThat(bug.get("statuses").toString()).isEqualTo("[\"todo\",\"inprogress\",\"done\"]");
        assertThat(bug.get("transitions")).hasSize(1);
        assertThat(saved.get("layout").has("review")).isFalse();

        // 처음부터 없던 상태(오타)는 정리 대상이 아니다 — 여전히 400
        putBody(body(BASE_STATUSES, "[{\"id\":\"x\",\"from\":[],\"to\":\"reviw\"}]", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("전환의 대상 상태가 워크플로에 없습니다"));
    }

    // ── C5 이슈 타입별 워크플로 ──

    /** 기본 3상태 + 진행 중 의미의 "검토" — 타입 워크플로가 기본과 다른 부분집합을 쓰게 한다 */
    private static final String WITH_REVIEW = BASE_STATUSES
            + ",{\"id\":\"review\",\"name\":\"검토\",\"category\":\"inprogress\",\"order\":4}";

    @Test
    void 타입별_워크플로가_있으면_그_타입은_그것만_다른_타입은_기본을_따른다() throws Exception {
        useCustomBody(body(WITH_REVIEW, "[]",
                ",\"workflowsByType\":{\"bug\":{\"statuses\":[\"todo\",\"review\",\"done\"],\"transitions\":[]}}"));
        long bug = createIssue("버그", "bug", null, null).get("id").asLong();
        long task = createIssue("작업", "task", null, null).get("id").asLong();

        mvc.perform(get("/api/alm/issues/{id}/transitions", bug).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.typeWorkflow").value(true))
                .andExpect(jsonPath("$.transitions.length()").value(2))
                .andExpect(jsonPath("$.transitions[*].to").value(containsInAnyOrder("review", "done")));
        // 다른 타입은 기본 워크플로(폴백) — 상태 4개 중 지금 상태를 뺀 3개
        mvc.perform(get("/api/alm/issues/{id}/transitions", task).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.typeWorkflow").value(false))
                .andExpect(jsonPath("$.transitions.length()").value(3));

        update(bug, "inprogress", "{}", 1, "bug").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 이슈 타입의 워크플로에 없는 상태입니다: 진행 중"));
        createIssueRaw("버그2", "bug", "inprogress").andExpect(status().isBadRequest());
        update(task, "inprogress", "{}", 1, "task").andExpect(status().isOk());

        // 진행 중인 작업을 버그로 바꾸면(상태는 그대로 보냄) 버그 워크플로로 옮긴다:
        // 같은 카테고리(진행 중)의 첫 상태 = 검토. 화면이 대상 상태를 골라 보내면 그 값을 쓴다
        update(task, "inprogress", "{}", 2, "bug").andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("bug"))
                .andExpect(jsonPath("$.status").value("review"));
        long task2 = createIssue("작업2", "task", "inprogress", null).get("id").asLong();
        update(task2, "done", "{}", 1, "bug").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.resolution").value("DONE"));
    }

    @Test
    void 타입별_워크플로를_바꾸면_그_타입의_이슈만_새_워크플로로_이관한다() throws Exception {
        String closed = BASE_STATUSES + ",{\"id\":\"closed\",\"name\":\"닫힘\",\"category\":\"done\",\"order\":4}";
        useCustomBody(body(closed, "[]", ""));
        long bug = createIssue("버그", "bug", "done", null).get("id").asLong();
        long task = createIssue("작업", "task", "done", null).get("id").asLong();

        putBody(body(closed, "[]",
                ",\"workflowsByType\":{\"bug\":{\"statuses\":[\"todo\",\"inprogress\",\"closed\"]}}"))
                .andExpect(status().isOk());
        // 버그: done이 버그 워크플로에 없다 → 같은 카테고리(완료)의 첫 상태 closed. 완료 → 완료라 해결은 유지
        mvc.perform(get("/api/alm/issues/{id}", bug).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.status").value("closed"))
                .andExpect(jsonPath("$.resolution").value("DONE"));
        mvc.perform(get("/api/alm/issues/{id}", task).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.status").value("done"));

        // 타입 워크플로도 의미(할 일/진행 중/완료)마다 상태가 필요하다
        putBody(body(closed, "[]", ",\"workflowsByType\":{\"bug\":{\"statuses\":[\"todo\",\"inprogress\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("버그 워크플로에도 카테고리(할 일/진행 중/완료)마다 상태가 최소 1개 필요합니다"));
        putBody(body(closed, "[]", ",\"workflowsByType\":{\"ghost\":{\"statuses\":[]}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 타입_워크플로의_전환을_생략하면_기본_전환_중_그_상태들_사이의_것만_쓴다() throws Exception {
        useCustomBody(body(WITH_REVIEW,
                "[{\"id\":\"a\",\"from\":[\"todo\"],\"to\":\"inprogress\"},{\"id\":\"b\",\"from\":[\"todo\",\"inprogress\"],\"to\":\"done\"},"
                        + "{\"id\":\"c\",\"from\":[],\"to\":\"todo\"},{\"id\":\"d\",\"from\":[\"todo\"],\"to\":\"review\"}]",
                ",\"workflowsByType\":{\"bug\":{\"statuses\":[\"todo\",\"review\",\"done\"]}}"));
        long bug = createIssue("버그", "bug", null, null).get("id").asLong();
        // a(→진행 중)는 버그 워크플로 밖이라 빠지고, c(→할 일)는 지금 상태라 빠진다
        mvc.perform(get("/api/alm/issues/{id}/transitions", bug).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.transitions.length()").value(2))
                .andExpect(jsonPath("$.transitions[*].id").value(containsInAnyOrder("b", "d")));
    }

    // ── C4 보드 열 매핑 ──

    @Test
    void 보드_열에_여러_상태를_넣고_미매핑_상태를_돌려준다() throws Exception {
        String review = BASE_STATUSES + ",{\"id\":\"review\",\"name\":\"검토\",\"category\":\"inprogress\",\"order\":4}";
        useCustomBody(body(review, "[]", ""));
        long boardId = kanbanBoard();
        long todoIssue = createIssue("할 일 이슈", "task", "todo", null).get("id").asLong();
        createIssue("검토 이슈", "task", "review", null);

        // 구 형식: 열 = 상태 1:1, resolvedColumns는 워크플로 상태 전부
        mvc.perform(get("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.columnMode").value("status"))
                .andExpect(jsonPath("$.resolvedColumns.length()").value(4))
                .andExpect(jsonPath("$.resolvedColumns[3].statuses[0]").value("review"))
                .andExpect(jsonPath("$.unmappedStatuses.length()").value(0));

        mvc.perform(put("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"name\":\"진행\",\"statuses\":[\"inprogress\",\"review\"],\"wipLimit\":3},"
                                + "{\"name\":\"끝\",\"statuses\":[\"done\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnMode").value("mapped"))
                .andExpect(jsonPath("$.columns[0].id").value(notNullValue()))
                .andExpect(jsonPath("$.columns[0].status").value("inprogress"))
                .andExpect(jsonPath("$.resolvedColumns[0].statuses.length()").value(2))
                .andExpect(jsonPath("$.resolvedColumns[0].wipLimit").value(3))
                .andExpect(jsonPath("$.unmappedStatuses[0]").value("todo"));
        // 미매핑 상태의 이슈는 보드에 나오지 않는다
        mvc.perform(get("/api/alm/boards/{id}/issues", boardId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("review"));
        assertThat(todoIssue).isPositive();

        mvc.perform(put("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"name\":\"가\",\"statuses\":[\"done\"]},{\"name\":\"나\",\"statuses\":[\"done\"]}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상태 하나는 한 열에만 넣을 수 있습니다: done"));
        mvc.perform(put("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"name\":\"가\",\"statuses\":[\"ghost\"]}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"name\":\"가\",\"statuses\":[\"done\"]},{\"name\":\"나\",\"status\":\"todo\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 구_형식_열_덮어쓰기는_그대로_저장되고_resolvedColumns에_반영된다() throws Exception {
        long boardId = kanbanBoard();
        mvc.perform(put("/api/alm/boards/{id}", boardId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columns\":[{\"status\":\"done\",\"name\":\"배포됨\",\"wipLimit\":null}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnMode").value("status"))
                .andExpect(jsonPath("$.columns.length()").value(1))
                .andExpect(jsonPath("$.columns[0].status").value("done"))
                .andExpect(jsonPath("$.resolvedColumns.length()").value(3))
                .andExpect(jsonPath("$.resolvedColumns[2].name").value("배포됨"))
                .andExpect(jsonPath("$.resolvedColumns[0].name").value("할 일"));
    }

    // ── 도메인 이벤트 ──

    @Test
    void 상태가_바뀌면_전환_이벤트와_변경_이벤트가_발행된다() throws Exception {
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        events.clear();
        move(id, "done").andExpect(status().isOk());

        List<IssueTransitionedEvent> transitioned = events.stream(IssueTransitionedEvent.class).toList();
        assertThat(transitioned).hasSize(1);
        IssueTransitionedEvent t = transitioned.get(0);
        assertThat(t.issueId()).isEqualTo(id);
        assertThat(t.fromStatus()).isEqualTo("todo");
        assertThat(t.toStatus()).isEqualTo("done");
        assertThat(t.toKind()).isEqualTo("complete");
        assertThat(t.transitionId()).isNull();
        assertThat(t.actorId()).isEqualTo(1);
        List<IssueUpdatedEvent> updated = events.stream(IssueUpdatedEvent.class).toList();
        assertThat(updated).hasSize(1);
        assertThat(updated.get(0).changedFields()).containsExactlyInAnyOrder("status", "resolution");

        // 같은 열 안 순서만 바꾸면 아무것도 나가지 않는다
        events.clear();
        move(id, "done").andExpect(status().isOk());
        assertThat(events.stream(IssueTransitionedEvent.class).count()).isZero();
        assertThat(events.stream(IssueUpdatedEvent.class).count()).isZero();
    }

    @Test
    void 전환_실행은_전환_id를_싣는다() throws Exception {
        useCustomBody(body(BASE_STATUSES, RULED_TRANSITIONS, ""));
        long id = createIssue("작업", "task", "todo", null).get("id").asLong();
        events.clear();
        transition(id, 2, "{\"transitionId\":\"t-start\"}").andExpect(status().isOk());
        IssueTransitionedEvent t = events.stream(IssueTransitionedEvent.class).findFirst().orElseThrow();
        assertThat(t.transitionId()).isEqualTo("t-start");
        assertThat(events.stream(IssueUpdatedEvent.class).findFirst().orElseThrow().changedFields())
                .containsExactlyInAnyOrder("status", "assignee");
    }

    // ── 도우미 ──

    private static String body(String statuses, String transitions, String extra) {
        return "{\"statuses\":[" + statuses + "],\"transitions\":" + transitions
                + ",\"layout\":{},\"enabledTypes\":" + ALL_TYPES + extra + "}";
    }

    private void customize() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":true}"))
                .andExpect(status().isOk());
    }

    private ResultActions putBody(String body) throws Exception {
        return mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void useCustomBody(String body) throws Exception {
        customize();
        putBody(body).andExpect(status().isOk());
    }

    private long kanbanBoard() throws Exception {
        String created = mvc.perform(post("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"칸반\",\"type\":\"kanban\"}"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(created).get("id").asLong();
    }

    private ResultActions createIssueRaw(String title, String type, String status) throws Exception {
        return mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"" + type + "\","
                        + (status == null ? "" : "\"status\":\"" + status + "\",")
                        + "\"priority\":\"MEDIUM\",\"details\":{}}"));
    }

    private JsonNode createIssue(String title, String type, String status, Long parentId) throws Exception {
        String details = parentId == null ? "{}" : "{\"parentId\":" + parentId + "}";
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"" + type + "\","
                                + (status == null ? "" : "\"status\":\"" + status + "\",")
                                + "\"priority\":\"MEDIUM\",\"details\":" + details + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private ResultActions update(long id, String status, String details, int expectedVersion) throws Exception {
        return update(id, status, details, expectedVersion, "task");
    }

    private ResultActions update(long id, String status, String details, int expectedVersion, String type) throws Exception {
        return mvc.perform(put("/api/alm/issues/{id}", id).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"" + type + "\",\"status\":\"" + status
                        + "\",\"priority\":\"MEDIUM\",\"details\":" + details + ",\"expectedVersion\":" + expectedVersion + "}"));
    }

    private ResultActions move(long id, String status) throws Exception {
        return move(id, status, 1);
    }

    private ResultActions move(long id, String status, long userId) throws Exception {
        return mvc.perform(post("/api/alm/issues/{id}/move", id).with(asUser(userId, "U" + userId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions transition(long id, long userId, String body) throws Exception {
        return mvc.perform(post("/api/alm/issues/{id}/transitions", id).with(asUser(userId, "U" + userId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
