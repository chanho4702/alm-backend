package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.SettingsScheme;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueTypeDefRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.ProjectSettingsRepository;
import com.platform.almbackend.repository.SettingsSchemeRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.almbackend.repository.StatusCategoryRepository;
import com.platform.almbackend.repository.StatusDefRepository;
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

    /** 스킴/레지스트리는 컨텍스트를 공유하는 다른 테스트 클래스와 같은 DB에 있다 — 바꾼 구성을 되돌린다 */
    @AfterEach
    void restoreDefaults() {
        seeder.resetToDefaults();
    }

    private static String defaultBodyWith(String extraStatuses, String transitions, String enabledTypes) {
        return "{\"statuses\":[{\"id\":\"todo\",\"name\":\"할 일\",\"category\":\"todo\",\"order\":1},"
                + "{\"id\":\"inprogress\",\"name\":\"진행 중\",\"category\":\"inprogress\",\"order\":2},"
                + "{\"id\":\"done\",\"name\":\"완료\",\"category\":\"done\",\"order\":3}" + extraStatuses + "],"
                + "\"transitions\":" + transitions + ",\"layout\":{},\"enabledTypes\":" + enabledTypes + "}";
    }

    private static final String ALL_TYPES = "[\"task\",\"story\",\"bug\",\"epic\",\"subtask\"]";

    /** 기본 본문에 필드 구성만 얹는다 — 보내지 않은 필드는 서버가 기본값으로 채운다 */
    private static String defaultBodyWithFields(String fields) {
        String base = defaultBodyWith("", "[]", ALL_TYPES);
        return base.substring(0, base.length() - 1) + ",\"fields\":" + fields + "}";
    }

    private static String field(String id, boolean visible, boolean required) {
        return "{\"id\":\"" + id + "\",\"visible\":" + visible + ",\"required\":" + required + "}";
    }

    /** 기본 구성 위에 이슈 타입별 덮어쓰기까지 얹은 본문 */
    private static String defaultBodyWithFieldsByType(String fields, String fieldsByType) {
        String base = defaultBodyWithFields(fields);
        return base.substring(0, base.length() - 1) + ",\"fieldsByType\":" + fieldsByType + "}";
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
    void 기본_상태는_아이콘을_갖고_워크플로_본문에도_실린다() throws Exception {
        mvc.perform(get("/api/alm/settings/statuses").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='todo')].icon").value("circle"))
                .andExpect(jsonPath("$[?(@.id=='inprogress')].icon").value("loader-circle"))
                .andExpect(jsonPath("$[?(@.id=='done')].icon").value("circle-check"));

        // 워크플로 본문의 상태 캐시에도 읽을 때 채워 내려간다 — 화면이 레지스트리를 따로 안 받아도 되게
        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.statuses[0].icon").value("circle"))
                .andExpect(jsonPath("$.body.statuses[2].icon").value("circle-check"));
    }

    @Test
    void 아이콘을_고르고_지우면_카테고리_의미의_기본으로_돌아간다() throws Exception {
        String created = mvc.perform(post("/api/alm/settings/statuses").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"검토 중\",\"categoryId\":\"inprogress\",\"icon\":\"flask\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.icon").value("flask"))
                .andReturn().getResponse().getContentAsString();
        String statusId = JSON.readTree(created).get("id").asText();

        // 빈 문자열은 "미지정"이라는 유효한 값 — 레지스트리 응답은 원본을 그대로 준다(편집기가 "미지정"을 보여야 한다)
        mvc.perform(put("/api/alm/settings/statuses/{id}", statusId).with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"icon\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.icon").value(""))
                .andExpect(jsonPath("$.name").value("검토 중"));

        // 워크플로에 넣으면 화면이 그릴 수 있게 의미(active)의 기본 아이콘으로 폴백해 내려간다
        String body = defaultBodyWith(
                ",{\"id\":\"" + statusId + "\",\"name\":\"검토 중\",\"category\":\"inprogress\",\"order\":4}",
                "[]", ALL_TYPES);
        mvc.perform(put("/api/alm/settings/schemes/{id}", "scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"기본 스킴\",\"body\":" + body + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.statuses[3].icon").value("refresh-cw"));
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

    @Test
    void 필드_구성이_없는_본문도_13종_전부_표시_비필수로_채워_응답한다() throws Exception {
        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fields.length()").value(13))
                .andExpect(jsonPath("$.body.fields[0].id").value("description"))
                .andExpect(jsonPath("$.body.fields[0].visible").value(true))
                .andExpect(jsonPath("$.body.fields[0].required").value(false))
                .andExpect(jsonPath("$.body.fields[1].id").value("assignee"))
                .andExpect(jsonPath("$.body.fields[12].id").value("links"));
        mvc.perform(get("/api/alm/settings/schemes").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body.fields.length()").value(13));
    }

    @Test
    void 필드_구성_규칙_위반은_400으로_거부한다() throws Exception {
        // 모르는 id
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields("[" + field("severity", true, false) + "]") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("없는 필드입니다: severity"));
        // 숨긴 필드는 필수 불가
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields("[" + field("dueDate", false, true) + "]") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("숨긴 필드는 필수로 지정할 수 없습니다: 마감일"));
        // 해결은 필수 불가
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields("[" + field("resolution", true, true) + "]") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("해결은 완료 상태에서만 입력하므로 필수로 지정할 수 없습니다"));
        // 상위 항목은 필수 불가 — 필수로 걸면 최상위 이슈를 만들 수 없다
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields("[" + field("parent", true, true) + "]") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상위 항목은 최상위 이슈가 있어야 하므로 필수로 지정할 수 없습니다"));
        // id가 비었다
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields("[{\"visible\":true,\"required\":false}]") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("필드 id가 비어 있습니다"));
        // 같은 id 중복
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields(
                                "[" + field("labels", true, false) + "," + field("labels", false, false) + "]") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("같은 필드를 두 번 넣을 수 없습니다: 라벨"));
        // 거부된 요청은 저장되지 않았다
        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.body.fields[9].id").value("resolution"))
                .andExpect(jsonPath("$.body.fields[9].required").value(false));
    }

    @Test
    void 필수_필드는_생성에서_막고_값을_주면_통과한다() throws Exception {
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields(
                                "[" + field("assignee", true, true) + "," + field("dueDate", true, true)
                                        + "," + field("links", false, false) + "]") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fields[1].id").value("assignee"))
                .andExpect(jsonPath("$.body.fields[1].required").value(true))
                .andExpect(jsonPath("$.body.fields[12].visible").value(false));

        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업2\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("담당자는 필수입니다"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업2\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\","
                                + "\"assigneeId\":7,\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("마감일은 필수입니다"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업2\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\","
                                + "\"assigneeId\":7,\"details\":{\"dueDate\":\"2026-09-30\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeId").value(7));
        // 수정(PUT)에서는 강제하지 않는다 — 구성이 바뀌었다고 기존 이슈 편집을 막지 않는다
        mvc.perform(put("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\","
                                + "\"expectedVersion\":" + version + ",\"details\":{}}"))
                .andExpect(status().isOk());
    }

    @Test
    void 커스텀_프로젝트는_스킴과_다른_필드_구성으로_동작한다() throws Exception {
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFields("[" + field("assignee", true, true) + "]") + "}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"스킴 규칙\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("담당자는 필수입니다"));

        // 커스텀 전환은 스킴 본문을 복사하므로 필수 규칙도 함께 온다
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("custom"))
                .andExpect(jsonPath("$.body.fields[1].required").value(true));
        // 이 프로젝트만 필수 해제
        mvc.perform(put("/api/alm/projects/{id}/settings/custom-body", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(defaultBodyWithFields("[" + field("assignee", true, false) + "]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fields[1].required").value(false));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"커스텀 규칙\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated());
        // 스킴 자체는 그대로 필수
        mvc.perform(get("/api/alm/settings/schemes").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].body.fields[1].required").value(true));
        // 스킴으로 복귀하면 다시 막힌다
        mvc.perform(put("/api/alm/projects/{id}/settings/custom", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"custom\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("scheme"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"복귀\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("담당자는 필수입니다"));
    }

    @Test
    void 타입별_구성은_기본_위에_필드_단위로_얹히고_키가_없는_타입은_기본을_따른다() throws Exception {
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFieldsByType(
                                "[" + field("assignee", true, true) + "]",
                                "{\"bug\":[" + field("assignee", true, false) + "," + field("dueDate", true, true) + "]}")
                                + "}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                // 기본 구성은 그대로 13종
                .andExpect(jsonPath("$.body.fields.length()").value(13))
                .andExpect(jsonPath("$.body.fields[1].id").value("assignee"))
                .andExpect(jsonPath("$.body.fields[1].required").value(true))
                .andExpect(jsonPath("$.body.fields[7].id").value("dueDate"))
                .andExpect(jsonPath("$.body.fields[7].required").value(false))
                // 덮어쓰기가 있는 타입만 13종 전부로 정규화된다
                .andExpect(jsonPath("$.body.fieldsByType.bug.length()").value(13))
                .andExpect(jsonPath("$.body.fieldsByType.bug[1].id").value("assignee"))
                .andExpect(jsonPath("$.body.fieldsByType.bug[1].required").value(false))
                .andExpect(jsonPath("$.body.fieldsByType.bug[7].id").value("dueDate"))
                .andExpect(jsonPath("$.body.fieldsByType.bug[7].required").value(true))
                .andExpect(jsonPath("$.body.fieldsByType.task").doesNotExist());

        // 기본을 따르는 타입은 기본의 필수를 받는다
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("담당자는 필수입니다"));
        // 덮어쓴 타입은 담당자가 풀리고 마감일이 필수다
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"버그\",\"description\":\"\",\"type\":\"bug\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("마감일은 필수입니다"));
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"버그\",\"description\":\"\",\"type\":\"bug\",\"status\":\"todo\",\"priority\":\"MEDIUM\","
                                + "\"details\":{\"dueDate\":\"2026-09-30\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("bug"));
    }

    @Test
    void CSV_가져오기도_항목의_타입으로_해석한_필수를_강제한다() throws Exception {
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFieldsByType(
                                "[]", "{\"bug\":[" + field("dueDate", true, true) + "]}") + "}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/alm/projects/{id}/issues/import", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":["
                                + "{\"title\":\"마감일 없는 버그\",\"type\":\"BUG\"},"
                                + "{\"title\":\"마감일 있는 버그\",\"type\":\"BUG\",\"details\":{\"dueDate\":\"2026-09-30\"}},"
                                + "{\"title\":\"기본을 따르는 작업\",\"type\":\"TASK\"}"
                                + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed.length()").value(1))
                .andExpect(jsonPath("$.failed[0].row").value(1))
                .andExpect(jsonPath("$.failed[0].reason").value("마감일은 필수입니다"));
    }

    @Test
    void 타입별_구성의_규칙_위반과_없는_타입_키는_400으로_거부한다() throws Exception {
        // 레지스트리에 없는 타입 키
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFieldsByType(
                                "[]", "{\"improvement\":[" + field("dueDate", true, true) + "]}") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("없는 이슈 타입입니다: improvement"));
        // 목록 규칙은 기본 구성과 같다 — 숨김+필수
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFieldsByType(
                                "[]", "{\"bug\":[" + field("dueDate", false, true) + "]}") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("숨긴 필드는 필수로 지정할 수 없습니다: 마감일"));
        // 상위 항목은 타입별로도 필수 불가 — 하위 작업이라도 계층 규칙이 이미 상위를 요구한다
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFieldsByType(
                                "[]", "{\"subtask\":[" + field("parent", true, true) + "]}") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("상위 항목은 최상위 이슈가 있어야 하므로 필수로 지정할 수 없습니다"));
        // 모르는 필드 id
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":" + defaultBodyWithFieldsByType(
                                "[]", "{\"bug\":[" + field("severity", true, false) + "]}") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("없는 필드입니다: severity"));
        // 거부된 요청은 저장되지 않았다
        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.body.fieldsByType").isEmpty());
    }

    @Test
    void 이슈_타입을_지우면_타입별_구성도_함께_사라진다() throws Exception {
        String created = mvc.perform(post("/api/alm/settings/issue-types").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"개선\",\"icon\":\"lightbulb\",\"color\":\"warning\",\"level\":\"standard\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String typeId = JSON.readTree(created).get("id").asText();

        String base = defaultBodyWith("", "[]", "[\"task\",\"story\",\"bug\",\"epic\",\"subtask\",\"" + typeId + "\"]");
        String body = base.substring(0, base.length() - 1)
                + ",\"fieldsByType\":{\"" + typeId + "\":[" + field("dueDate", true, true) + "],"
                + "\"bug\":[" + field("estimate", true, true) + "]}}";
        mvc.perform(put("/api/alm/settings/schemes/scheme-default").with(asAdmin(9, "Root"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":" + body + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fieldsByType['" + typeId + "'].length()").value(13));

        mvc.perform(delete("/api/alm/settings/issue-types/{id}", typeId).with(asAdmin(9, "Root")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/alm/settings/schemes").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body.enabledTypes.length()").value(5))
                .andExpect(jsonPath("$[0].body.fieldsByType['" + typeId + "']").doesNotExist())
                // 남은 타입의 덮어쓰기는 그대로다
                .andExpect(jsonPath("$[0].body.fieldsByType.bug[10].id").value("estimate"))
                .andExpect(jsonPath("$[0].body.fieldsByType.bug[10].required").value(true));
    }

    @Test
    void 필드_구성이_아예_없는_구버전_본문도_기본_13종과_빈_타입별_구성으로_읽는다() throws Exception {
        SettingsScheme scheme = schemes.findById("scheme-default").orElseThrow();
        scheme.replaceBody(defaultBodyWith("", "[]", ALL_TYPES));
        schemes.save(scheme);

        mvc.perform(get("/api/alm/projects/{id}/settings", projectId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.fields.length()").value(13))
                .andExpect(jsonPath("$.body.fields[0].id").value("description"))
                .andExpect(jsonPath("$.body.fields[0].required").value(false))
                .andExpect(jsonPath("$.body.fieldsByType").isEmpty());
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"구버전\",\"description\":\"\",\"type\":\"bug\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated());
    }
}
