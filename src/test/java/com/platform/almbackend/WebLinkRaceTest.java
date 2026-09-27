package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.domain.IssueWebLink;
import com.platform.almbackend.repository.IssueActivityRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWebLinkRepository;
import com.platform.almbackend.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 링크 멱등의 경합 경로(AGP-49) — 두 요청이 "없음" 조회를 함께 통과한 상황을, 첫 조회만 비어 있게
 * 만들어 재현한다. 유니크 제약에 걸린 쪽은 예외 없이 먼저 들어간 행을 200으로 돌려받아야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class WebLinkRaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PR_URL = "https://github.com/org/repo/pull/7";

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired IssueActivityRepository activities;
    @Autowired TestConfig.FakePermissionClient permissions;
    @MockitoSpyBean IssueWebLinkRepository webLinks;

    MockMvc mvc;
    long issueId;

    @BeforeEach
    void setUp() throws Exception {
        reset(webLinks);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        webLinks.deleteAllInBatch();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String project = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"race\",\"name\":\"경합\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(project).get("id").asLong();
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"경합 이슈\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        issueId = JSON.readTree(issue).get("id").asLong();
    }

    @Test
    void 조회를_통과한_뒤_제약에_걸리면_먼저_들어간_행을_200으로_돌려준다() throws Exception {
        String first = mvc.perform(post("/api/alm/issues/{id}/web-links", issueId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + PR_URL + "\",\"title\":\"PR #7\",\"kind\":\"PR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = JSON.readTree(first).get("id").asLong();
        long activitiesBefore = activities.count();

        // 뒤따른 요청의 사전 조회가 첫 요청의 커밋 전에 실행된 것처럼 — 첫 조회만 비어 있다.
        // 리포지토리 프록시는 실제 메서드 호출 스텁을 못 받아 두 번째 조회는 실제 행을 미리 떠 둔다.
        // insertIfAbsent는 스텁하지 않는다 — 실제로 제약에 걸려 0을 돌려주는 경로를 탄다.
        Optional<IssueWebLink> stored = webLinks.findByIssueIdAndUrl(issueId, PR_URL);
        assertThat(stored).isPresent();
        doReturn(Optional.empty()).doReturn(stored).when(webLinks).findByIssueIdAndUrl(anyLong(), anyString());

        mvc.perform(post("/api/alm/issues/{id}/web-links", issueId).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + PR_URL + "\",\"title\":\"다른 제목\",\"kind\":\"PR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.title").value("PR #7"))
                .andExpect(jsonPath("$.createdBy").value(1));

        // 두 요청 모두 실제 삽입을 시도했다 — 두 번째는 제약에 걸려 0행(예외 없음)
        verify(webLinks, times(2)).insertIfAbsent(anyLong(), anyString(), any(),
                anyString(), anyLong(), any());
        assertThat(webLinks.findByIssueIdOrderByIdDesc(issueId)).hasSize(1);
        // 새로 만든 게 아니므로 활동도 남기지 않는다
        assertThat(activities.count()).isEqualTo(activitiesBefore);
    }
}
