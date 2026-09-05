package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.LinkTypeDefRepository;
import com.platform.almbackend.repository.ProjectRepository;
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

/** 링크 타입 레지스트리(지라 업무 항목 연결) — 기본 5종, 커스텀, 대칭 여부가 중복·방향 판정을 정한다 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class LinkTypeControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired LinkTypeDefRepository linkTypes;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long a;
    private long b;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        linkTypes.findAll().stream().filter(t -> !t.isBuiltIn()).forEach(linkTypes::delete);
        permissions.setAllowed(true);
        // 전역 관리자는 org-service의 GLOBAL/ADMIN grant다 — JWT 역할이 아니다(2026-09-05)
        permissions.setGlobalAdmins(1);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(body).get("id").asLong();
        a = createIssue(projectId, "A");
        b = createIssue(projectId, "B");
    }

    private long createIssue(long projectId, String title) throws Exception {
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(issue).get("id").asLong();
    }

    private void link(long source, long target, String type, int expected) throws Exception {
        mvc.perform(post("/api/alm/issues/{id}/links", source).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + target + ",\"type\":\"" + type + "\"}"))
                .andExpect(status().is(expected));
    }

    @Test
    void 기본_5종이_있고_대칭_타입은_역방향_중복도_막으며_방향_없이_보인다() throws Exception {
        mvc.perform(get("/api/alm/settings/link-types").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].id").value("blocks"))
                .andExpect(jsonPath("$[1].outward").value("관련됨"))
                .andExpect(jsonPath("$[3].id").value("causes"));
        link(a, b, "duplicates", 201);
        link(b, a, "duplicates", 201); // 비대칭 — 역방향은 다른 링크
        link(a, b, "relates", 201);
        link(b, a, "relates", 400); // 대칭 — 역방향도 중복
        link(a, b, "nope", 400);
        mvc.perform(get("/api/alm/issues/{id}/links", b).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.link.type=='relates')].direction").value("outward"));
        mvc.perform(get("/api/alm/issues/{id}/activity", a).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[1].detail").value("중복 링크: ALM-2"));
    }

    @Test
    void 관리자가_커스텀_타입을_만들고_쓰이는_타입은_대칭_여부를_못_바꾸고_지우지_못한다() throws Exception {
        mvc.perform(post("/api/alm/settings/link-types").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"의존\",\"outward\":\"의존함\",\"inward\":\"의존됨\"}"))
                .andExpect(status().isForbidden());
        String created = mvc.perform(post("/api/alm/settings/link-types").with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"의존\",\"outward\":\"의존함\",\"inward\":\"의존됨\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order").value(6))
                .andReturn().getResponse().getContentAsString();
        String id = JSON.readTree(created).get("id").asText();
        link(a, b, id, 201);
        mvc.perform(put("/api/alm/settings/link-types/{id}", id).with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outward\":\"의존\",\"inward\":\"의존\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 타입을 쓰는 링크가 있어 방향성(대칭 여부)을 바꿀 수 없습니다"));
        mvc.perform(put("/api/alm/settings/link-types/{id}", id).with(asAdmin(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"선행\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("선행"));
        mvc.perform(delete("/api/alm/settings/link-types/{id}", id).with(asAdmin(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이 타입을 쓰는 링크가 있습니다"));
        mvc.perform(delete("/api/alm/settings/link-types/blocks").with(asAdmin(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("기본 링크 타입은 삭제할 수 없습니다"));
        mvc.perform(get("/api/alm/settings/link-types/usage").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$." + id).value(1));
    }
}
