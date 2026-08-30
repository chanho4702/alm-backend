package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SettingsSchemeRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
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

import static com.platform.almbackend.TestAuth.asAdmin;
import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설정 서버 저장 계약 — 레지스트리 규칙, 스킴 수정 시 이슈 이관, 커스텀 전환, 전이·타입 강제.
 * 테스트 프로필은 H2 create-drop이라 V11 시드가 없다 → TestConfig가 기본값을 심는다(SettingsSeeder).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SettingsControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired ProjectSettingsRepository projectSettings;
    @Autowired SettingsSchemeRepository schemes;
    @Autowired StatusDefRepository statuses;
    @Autowired StatusCategoryRepository categories;
    @Autowired IssueTypeDefRepository types;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;

    private long projectId;
    private long issueId;
    private long version;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projectSettings.deleteAllInBatch();
        projects.deleteAllInBatch();
        seeder.resetToDefaults();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("task"))
                .andReturn().getResponse().getContentAsString();
        issueId = JSON.readTree(issue).get("id").asLong();
        version = JSON.readTree(issue).get("version").asLong();
    }

    private static String defaultBodyWith(String extraStatuses, String transitions, String enabledTypes) {
        return "{\"statuses\":[{\"id\":\"todo\",\"name\":\"할 일\",\"category\":\"todo\",\"order\":1},"
                + "{\"id\":\"inprogress\",\"name\":\"진행 중\",\"category\":\"inprogress\",\"order\":2},"
                + "{\"id\":\"done\",\"name\":\"완료\",\"category\":\"done\",\"order\":3}" + extraStatuses + "],"
                + "\"transitions\":" + transitions + ",\"layout\":{},\"enabledTypes\":" + enabledTypes + "}";
    }

    @Test
    void 새_프로젝트는_디폴트_스킴을_받고_해석된_본문에_의미와_색이_붙는다() throws Exception {
        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("scheme"))
                .andExpect(jsonPath("$.scheme.id").value("scheme-default"))
                .andExpect(jsonPath("$.body.statuses[2].kind").value("complete"))
                .andExpect(jsonPath("$.body.statuses[2].color").value("success"))
                .andExpect(jsonPath("$.body.enabledTypes.length()").value(5));
    }

    @Test
    void 레지스트리_규칙_기본값은_못_지우고_이름은_유일하며_쓰는_상태는_못_지운다() throws Exception {
        mvc.perform(delete("/api/alm/settings/categories/done").with(asAdmin(9, "Root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("기본 카테고리는 삭제할 수 없습니다"));
        mvc.perform(post("/api/alm/settings/statuses").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"완료\",\"categoryId\":\"done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상태 이름이 중복됩니다: 완료"));
        mvc.perform(delete("/api/alm/settings/statuses/todo").with(asAdmin(9, "Root")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("워크플로에서 쓰는 상태는 삭제할 수 없습니다"));
        // 일반 사용자는 전역 쓰기 불가
        mvc.perform(post("/api/alm/settings/categories").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"검토\",\"kind\":\"active\",\"color\":\"warning\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/alm/settings/categories").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"검토\",\"kind\":\"active\",\"color\":\"warning\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order").value(4))
                .andExpect(jsonPath("$.builtIn").value(false));
    }

    @Test
    void 스킴에_상태를_넣고_빼면_이슈가_같은_카테고리의_첫_상태로_이관되고_이력이_남는다() throws Exception {
        // 리뷰 상태 등록 + 스킴에 추가
        String created = mvc.perform(post("/api/alm/settings/statuses").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"리뷰\",\"categoryId\":\"inprogress\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reviewId = JSON.readTree(created).get("id").asText();
        String extra = ",{\"id\":\"" + reviewId + "\",\"name\":\"리뷰\",\"category\":\"inprogress\",\"order\":4}";
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWith(extra, "[]", "[\"task\",\"story\",\"bug\",\"epic\",\"subtask\"]") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.statuses.length()").value(4));

        // 이슈를 리뷰로 옮긴다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"" + reviewId
                                + "\",\"priority\":\"MEDIUM\",\"expectedVersion\":" + version + ",\"details\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(reviewId));

        // 스킴에서 리뷰를 빼면 → 같은 카테고리(진행 중)의 첫 상태로
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWith("", "[]", "[\"task\",\"story\",\"bug\",\"epic\",\"subtask\"]") + "}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.status").value("inprogress"));
        mvc.perform(get("/api/alm/projects/{id}/changes", projectId).param("field", "STATUS").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[-1].fromValue").value(reviewId))
                .andExpect(jsonPath("$[-1].toValue").value("inprogress"));

        // 완료 의미 상태를 전부 빼면 거부
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":{\"statuses\":[{\"id\":\"todo\",\"name\":\"할 일\",\"category\":\"todo\",\"order\":1},"
                                + "{\"id\":\"inprogress\",\"name\":\"진행 중\",\"category\":\"inprogress\",\"order\":2}],"
                                + "\"transitions\":[],\"layout\":{},\"enabledTypes\":[\"task\",\"subtask\"]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("카테고리(할 일/진행 중/완료)마다 상태가 최소 1개 필요합니다"));
    }

    @Test
    void 전이_규칙과_활성_타입을_서버가_강제한다() throws Exception {
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWith("",
                                "[{\"id\":\"t1\",\"name\":\"시작\",\"from\":[\"todo\"],\"to\":\"inprogress\"}]",
                                "[\"task\",\"subtask\"]") + "}"))
                .andExpect(status().isOk());

        // todo → done은 전이 목록에 없다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"done\",\"priority\":\"MEDIUM\",\"expectedVersion\":" + version + ",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("할 일에서 완료로 옮길 수 없습니다"));
        // 없는 상태
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"ghost\",\"priority\":\"MEDIUM\",\"expectedVersion\":" + version + ",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 프로젝트에 없는 상태입니다: ghost"));
        // 꺼진 타입으로 생성 불가
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"버그\",\"description\":\"\",\"type\":\"bug\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 프로젝트에서 사용할 수 없는 타입입니다: 버그"));
        // 허용된 전이는 된다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"inprogress\",\"priority\":\"MEDIUM\",\"expectedVersion\":" + version + ",\"details\":{}}"))
                .andExpect(status().isOk());
    }

    @Test
    void 커스텀_전환은_스킴을_복사하고_복귀하면_스킴_구성으로_돌아간다() throws Exception {
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("custom"));
        mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultBodyWith("", "[]", "[\"story\",\"subtask\"]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.enabledTypes.length()").value(2));
        mvc.perform(get("/api/alm/settings/schemes/scheme-default/projects/count").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":false}"))
                .andExpect(jsonPath("$.source").value("scheme"))
                .andExpect(jsonPath("$.body.enabledTypes.length()").value(5));
        // 사용자 정의 타입 등록 → 스킴에 켜면 생성 가능, 계층은 level에서
        String created = mvc.perform(post("/api/alm/settings/issue-types").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"개선\",\"icon\":\"lightbulb\",\"color\":\"warning\",\"level\":\"standard\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String typeId = JSON.readTree(created).get("id").asText();
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWith("", "[]", "[\"task\",\"story\",\"bug\",\"epic\",\"subtask\",\"" + typeId + "\"]") + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"개선 건\",\"description\":\"\",\"type\":\"" + typeId + "\",\"status\":\"todo\",\"priority\":\"LOW\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value(typeId));
    }
}
