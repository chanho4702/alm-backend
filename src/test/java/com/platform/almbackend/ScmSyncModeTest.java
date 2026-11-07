package com.platform.almbackend;

import com.platform.almbackend.dev.scm.ScmSyncMode;
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

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 설치 스위치 ALM_SCM_SYNC=polling — 웹훅 수신 경로는 404, 웹훅 발급은 400, 폴링 관리는 그대로 */
@SpringBootTest(properties = "platform.alm.scm.sync=polling")
@ActiveProfiles("test")
@Import(TestConfig.class)
class ScmSyncModeTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    MockMvc mvc;
    long plat;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        plat = TestData.project(mvc, "PLAT");
        mvc.perform(put("/api/alm/projects/{id}/settings/repositories", plat).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositories\":[{\"url\":\"https://github.com/a/b\"}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void 폴링만_쓰는_설치는_웹훅을_받지도_만들지도_않는다() throws Exception {
        mvc.perform(post("/api/alm/webhooks/github/anything").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"https://github.com/a/b\",\"provider\":\"GITHUB\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/alm/projects/{id}/settings/webhooks", plat).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.webhooksEnabled").value(false));
        mvc.perform(get("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.syncMode").value("polling"))
                .andExpect(jsonPath("$.webhooksEnabled").value(false))
                .andExpect(jsonPath("$.pollingEnabled").value(true));
        // github.com은 제공자·API 주소를 짐작한다
        mvc.perform(put("/api/alm/projects/{id}/settings/scm-sync", plat).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryUrl\":\"https://github.com/a/b\",\"token\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("GITHUB"))
                .andExpect(jsonPath("$.apiBaseUrl").value("https://api.github.com"));
    }

    @Test
    void 스위치_값은_세_가지뿐이고_비면_both() {
        assertThat(ScmSyncMode.parse(null)).isEqualTo(ScmSyncMode.BOTH);
        assertThat(ScmSyncMode.parse(" Polling ")).isEqualTo(ScmSyncMode.POLLING);
        assertThat(ScmSyncMode.parse("webhook").polling()).isFalse();
        assertThatThrownBy(() -> ScmSyncMode.parse("pull")).isInstanceOf(IllegalStateException.class);
    }
}
