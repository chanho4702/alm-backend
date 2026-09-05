package com.platform.almbackend;

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

import com.platform.almbackend.security.AccountStatusInterceptor;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계정 상태 격리 — 정지·비활성·승인 대기 계정은 ALM REST 어디도 못 쓴다.
 *
 * <p>검증 대상은 <b>권한 검사가 없는 전역 읽기</b>다. 프로젝트를 건드리는 경로는 원래 grant 판정이
 * 지키지만, 레지스트리 읽기 같은 경로는 아무것도 묻지 않아 org REST가 막아 둔 승인 대기 계정이
 * 여기서는 그대로 읽을 수 있었다(2026-09-05 실측).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class AccountStatusGateTest {

    /** 권한 판정이 없는 전역 읽기 — 게이트가 없으면 누구에게나 200이다 */
    private static final String OPEN_READ = "/api/alm/settings/schemes";

    @Autowired WebApplicationContext context;
    @Autowired TestConfig.FakeMemberDirectory directory;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.SettingsSeeder seeder;
    @Autowired AccountStatusInterceptor gate;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        directory.reset();
        permissions.reset();
        gate.evictAll(); // 30초 캐시가 테스트 사이에 남지 않게
        seeder.resetToDefaults();
    }

    @AfterEach
    void restore() {
        directory.reset();
        permissions.reset();
        gate.evictAll();
    }

    @Test
    void 활성_계정은_통과한다() throws Exception {
        directory.put(11, "Alice", "alice@org.example", "ACTIVE");

        mvc.perform(get(OPEN_READ).with(asUser(11, "Alice")))
                .andExpect(status().isOk());
    }

    @Test
    void 승인_대기_계정은_권한_검사가_없는_읽기도_막힌다() throws Exception {
        directory.put(12, "New", "new@org.example", "PENDING");

        mvc.perform(get(OPEN_READ).with(asUser(12, "New")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("승인 대기 중인 계정입니다"));
    }

    @Test
    void 정지된_계정도_막힌다() throws Exception {
        directory.put(13, "Paused", "paused@org.example", "SUSPENDED");

        mvc.perform(get(OPEN_READ).with(asUser(13, "Paused")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("정지된 계정입니다"));
    }

    @Test
    void 비활성된_계정도_막힌다() throws Exception {
        directory.put(14, "Gone", "gone@org.example", "DEACTIVATED");

        mvc.perform(get(OPEN_READ).with(asUser(14, "Gone")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("비활성된 계정입니다"));
    }

    /**
     * org의 member 행은 그 사람의 첫 org 호출 때 생긴다 — ALM이 먼저 불릴 수 있다.
     * 없는 것을 막으면 정상 사용자가 미러링 순서에 따라 무작위로 차단된다.
     */
    @Test
    void 아직_org에_없는_사용자는_통과한다() throws Exception {
        mvc.perform(get(OPEN_READ).with(asUser(15, "First")))
                .andExpect(status().isOk());
    }

    @Test
    void org가_불능이면_403이_아니라_503이다() throws Exception {
        directory.setUnavailable(true);

        mvc.perform(get(OPEN_READ).with(asUser(16, "Alice")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("권한 서비스에 연결할 수 없습니다"));
    }

    /** 가용성 장애가 아닌 실패는 통과 — org 버그를 "당신 계정이 정지됐다"로 말하지 않는다 */
    @Test
    void 조회_실패는_통과시킨다() throws Exception {
        directory.setFailed(true);

        mvc.perform(get(OPEN_READ).with(asUser(17, "Alice")))
                .andExpect(status().isOk());
    }

    @Test
    void 상태는_캐시되어_요청마다_묻지_않는다() throws Exception {
        directory.put(18, "Alice", "alice@org.example", "ACTIVE");

        mvc.perform(get(OPEN_READ).with(asUser(18, "Alice"))).andExpect(status().isOk());
        mvc.perform(get(OPEN_READ).with(asUser(18, "Alice"))).andExpect(status().isOk());
        mvc.perform(get(OPEN_READ).with(asUser(18, "Alice"))).andExpect(status().isOk());

        assertThat(directory.calls()).hasSize(1);
    }

    /** 장애는 캐시하지 않는다 — 30초 동안 503이 고이면 org가 살아나도 서비스가 안 돌아온다 */
    @Test
    void 장애는_캐시하지_않는다() throws Exception {
        directory.setUnavailable(true);
        mvc.perform(get(OPEN_READ).with(asUser(19, "Alice")))
                .andExpect(status().isServiceUnavailable());

        directory.setUnavailable(false);
        directory.put(19, "Alice", "alice@org.example", "ACTIVE");
        mvc.perform(get(OPEN_READ).with(asUser(19, "Alice")))
                .andExpect(status().isOk());

        // 불능 시 게이트가 한 번 재시도한다(콜드 스타트 흡수) → 실패 2회 + 복구 후 1회
        assertThat(directory.calls()).hasSize(3);
    }
}
