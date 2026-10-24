package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.automation.AutomationScheduler;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자동화 규칙(V36·V37) — 트리거(생성·전환·필드 변경·예약)·조건(AQL)·액션, 그리고 안전장치(규칙 연쇄 기본 차단·루프·깊이·
 * 실행 상한·연속 실패 비활성·원자성)와 실행 이력. 테스트는 실행기를 동기(async=false)로 돌려 결과를 곧장 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class AutomationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 규칙을 만드는 프로젝트 관리자 — 실행 주체 */
    private static final long ADMIN = 3;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired AutomationScheduler scheduler;
    @Autowired com.platform.almbackend.repository.AutomationRuleRepository rules;
    @Autowired com.platform.almbackend.repository.AutomationRunRepository runRepository;
    @Autowired com.platform.almbackend.automation.AutomationEngine engine;
    @Autowired com.platform.almbackend.search.aql.AqlSearchService aql;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    MockMvc mvc;
    long projectId;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        TestData.clear(jdbc);
        projectId = TestData.project(mvc, "AU");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    // ── 트리거 · 조건 · 액션 ──

    @Test
    void 생성_트리거는_조건에_맞는_이슈에만_액션을_실행하고_이력을_남긴다() throws Exception {
        long ruleId = rule("""
                {"name":"버그는 높게","trigger":{"type":"ISSUE_CREATED"},"condition":"type = bug",
                 "actions":[{"type":"SET_FIELD","field":"priority","value":"HIGH"},
                            {"type":"COMMENT","body":"자동 분류됨"},
                            {"type":"NOTIFY","userIds":[5],"message":"새 버그가 들어왔습니다"}]}""");

        long bug = issue("화면 깨짐", "bug");
        long task = issue("문서 작업", "task");

        JsonNode bugNow = read(bug);
        assertThat(bugNow.get("priority").asText()).isEqualTo("high");
        assertThat(read(task).get("priority").asText()).isEqualTo("medium");
        mvc.perform(get("/api/alm/issues/{id}/comments", bug).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].body").value("자동 분류됨"))
                .andExpect(jsonPath("$[0].authorId").value(ADMIN));
        assertThat(jdbc.queryForObject("select count(*) from notification where user_id = 5 and type = 'AUTOMATION'"
                + " and detail = '새 버그가 들어왔습니다'", Long.class)).isEqualTo(1);

        // 조건에 안 맞은 실행은 남기지 않는다
        mvc.perform(get("/api/alm/automation/rules/{id}/runs", ruleId).with(asUser(ADMIN, "Admin")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$[0].issueId").value(bug))
                .andExpect(jsonPath("$[0].depth").value(1))
                .andExpect(jsonPath("$[0].actionsRun").value(3));
        mvc.perform(get("/api/alm/automation/rules/{id}", ruleId).with(asUser(ADMIN, "Admin")))
                .andExpect(jsonPath("$.actorId").value(ADMIN))
                .andExpect(jsonPath("$.actions[0].value").value("high")) // 우선순위는 레지스트리 id로 정규화해 저장
                .andExpect(jsonPath("$.lastRunAt").isNotEmpty());
    }

    @Test
    void 전환_트리거는_도착_상태로_고르고_필드_변경_트리거는_지켜보는_필드만_본다() throws Exception {
        rule("""
                {"name":"끝나면 막힘 해제","trigger":{"type":"ISSUE_TRANSITIONED","toStatuses":["done"]},
                 "actions":[{"type":"SET_FIELD","field":"flagged","value":false}]}""");
        rule("""
                {"name":"높으면 urgent","trigger":{"type":"FIELD_CHANGED","fields":["priority"]},"condition":"priority >= high",
                 "actions":[{"type":"LABELS","add":["urgent"]},{"type":"ASSIGN","assignee":"reporter"}]}""");

        long id = issue("막힌 일", "task");
        mvc.perform(put("/api/alm/issues/{id}/flag", id).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"flagged\":true}")).andExpect(status().isOk());
        move(id, "inprogress");
        assertThat(read(id).get("flagged").asBoolean()).isTrue(); // done이 아니면 그대로
        move(id, "done");
        assertThat(read(id).get("flagged").asBoolean()).isFalse();

        long other = issue("급한 일", "task");
        edit(other, "{\"priority\":\"low\"}");
        assertThat(read(other).get("labels")).isEmpty(); // 바뀌었지만 조건(>= high)에 안 맞는다
        edit(other, "{\"priority\":\"highest\"}");
        JsonNode now = read(other);
        assertThat(now.get("labels").toString()).contains("urgent");
        assertThat(now.get("assigneeId").asLong()).isEqualTo(1); // 보고자
    }

    @Test
    void 전환_액션은_워크플로_엔진의_조건_후처리_해결_규칙을_그대로_탄다() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":true}")).andExpect(status().isOk());
        mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"statuses":[{"id":"todo","name":"할 일","category":"todo","order":1},
                                             {"id":"inprogress","name":"진행 중","category":"inprogress","order":2},
                                             {"id":"done","name":"완료","category":"done","order":3}],
                                 "transitions":[{"id":"t-start","name":"시작","from":["todo"],"to":"inprogress",
                                                 "postFunctions":[{"type":"assignToActor"}]},
                                                {"id":"t-done","name":"끝내기","from":["inprogress"],"to":"done",
                                                 "conditions":[{"type":"assignee"}]}],
                                 "layout":{},"enabledTypes":["task","story","bug","epic","subtask"]}"""))
                .andExpect(status().isOk());
        rule("""
                {"name":"작업은 바로 끝","trigger":{"type":"ISSUE_CREATED"},"condition":"type = task",
                 "actions":[{"type":"TRANSITION","toStatus":"inprogress"},{"type":"TRANSITION","toStatus":"done"}]}""");
        long jump = rule("""
                {"name":"버그는 건너뛰기","trigger":{"type":"ISSUE_CREATED"},"condition":"type = bug",
                 "actions":[{"type":"TRANSITION","toStatus":"done"}]}""");

        long task = issue("작업", "task");
        JsonNode done = read(task);
        assertThat(done.get("status").asText()).isEqualTo("done");
        assertThat(done.get("assigneeId").asLong()).isEqualTo(ADMIN); // 시작 전환의 후처리(실행 주체를 담당자로)
        assertThat(done.get("resolution").asText()).isEqualTo("DONE"); // 해결 규칙

        long bug = issue("버그", "bug");
        assertThat(read(bug).get("status").asText()).isEqualTo("todo"); // 할 일 → 완료 전환은 없다
        assertThat(outcomes(jump)).containsExactly("FAILED");
    }

    // ── 안전장치 ──

    @Test
    void 규칙이_만든_변경은_기본적으로_다른_규칙을_깨우지_않는다() throws Exception {
        rule("""
                {"name":"우선순위→포인트","trigger":{"type":"FIELD_CHANGED","fields":["priority"]},
                 "actions":[{"type":"SET_FIELD","field":"storyPoints","value":8}]}""");
        rule("""
                {"name":"포인트→라벨","trigger":{"type":"FIELD_CHANGED","fields":["storyPoints"]},
                 "actions":[{"type":"LABELS","add":["sized"]}]}""");
        long id = issue("연쇄", "task");
        edit(id, "{\"priority\":\"high\"}");
        JsonNode now = read(id);
        assertThat(now.get("storyPoints").decimalValue()).isEqualByComparingTo("8");
        assertThat(now.get("labels")).isEmpty(); // 두 번째 규칙은 allowRuleTrigger가 꺼져 있다

        // 사람이 포인트를 바꾸면 두 번째 규칙이 돈다
        edit(id, "{}", "{\"storyPoints\":3}");
        assertThat(read(id).get("labels").toString()).contains("sized");
    }

    @Test
    void 핑퐁하는_두_규칙은_같은_연쇄의_같은_이슈에서_멈춘다() throws Exception {
        long a = rule("""
                {"name":"A","allowRuleTrigger":true,"trigger":{"type":"FIELD_CHANGED","fields":["priority"]},
                 "actions":[{"type":"SET_FIELD","field":"storyPoints","value":1}]}""");
        long b = rule("""
                {"name":"B","allowRuleTrigger":true,"trigger":{"type":"FIELD_CHANGED","fields":["storyPoints"]},
                 "actions":[{"type":"SET_FIELD","field":"priority","value":"low"}]}""");
        long id = issue("핑퐁", "task");
        edit(id, "{\"priority\":\"high\"}");

        // 차단은 B의 실행 안에서(연쇄 2단계) 먼저 기록되고, A 자신의 성공은 그 뒤에 기록된다
        assertThat(outcomes(a)).containsExactlyInAnyOrder("LOOP_BLOCKED", "SUCCESS");
        assertThat(outcomes(b)).containsExactly("SUCCESS");
        JsonNode now = read(id);
        assertThat(now.get("priority").asText()).isEqualTo("low");
        assertThat(now.get("storyPoints").decimalValue()).isEqualByComparingTo("1");
    }

    @Test
    void 연쇄는_깊이_상한에서_멈춘다() throws Exception {
        long r1 = rule(chain("R1", "priority", "{\"type\":\"SET_FIELD\",\"field\":\"storyPoints\",\"value\":2}"));
        long r2 = rule(chain("R2", "storyPoints", "{\"type\":\"SET_FIELD\",\"field\":\"startDate\",\"value\":\"2026-11-01\"}"));
        long r3 = rule(chain("R3", "startDate", "{\"type\":\"SET_FIELD\",\"field\":\"dueDate\",\"value\":\"2026-11-30\"}"));
        long r4 = rule(chain("R4", "dueDate", "{\"type\":\"LABELS\",\"add\":[\"deep\"]}"));
        long id = issue("깊은 연쇄", "task");
        edit(id, "{\"priority\":\"high\"}");

        assertThat(outcomes(r1)).containsExactly("SUCCESS");
        assertThat(outcomes(r2)).containsExactly("SUCCESS");
        assertThat(outcomes(r3)).containsExactly("SUCCESS");
        assertThat(outcomes(r4)).containsExactly("LOOP_BLOCKED");
        JsonNode now = read(id);
        assertThat(now.get("dueDate").asText()).isEqualTo("2026-11-30");
        assertThat(now.get("labels")).isEmpty();
        assertThat(jdbc.queryForObject("select message from automation_run where rule_id = ?", String.class, r4))
                .contains("3단계");
    }

    @Test
    void 실패한_실행은_액션을_모두_되돌리고_연속_실패가_쌓이면_규칙을_끈다() throws Exception {
        long ruleId = rule("""
                {"name":"권한 잃은 규칙","trigger":{"type":"ISSUE_CREATED"},
                 "actions":[{"type":"COMMENT","body":"먼저 남는 코멘트"},{"type":"SET_FIELD","field":"priority","value":"high"}]}""");
        // 실행 주체가 편집 권한을 잃었다 — 코멘트(VIEW)는 되지만 우선순위(EDIT)에서 실패한다
        permissions.denyAction(ADMIN, projectId, AlmAction.EDIT);
        for (int i = 0; i < 5; i++) issue("이슈 " + i, "task");

        assertThat(jdbc.queryForObject("select count(*) from issue_comment", Long.class)).isZero(); // 원자성
        List<String> outcomes = outcomes(ruleId);
        assertThat(outcomes).hasSize(5).containsOnly("FAILED");
        assertThat(jdbc.queryForObject("select message from automation_run where rule_id = ? order by id limit 1",
                String.class, ruleId)).startsWith("2번째 액션(SET_FIELD) 실패: ");
        Map<String, Object> rule = jdbc.queryForMap("select enabled, disabled_reason, consecutive_failures from automation_rule where id = ?", ruleId);
        assertThat(rule.get("enabled")).isEqualTo(false);
        assertThat((String) rule.get("disabled_reason")).isEqualTo("연속 5회 실패해 자동으로 꺼졌습니다");

        // 꺼진 규칙은 돌지 않는다
        issue("더", "task");
        assertThat(outcomes(ruleId)).hasSize(5);

        // 다시 켜서 저장하면 기록이 지워진다
        permissions.reset();
        mvc.perform(put("/api/alm/automation/rules/{id}", ruleId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name":"권한 잃은 규칙","enabled":true,"trigger":{"type":"ISSUE_CREATED"},
                                 "actions":[{"type":"COMMENT","body":"다시"}],"expectedVersion":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.disabledReason").doesNotExist())
                .andExpect(jsonPath("$.consecutiveFailures").value(0))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void 권한_서비스_불능은_실패로_남기되_자동_비활성에_세지_않는다() throws Exception {
        long ruleId = rule("""
                {"name":"규칙","trigger":{"type":"FIELD_CHANGED","fields":["flagged"]},
                 "actions":[{"type":"COMMENT","body":"막힘 변경"}]}""");
        long id = issue("이슈", "task");
        // 사람의 요청은 성공하고 커밋된 뒤, 규칙 실행 때 org가 죽었다고 흉내 낸다
        jdbc.update("update automation_rule set actor_id = 999 where id = ?", ruleId);
        permissions.setUnavailableFor(999);
        for (int i = 0; i < 6; i++) {
            mvc.perform(put("/api/alm/issues/{id}/flag", id).with(asUser(1, "Alice")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"flagged\":" + (i % 2 == 0) + "}")).andExpect(status().isOk());
        }
        assertThat(outcomes(ruleId)).hasSize(6).containsOnly("FAILED");
        assertThat(jdbc.queryForObject("select enabled from automation_rule where id = ?", Boolean.class, ruleId)).isTrue();
        assertThat(jdbc.queryForObject("select message from automation_run where rule_id = ? order by id limit 1",
                String.class, ruleId)).isEqualTo("권한 서비스에 연결할 수 없어 실행하지 못했습니다");
    }

    @Test
    void 분당_실행_상한을_넘으면_규칙을_끈다() throws Exception {
        long ruleId = rule("""
                {"name":"시끄러운 규칙","trigger":{"type":"ISSUE_CREATED"},"actions":[{"type":"COMMENT","body":"x"}]}""");
        Timestamp now = Timestamp.from(Instant.now());
        for (int i = 0; i < 30; i++) {
            jdbc.update("insert into automation_run (rule_id, project_id, trigger_type, outcome, depth, actions_run, started_at, finished_at)"
                    + " values (?, ?, 'ISSUE_CREATED', 'SUCCESS', 1, 1, ?, ?)", ruleId, projectId, now, now);
        }
        issue("넘침", "task");
        assertThat(outcomes(ruleId).get(0)).isEqualTo("RATE_LIMITED");
        Map<String, Object> rule = jdbc.queryForMap("select enabled, disabled_reason from automation_rule where id = ?", ruleId);
        assertThat(rule.get("enabled")).isEqualTo(false);
        assertThat((String) rule.get("disabled_reason")).isEqualTo("1분에 30회를 넘게 실행돼 자동으로 꺼졌습니다");
    }

    // ── 예약 ──

    @Test
    void 예약_규칙은_때가_되면_조건에_맞는_이슈마다_돌고_다음_시각을_잡는다() throws Exception {
        long ruleId = rule("""
                {"name":"막힌 이슈 독촉","trigger":{"type":"SCHEDULED","cron":"0 9 * * MON-FRI"},"condition":"flagged = true",
                 "actions":[{"type":"COMMENT","body":"아직 막혀 있나요?"}]}""");
        long blocked = issue("막힘", "task");
        long fine = issue("괜찮음", "task");
        mvc.perform(put("/api/alm/issues/{id}/flag", blocked).with(asUser(1, "Alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"flagged\":true}")).andExpect(status().isOk());

        Timestamp next = jdbc.queryForObject("select next_run_at from automation_rule where id = ?", Timestamp.class, ruleId);
        assertThat(next.toInstant()).isAfter(Instant.now());
        // 아직 때가 아니면 돌지 않는다
        assertThat(scheduler.runDue(Instant.now())).isZero();

        jdbc.update("update automation_rule set next_run_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(5)), ruleId);
        assertThat(scheduler.runDue(Instant.now())).isEqualTo(1);
        // 같은 회차를 다시 집지 않는다
        assertThat(scheduler.runDue(Instant.now())).isZero();

        mvc.perform(get("/api/alm/issues/{id}/comments", blocked).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/alm/issues/{id}/comments", fine).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/projects/{id}/automation/runs", projectId).with(asUser(ADMIN, "Admin")))
                .andExpect(jsonPath("$[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$[0].issueId").doesNotExist())
                .andExpect(jsonPath("$[0].message").value("이슈 1건 처리"));
        assertThat(jdbc.queryForObject("select next_run_at from automation_rule where id = ?", Timestamp.class, ruleId)
                .toInstant()).isAfter(Instant.now());
    }

    /**
     * Codex P1: 예약 규칙이 매 회차 page 0만 가져오면, COMMENT·NOTIFY처럼 조건을 바꾸지 않는 액션은 첫 배치만 영원히 처리한다.
     * 규칙별 커서(마지막 처리 이슈 id)로 다음 회차에 이어 가고, 끝에 닿으면 처음부터 — 한 바퀴 안에서 같은 이슈를 두 번 돌지 않는다.
     */
    @Test
    void 예약_규칙은_배치_상한을_넘는_대상을_회차마다_이어서_돌고_끝나면_처음부터() throws Exception {
        AutomationScheduler small = new AutomationScheduler(rules, runRepository, engine, aql, transactionManager, false, 2);
        long ruleId = rule("""
                {"name":"전부 독촉","trigger":{"type":"SCHEDULED","cron":"0 9 * * *"},"condition":"type = task",
                 "actions":[{"type":"COMMENT","body":"확인해 주세요"}]}""");
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(issue("작업 " + i, "task"));

        // 1회차 2건, 2회차 2건, 3회차 1건(끝) — 5건이 모두 정확히 한 번씩
        for (int tick = 0; tick < 3; tick++) {
            jdbc.update("update automation_rule set next_run_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(5)), ruleId);
            assertThat(small.runDue(Instant.now())).isEqualTo(1);
        }
        for (long id : ids) assertThat(comments(id)).as("issue " + id).isEqualTo(1);

        // 4회차는 처음부터 다시 — 앞의 두 건만 두 번째 코멘트
        jdbc.update("update automation_rule set next_run_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(5)), ruleId);
        assertThat(small.runDue(Instant.now())).isEqualTo(1);
        assertThat(comments(ids.get(0))).isEqualTo(2);
        assertThat(comments(ids.get(1))).isEqualTo(2);
        assertThat(comments(ids.get(2))).isEqualTo(1);
    }

    private long comments(long issueId) {
        return jdbc.queryForObject("select count(*) from issue_comment where issue_id = ?", Long.class, issueId);
    }

    @Test
    void 오래된_실행_이력은_30일이_지나면_지운다() throws Exception {
        long ruleId = rule("""
                {"name":"r","trigger":{"type":"ISSUE_CREATED"},"actions":[{"type":"COMMENT","body":"x"}]}""");
        Timestamp old = Timestamp.from(Instant.now().minusSeconds(31L * 24 * 3600));
        jdbc.update("insert into automation_run (rule_id, project_id, trigger_type, outcome, depth, actions_run, started_at, finished_at)"
                + " values (?, ?, 'ISSUE_CREATED', 'SUCCESS', 1, 1, ?, ?)", ruleId, projectId, old, old);
        issue("새것", "task");
        assertThat(scheduler.purgeOld(Instant.now())).isEqualTo(1);
        assertThat(outcomes(ruleId)).containsExactly("SUCCESS");
    }

    // ── 저장 검증 · 권한 ──

    @Test
    void 규칙_저장은_닫힌_어휘와_권한을_검사한다() throws Exception {
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_DELETED\"},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}",
                "모르는 트리거입니다: ISSUE_DELETED");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_TRANSITIONED\",\"toStatuses\":[\"nope\"]},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}",
                "이 프로젝트에 없는 상태입니다: nope");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"FIELD_CHANGED\",\"fields\":[]},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}",
                "지켜볼 필드를 하나 이상 고르세요");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"actions\":[{\"type\":\"DELETE\"}]}", "모르는 액션입니다: DELETE");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"actions\":[]}", "액션을 하나 이상 넣으세요");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"actions\":[{\"type\":\"SET_FIELD\",\"field\":\"title\",\"value\":\"x\"}]}",
                "바꿀 수 없는 필드입니다: title");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"SCHEDULED\",\"cron\":\"*/1 * * * *\"},\"condition\":\"flagged = true\","
                + "\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}", "예약은 5분보다 자주 돌 수 없습니다");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"SCHEDULED\",\"cron\":\"0 9 * * *\"},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}",
                "예약 규칙에는 대상을 고르는 조건(AQL)이 필요합니다");
        bad("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"actions\":[{\"type\":\"SET_FIELD\",\"field\":\"priority\",\"value\":\"urgent\"}]}",
                "없는 우선순위입니다: urgent");
        mvc.perform(post("/api/alm/projects/{id}/automation/rules", projectId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"condition\":\"status = \","
                                + "\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.position").exists());

        // 관리자가 아니면 보지도 못한다
        permissions.grantAdminOnly(ADMIN, projectId);
        mvc.perform(get("/api/alm/projects/{id}/automation/rules", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isForbidden());
        long ruleId = rule("{\"name\":\"r\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}]}");
        mvc.perform(get("/api/alm/automation/rules/{id}/runs", ruleId).with(asUser(1, "Alice")))
                .andExpect(status().isForbidden());
        // 오래된 버전으로 저장하면 409
        mvc.perform(put("/api/alm/automation/rules/{id}", ruleId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"r2\",\"trigger\":{\"type\":\"ISSUE_CREATED\"},\"actions\":[{\"type\":\"COMMENT\",\"body\":\"x\"}],\"expectedVersion\":7}"))
                .andExpect(status().isConflict());
    }

    // ── 도우미 ──

    private static String chain(String name, String field, String action) {
        return "{\"name\":\"" + name + "\",\"allowRuleTrigger\":true,\"trigger\":{\"type\":\"FIELD_CHANGED\",\"fields\":[\""
                + field + "\"]},\"actions\":[" + action + "]}";
    }

    private long rule(String body) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/automation/rules", projectId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void bad(String body, String message) throws Exception {
        mvc.perform(post("/api/alm/projects/{id}/automation/rules", projectId).with(asUser(ADMIN, "Admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(message));
    }

    private List<String> outcomes(long ruleId) {
        return jdbc.queryForList("select outcome from automation_run where rule_id = ? order by id desc", String.class, ruleId);
    }

    private long issue(String title, String type) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"" + type + "\",\"priority\":\"medium\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(long id) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/alm/issues/{id}", id).with(asUser(1, "Alice")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void move(long id, String status) throws Exception {
        mvc.perform(post("/api/alm/issues/{id}/move", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private void edit(long id, String patch) throws Exception {
        edit(id, patch, null);
    }

    /** 지금 값 위에 patch(최상위 필드)를 얹어 PUT — details는 생략(그대로) */
    private void edit(long id, String patch, String planning) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode body = (com.fasterxml.jackson.databind.node.ObjectNode) read(id);
        com.fasterxml.jackson.databind.node.ObjectNode request = JSON.createObjectNode();
        request.put("title", body.get("title").asText());
        request.put("description", body.get("description").asText());
        request.put("type", body.get("type").asText());
        request.put("status", body.get("status").asText());
        request.put("priority", body.get("priority").asText());
        request.set("assigneeId", body.get("assigneeId"));
        request.put("expectedVersion", body.get("version").asInt());
        request.setAll((com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(patch));
        if (planning != null) request.set("planning", JSON.readTree(planning));
        mvc.perform(put("/api/alm/issues/{id}", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
                .andExpect(status().isOk());
    }
}
