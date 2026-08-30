package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.BoardRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.NotificationRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
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

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 코멘트·워크로그·링크·활동·보드 계약 — 프론트 목업과 같은 규칙을 서버가 강제한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class CollaborationControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired BoardRepository boards;
    @Autowired NotificationRepository notifications;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;
    private long issueA;
    private long issueB;
    private long version;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAllInBatch();
        boards.deleteAllInBatch();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
        issueA = createIssue("첫 이슈");
        issueB = createIssue("둘째 이슈");
    }

    private long createIssue(String title) throws Exception {
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        version = JSON.readTree(issue).get("version").asLong();
        return JSON.readTree(issue).get("id").asLong();
    }

    @Test
    void 코멘트는_본인만_고치고_지우며_워처에게_알림이_간다() throws Exception {
        mvc.perform(put("/api/alm/issues/{id}/watchers/me", issueA).with(asUser(2, "Bob")));
        String created = mvc.perform(post("/api/alm/issues/{id}/comments", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\" 확인했습니다 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("확인했습니다"))
                .andExpect(jsonPath("$.authorId").value(1))
                .andReturn().getResponse().getContentAsString();
        long commentId = JSON.readTree(created).get("id").asLong();

        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("COMMENTED"));

        mvc.perform(put("/api/alm/comments/{id}", commentId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"몰래 수정\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/alm/comments/{id}", commentId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"수정함\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").exists());
        mvc.perform(post("/api/alm/issues/{id}/comments", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/alm/comments/{id}", commentId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/issues/{id}/comments", issueA).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 워크로그와_링크는_활동으로_남고_링크는_중복과_자기연결을_막는다() throws Exception {
        mvc.perform(post("/api/alm/issues/{id}/worklogs", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2.5,\"comment\":\"구현\",\"workedOn\":\"2026-08-30\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hours").value(2.5));
        mvc.perform(post("/api/alm/issues/{id}/worklogs", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":0,\"workedOn\":\"2026-08-30\"}"))
                .andExpect(status().isBadRequest());

        String link = mvc.perform(post("/api/alm/issues/{id}/links", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + issueB + ",\"type\":\"blocks\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long linkId = JSON.readTree(link).get("id").asLong();
        mvc.perform(post("/api/alm/issues/{id}/links", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + issueB + ",\"type\":\"blocks\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("이미 연결돼 있습니다"));
        mvc.perform(post("/api/alm/issues/{id}/links", issueA).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":" + issueA + ",\"type\":\"relates\"}"))
                .andExpect(status().isBadRequest());

        // B 쪽에서 보면 차단됨(inward)
        mvc.perform(get("/api/alm/issues/{id}/links", issueB).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].direction").value("inward"))
                .andExpect(jsonPath("$[0].other.key").value("ALM-1"));

        mvc.perform(get("/api/alm/issues/{id}/activity", issueA).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].type").value("created"))
                .andExpect(jsonPath("$[1].type").value("worklog"))
                .andExpect(jsonPath("$[1].detail").value("2.5시간 기록"))
                .andExpect(jsonPath("$[2].type").value("link"))
                .andExpect(jsonPath("$[2].detail").value("차단 링크: ALM-2"));

        mvc.perform(delete("/api/alm/links/{id}", linkId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/issues/{id}/links", issueA).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 이슈_수정은_바뀐_필드마다_활동을_남긴다() throws Exception {
        mvc.perform(put("/api/alm/issues/{id}", issueB).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"둘째 이슈\",\"description\":\"\",\"type\":\"task\",\"status\":\"inprogress\",\"priority\":\"HIGH\",\"assigneeId\":2,\"expectedVersion\":" + version + ",\"details\":{\"labels\":[\"backend\"]}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/alm/issues/{id}/activity", issueB).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[1].type").value("status"))
                .andExpect(jsonPath("$[1].detail").value("할 일 → 진행 중"))
                .andExpect(jsonPath("$[2].type").value("assignee"))
                .andExpect(jsonPath("$[3].type").value("priority"))
                .andExpect(jsonPath("$[4].type").value("labels"))
                .andExpect(jsonPath("$[4].detail").value("backend"));
    }

    @Test
    void 보드는_프로젝트마다_기본_하나가_생기고_마지막은_못_지운다() throws Exception {
        String list = mvc.perform(get("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("메인 보드"))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andReturn().getResponse().getContentAsString();
        long mainId = JSON.readTree(list).get(0).get("id").asLong();
        mvc.perform(delete("/api/alm/boards/{id}", mainId).with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("마지막 보드는 삭제할 수 없습니다"));

        String created = mvc.perform(post("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"칸반\",\"type\":\"kanban\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long kanbanId = JSON.readTree(created).get("id").asLong();
        // 칸반은 스프린트 무관 전체, 필터 저장
        mvc.perform(put("/api/alm/boards/{id}", kanbanId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"kanban\",\"filter\":{\"assigneeIds\":[\"unassigned\"],\"types\":[],\"labels\":[]},\"columns\":[{\"status\":\"todo\",\"name\":\"대기\",\"wipLimit\":3}],\"swimlane\":\"assignee\",\"isDefault\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("kanban"))
                .andExpect(jsonPath("$.columns[0].wipLimit").value(3))
                .andExpect(jsonPath("$.isDefault").value(true));
        mvc.perform(get("/api/alm/boards/{id}/issues", kanbanId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(2));
        // scrum 보드는 활성 스프린트가 없어 비어 있다
        mvc.perform(get("/api/alm/boards/{id}/issues", mainId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/alm/projects/{id}/boards", projectId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].id").value(kanbanId)); // 기본 보드가 앞
        mvc.perform(delete("/api/alm/boards/{id}", mainId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
    }
}
