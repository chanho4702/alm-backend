package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 웨이브 D·트리아지·자동화 테스트가 같이 쓰는 데이터 도우미 — 사용자 1(Alice)이 만든다 */
final class TestData {
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestData() {}

    /** 이 테스트들이 건드리는 표를 비운다(자식부터) */
    static void clear(JdbcTemplate jdbc) {
        for (String table : new String[] {
                "automation_run", "automation_rule", "saved_view", "saved_filter", "notification", "issue_watcher",
                "issue_activity", "issue_change_log", "issue_link", "issue_comment", "issue_label", "issue_component",
                "issue", "sprint", "component", "board", "project_settings", "project"}) {
            jdbc.update("delete from " + table);
        }
    }

    static long project(MockMvc mvc, String key) throws Exception {
        return id(mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + key + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    static long sprint(MockMvc mvc, long projectId, String name) throws Exception {
        return id(mvc.perform(post("/api/alm/projects/{id}/sprints", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    static long id(String body) throws Exception {
        return JSON.readTree(body).get("id").asLong();
    }
}
