package com.platform.almbackend;

import com.platform.almbackend.admin.SystemStatsService;
import com.platform.almbackend.repository.AuditLogRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
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

import static com.platform.almbackend.TestAuth.asUser;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 시스템 현황의 60초 캐시.
 *
 * <p>지키려는 것 둘이다 — 같은 창 안에서 두 번 물어도 <b>집계는 한 번만</b> 돈다(대시보드를 여럿이
 * 봐도 DB 부하가 늘지 않는다), 그리고 <b>권한 판정은 캐시 밖</b>이라 캐시가 채워져 있어도
 * 관리자가 아닌 사람은 여전히 403이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SystemStatsCacheTest {

    private static final String STATS = "/api/alm/admin/stats";

    @Autowired WebApplicationContext context;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired AuditLogRepository auditLogs;
    @Autowired SystemStatsService systemStats;
    @Autowired TestConfig.FakePermissionClient permissions;

    /** 집계가 실제로 몇 번 돌았는지 세려고 리포지토리를 감싼다 — 캐시는 눈에 보이지 않으므로. */
    @MockitoSpyBean ProjectRepository projects;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        systemStats.evictAll();
        auditLogs.deleteAllInBatch();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.reset();
        permissions.setGlobalAdmins(9);
        clearInvocations(projects);
    }

    @Test
    void 같은_창_안에서_두_번_불러도_집계는_한_번만_돈다() throws Exception {
        mvc.perform(get(STATS).with(asUser(9, "Root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").value(0));

        mvc.perform(get(STATS).with(asUser(9, "Root")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").value(0));

        verify(projects, times(1)).count();
    }

    @Test
    void 캐시가_만료되기_전에는_새_프로젝트가_보이지_않는다() throws Exception {
        mvc.perform(get(STATS).with(asUser(9, "Root")))
                .andExpect(jsonPath("$.projects").value(0));

        mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"cch\",\"name\":\"캐시 검증\",\"description\":\"\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get(STATS).with(asUser(9, "Root")))
                .andExpect(jsonPath("$.projects").value(0));   // 아직 캐시된 값

        systemStats.evictAll();
        mvc.perform(get(STATS).with(asUser(9, "Root")))
                .andExpect(jsonPath("$.projects").value(1));
    }

    /** 캐시가 채워져 있어도 인가는 매 호출 판정한다. */
    @Test
    void 캐시가_있어도_비관리자는_403이다() throws Exception {
        mvc.perform(get(STATS).with(asUser(9, "Root"))).andExpect(status().isOk());

        permissions.setGlobalAdmins();   // 아무도 관리자가 아니다

        mvc.perform(get(STATS).with(asUser(9, "Root"))).andExpect(status().isForbidden());
    }
}
