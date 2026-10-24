package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.realtime.RealtimeBus;
import com.platform.almbackend.realtime.RealtimeSignal;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 커밋 연결(V41) — 수집 멱등·키 파싱·프로젝트별 권한 건너뛰기·개발 정보·프로젝트 최근 커밋·실시간·자동화 트리거 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class DevCommitTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPO = "https://github.com/chanho4702/alm-backend";
    private static final long DEV = 7;

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired RealtimeBus bus;
    MockMvc mvc;
    long plat;
    long wiki;
    long plat1;
    long plat2;
    long wiki1;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        seeder.resetToDefaults();
        jdbc.update("delete from issue_web_link");
        TestData.clear(jdbc);
        plat = TestData.project(mvc, "PLAT");
        wiki = TestData.project(mvc, "WIKI");
        plat1 = issue(plat, "커밋 연결");
        plat2 = issue(plat, "개발 패널");
        wiki1 = issue(wiki, "위키 쪽");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    @Test
    void 메시지의_이슈_키로_잇고_같은_묶음을_다시_보내면_아무것도_늘지_않는다() throws Exception {
        String body = ingestBody(REPO, commit(sha('a'), "feat(alm): 커밋 연결 [PLAT-1]\\n\\nRefs: plat-2, fixes WIKI-1\\nUTF-8 ISO-8601 NOPE-3"));

        ingest(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(REPO))
                .andExpect(jsonPath("$.stored").value(1))
                .andExpect(jsonPath("$.linksCreated").value(3))
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[0].shortSha").value("aaaaaaa"))
                .andExpect(jsonPath("$.results[0].linked.length()").value(3))
                .andExpect(jsonPath("$.results[0].linked[0].issueKey").value("PLAT-1"))
                .andExpect(jsonPath("$.results[0].linked[1].issueKey").value("PLAT-2"))
                .andExpect(jsonPath("$.results[0].linked[2].issueKey").value("WIKI-1"))
                .andExpect(jsonPath("$.results[0].skipped.length()").value(0));

        ingest(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.stored").value(0))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.linksCreated").value(0))
                .andExpect(jsonPath("$.results[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.results[0].alreadyLinked.length()").value(3));

        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from issue_commit", Long.class)).isEqualTo(3);
        // 활동은 새 연결이 생겼을 때만 — 재전송은 활동을 늘리지 않는다
        assertThat(jdbc.queryForObject("select count(*) from issue_activity where activity_type = 'commit_linked' and issue_id = ?",
                Long.class, plat1)).isEqualTo(1);
    }

    @Test
    void 권한_없는_프로젝트의_키는_건너뛰고_이유를_싣는다() throws Exception {
        permissions.denyAction(DEV, wiki, AlmAction.EDIT);
        ingest(ingestBody(REPO, commit(sha('b'), "fix: PLAT-1 WIKI-1 PLAT-99"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("STORED"))
                .andExpect(jsonPath("$.results[0].linked.length()").value(1))
                .andExpect(jsonPath("$.results[0].linked[0].issueKey").value("PLAT-1"))
                .andExpect(jsonPath("$.results[0].skipped[0].issueKey").value("WIKI-1"))
                .andExpect(jsonPath("$.results[0].skipped[0].reason").value("NO_PERMISSION"))
                .andExpect(jsonPath("$.results[0].skipped[1].issueKey").value("PLAT-99"))
                .andExpect(jsonPath("$.results[0].skipped[1].reason").value("ISSUE_NOT_FOUND"));

        // 이을 곳이 하나도 없고 저장소도 등록 안 됐으면 커밋을 저장하지 않는다
        ingest(ingestBody(REPO, commit(sha('c'), "chore: WIKI-1 정리"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.results[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.results[0].reason").value("NO_LINKABLE_ISSUE"))
                .andExpect(jsonPath("$.results[0].skipped[0].reason").value("NO_PERMISSION"));
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isEqualTo(1);
    }

    @Test
    void org_불능은_삼키지_않고_503이며_아무것도_남지_않는다() throws Exception {
        permissions.setUnavailable(true);
        ingest(ingestBody(REPO, commit(sha('d'), "feat: PLAT-1"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        permissions.setUnavailable(false);
        assertThat(jdbc.queryForObject("select count(*) from dev_commit", Long.class)).isZero();
    }

    @Test
    void 형식이_틀린_커밋은_그것만_INVALID고_나머지는_처리한다() throws Exception {
        String body = """
                {"repository":"git@github.com:chanho4702/alm-backend.git","branch":"main","commits":[
                  {"sha":"abc123","message":"PLAT-1 짧은 sha","authoredAt":"2026-10-25T01:00:00Z"},
                  {"sha":"%s","message":"PLAT-1 시각 없음"},
                  %s ]}""".formatted(sha('e'), commit(sha('f'), "PLAT-1 정상"));
        ingest(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value(REPO)) // SSH 형식도 같은 저장소로 정규화
                .andExpect(jsonPath("$.invalid").value(2))
                .andExpect(jsonPath("$.stored").value(1))
                .andExpect(jsonPath("$.results[0].status").value("INVALID"))
                .andExpect(jsonPath("$.results[1].reason").value("작성 시각(authoredAt)이 필요합니다"))
                .andExpect(jsonPath("$.results[2].status").value("STORED"));

        ingest("{\"repository\":\"https://tok:x@github.com/o/r\",\"commits\":[" + commit(sha('1'), "PLAT-1") + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("저장소 URL에 계정 정보를 넣을 수 없습니다"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 501; i++) many.append(i == 0 ? "" : ",").append(commit(String.format("%040x", i + 1), "PLAT-1"));
        ingest(ingestBody(REPO, many.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("커밋은 한 번에 500건까지 보낼 수 있습니다"));
    }

    @Test
    void 개발_정보는_커밋_최신순과_PR_웹링크를_함께_준다() throws Exception {
        ingest(ingestBody(REPO,
                commitAt(sha('1'), "first PLAT-1", "2026-10-20T01:00:00Z") + ","
                        + commitAt(sha('2'), "second PLAT-1\\n\\n본문 첫 줄\\n둘째 줄", "2026-10-22T01:00:00Z")))
                .andExpect(status().isOk());
        webLink(plat1, "https://github.com/chanho4702/alm-backend/pull/9", "PR");
        webLink(plat1, "https://github.com/chanho4702/agent-service/commit/0123456789abcdef0123456789abcdef01234567", "COMMIT");
        // 이미 수집된 커밋과 같은 URL의 옛 웹 링크는 한 번만 센다
        webLink(plat1, REPO + "/commit/" + sha('1'), "COMMIT");

        mvc.perform(get("/api/alm/issues/{id}/development", plat1).with(asUser(DEV, "Dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueKey").value("PLAT-1"))
                .andExpect(jsonPath("$.summary.commits").value(3))
                .andExpect(jsonPath("$.summary.repositories").value(1))
                .andExpect(jsonPath("$.summary.pullRequests").value(1))
                .andExpect(jsonPath("$.summary.lastCommitAt").value("2026-10-22T01:00:00Z"))
                .andExpect(jsonPath("$.commits[0].title").value("second PLAT-1"))
                .andExpect(jsonPath("$.commits[0].message").value("본문 첫 줄\n둘째 줄"))
                .andExpect(jsonPath("$.commits[0].repositoryName").value("chanho4702/alm-backend"))
                .andExpect(jsonPath("$.commits[0].branch").value("main"))
                .andExpect(jsonPath("$.commits[0].url").value(REPO + "/commit/" + sha('2')))
                .andExpect(jsonPath("$.commits[1].title").value("first PLAT-1"))
                .andExpect(jsonPath("$.commits[2].source").value("WEB_LINK"))
                .andExpect(jsonPath("$.commits[2].shortSha").value("0123456"))
                .andExpect(jsonPath("$.pullRequests[0].url").value("https://github.com/chanho4702/alm-backend/pull/9"))
                .andExpect(jsonPath("$.truncated").value(false));

        permissions.deny(9, plat);
        mvc.perform(get("/api/alm/issues/{id}/development", plat1).with(asUser(9, "Outsider")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 등록_저장소의_커밋은_키가_없어도_프로젝트_최근_커밋에_쌓이고_커서로_넘긴다() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/repositories", plat).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositories\":[{\"url\":\"https://GitHub.com/chanho4702/alm-backend.git/\"},"
                                + "{\"name\":\"에이전트\",\"url\":\"https://github.com/chanho4702/agent-service\",\"defaultBranch\":\"dev\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].url").value(REPO))
                .andExpect(jsonPath("$[0].name").value("chanho4702/alm-backend"))
                .andExpect(jsonPath("$[0].defaultBranch").value("main"))
                .andExpect(jsonPath("$[1].name").value("에이전트"))
                .andExpect(jsonPath("$[1].defaultBranch").value("dev"));
        mvc.perform(get("/api/alm/projects/{id}/settings/repositories", plat).with(asUser(DEV, "Dev")))
                .andExpect(jsonPath("$.length()").value(2));

        ingest(ingestBody(REPO,
                commitAt(sha('1'), "chore: 키 없음", "2026-10-20T01:00:00Z") + ","
                        + commitAt(sha('2'), "feat: PLAT-1 WIKI-1", "2026-10-21T01:00:00Z") + ","
                        + commitAt(sha('3'), "docs: 정리", "2026-10-22T01:00:00Z")))
                .andExpect(jsonPath("$.stored").value(3));

        // 다른 저장소의 WIKI 커밋은 PLAT 목록에 없다
        ingest(ingestBody("https://github.com/chanho4702/wiki-backend", commit(sha('4'), "WIKI-1 only")))
                .andExpect(jsonPath("$.stored").value(1));

        permissions.deny(DEV, wiki); // WIKI를 볼 수 없는 사람에게는 WIKI 이슈를 싣지 않는다
        String first = mvc.perform(get("/api/alm/projects/{id}/commits", plat).param("limit", "2").with(asUser(DEV, "Dev")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].title").value("docs: 정리"))
                .andExpect(jsonPath("$.items[1].title").value("feat: PLAT-1 WIKI-1"))
                .andExpect(jsonPath("$.items[1].issues.length()").value(1))
                .andExpect(jsonPath("$.items[1].issues[0].issueKey").value("PLAT-1"))
                .andReturn().getResponse().getContentAsString();
        String cursor = JSON.readTree(first).get("nextCursor").asText();
        mvc.perform(get("/api/alm/projects/{id}/commits", plat).param("limit", "2").param("cursor", cursor).with(asUser(DEV, "Dev")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("chore: 키 없음"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get("/api/alm/projects/{id}/commits", plat).param("cursor", "!!").with(asUser(DEV, "Dev")))
                .andExpect(status().isBadRequest());

        // 저장소 등록은 프로젝트 ADMIN
        permissions.grantAdminOnly(1, plat);
        mvc.perform(put("/api/alm/projects/{id}/settings/repositories", plat).with(asUser(DEV, "Dev"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositories\":[]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 새_연결은_실시간_development_신호와_COMMIT_LINKED_자동화를_깨운다() throws Exception {
        long ruleId = TestData.id(mvc.perform(post("/api/alm/projects/{id}/automation/rules", plat).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"커밋 알림\",\"trigger\":{\"type\":\"COMMIT_LINKED\"},"
                                + "\"actions\":[{\"type\":\"COMMENT\",\"body\":\"커밋이 연결됐습니다\"}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String head = bus.head();

        ingest(ingestBody(REPO, commit(sha('9'), "feat: PLAT-1"))).andExpect(status().isOk());

        List<RealtimeSignal> signals = bus.since(head, plat, 100).signals();
        assertThat(signals).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("updated");
            assertThat(s.issueId()).isEqualTo(plat1);
            assertThat(s.changedFields()).containsExactly("development");
        });
        assertThat(jdbc.queryForObject("select count(*) from automation_run where rule_id = ? and outcome = 'SUCCESS'",
                Long.class, ruleId)).isEqualTo(1);

        // 재전송은 새 연결이 없어 다시 깨우지 않는다
        ingest(ingestBody(REPO, commit(sha('9'), "feat: PLAT-1"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from automation_run where rule_id = ?", Long.class, ruleId)).isEqualTo(1);
    }

    // ── 도우미 ──

    private ResultActions ingest(String body) throws Exception {
        return mvc.perform(post("/api/alm/dev/commits").with(asUser(DEV, "Dev"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String ingestBody(String repo, String commits) {
        return "{\"repository\":\"" + repo + "\",\"branch\":\"main\",\"commits\":[" + commits + "]}";
    }

    private static String commit(String sha, String message) {
        return commitAt(sha, message, "2026-10-25T01:00:00Z");
    }

    private static String commitAt(String sha, String message, String at) {
        return "{\"sha\":\"" + sha + "\",\"message\":\"" + message + "\",\"authorName\":\"Dev\",\"authorEmail\":\"dev@test.com\","
                + "\"authoredAt\":\"" + at + "\"}";
    }

    private static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    private long issue(long projectId, String title) throws Exception {
        return TestData.id(mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"priority\":\"medium\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void webLink(long issueId, String url, String kind) throws Exception {
        mvc.perform(post("/api/alm/issues/{id}/web-links", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\",\"title\":\"" + kind + "\",\"kind\":\"" + kind + "\"}"))
                .andExpect(status().is2xxSuccessful());
    }
}
