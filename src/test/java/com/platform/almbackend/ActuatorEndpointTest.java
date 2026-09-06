package com.platform.almbackend;

import com.platform.almbackend.security.AccountStatusInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 게이트웨이 상태판이 읽는 표면.
 *
 * <p>여기서 막는 사고는 하나다 — <b>헬스 프로브가 org-service를 부르는 것</b>. 계정 상태 게이트는
 * 모든 요청 앞에 서서 org에 gRPC로 상태를 묻는데, 그것이 헬스에도 걸리면 org가 죽는 순간 ALM이
 * "죽었다"고 보고되고 프로브마다 org에 부하가 얹힌다. 게이트는 {@code /api/alm/**}에만 걸려 있다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ActuatorEndpointTest {

    @Autowired WebApplicationContext context;
    @Autowired TestConfig.FakeMemberDirectory directory;
    @Autowired AccountStatusInterceptor gate;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        directory.reset();
        gate.evictAll();
    }

    @AfterEach
    void restore() {
        directory.reset();
        gate.evictAll();
    }

    @Test
    void 헬스는_토큰_없이_200이고_DB_상세를_준다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void info도_토큰_없이_200이다() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    /** org가 통째로 죽어도 헬스는 200이다 — 프로브는 org를 아예 부르지 않는다. */
    @Test
    void 헬스는_org를_부르지_않는다() throws Exception {
        directory.setUnavailable(true);   // 게이트를 탔다면 503이 된다

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());

        assertThat(directory.calls()).isEmpty();
    }

    /** health·info 밖은 노출 목록에 없고, 그 앞에서 시큐리티가 401로 끊는다. */
    @Test
    void 다른_액추에이터_엔드포인트는_노출되지_않는다() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
    }
}
