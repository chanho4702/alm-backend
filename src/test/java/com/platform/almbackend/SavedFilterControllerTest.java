package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.SavedFilterRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 저장 필터 — 사이드바에 꽂아 두는 내 검색. 사이드바가 서버를 정본으로 삼으므로 소유 격리와
 * 이름 중복, AQL 문법 거절이 계약의 전부다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class SavedFilterControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired SavedFilterRepository filters;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        filters.deleteAllInBatch();
    }

    @Test
    void 만들고_읽고_고치고_지운다() throws Exception {
        long id = create(1, "내 미해결", "smart", "담당자:나 상태:미해결");

        mvc.perform(get("/api/alm/me/filters").with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].name").value("내 미해결"))
                .andExpect(jsonPath("$[0].kind").value("smart"))
                .andExpect(jsonPath("$[0].query").value("담당자:나 상태:미해결"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].updatedAt").isNotEmpty());

        // 보낸 것만 바뀐다 — kind·query는 그대로다
        mvc.perform(put("/api/alm/me/filters/{id}", id).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"내 할 일\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("내 할 일"))
                .andExpect(jsonPath("$.kind").value("smart"))
                .andExpect(jsonPath("$.query").value("담당자:나 상태:미해결"));

        mvc.perform(delete("/api/alm/me/filters/{id}", id).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/alm/me/filters").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * 순서는 DB 콜레이션이 아니라 한국어 {@code Collator}(PRIMARY)가 정한다 — 숫자 → 영문 → 한글이고
     * 대소문자는 가리지 않는다. H2로 돌든 Postgres로 돌든 같은 순서여야 사이드바가 흔들리지 않는다.
     */
    @Test
    void 목록은_한글과_영문이_섞여도_같은_이름_순이다() throws Exception {
        long banana = create(1, "banana", "smart", "a");
        long hangulLast = create(1, "하나", "smart", "b");
        long apple = create(1, "Apple", "smart", "c");
        long hangulFirst = create(1, "가나", "smart", "d");
        long digit = create(1, "1순위", "smart", "e");
        // 대소문자만 다른 이름은 동률 — id 오름차순으로 끊는다(Apple이 먼저 생겼다)
        long appleLower = create(1, "apple", "smart", "f");

        mvc.perform(get("/api/alm/me/filters").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].id").value(digit))
                .andExpect(jsonPath("$[1].id").value(apple))
                .andExpect(jsonPath("$[2].id").value(appleLower))
                .andExpect(jsonPath("$[3].id").value(banana))
                .andExpect(jsonPath("$[4].id").value(hangulFirst))
                .andExpect(jsonPath("$[5].id").value(hangulLast));
    }

    @Test
    void 남의_필터는_목록에도_없고_지목하면_404다() throws Exception {
        long alice = create(1, "앨리스 것", "smart", "a");
        create(2, "밥 것", "smart", "b");

        mvc.perform(get("/api/alm/me/filters").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("밥 것"));

        // 403이 아니라 404다 — 남의 필터가 있다는 사실 자체를 알리지 않는다
        mvc.perform(put("/api/alm/me/filters/{id}", alice).with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"가로채기\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("저장 필터를 찾을 수 없습니다: " + alice));
        mvc.perform(delete("/api/alm/me/filters/{id}", alice).with(asUser(2, "Bob")))
                .andExpect(status().isNotFound());

        // 앨리스 것은 그대로다
        mvc.perform(get("/api/alm/me/filters").with(asUser(1, "Alice")))
                .andExpect(jsonPath("$[0].name").value("앨리스 것"));
    }

    @Test
    void 같은_이름은_한_사람_안에서만_막힌다() throws Exception {
        create(1, "내 것", "smart", "a");

        mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"내 것\",\"kind\":\"smart\",\"query\":\"b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("같은 이름의 필터가 있습니다"));

        // 다른 사람은 같은 이름을 쓸 수 있다
        create(2, "내 것", "smart", "b");

        // 이름을 남의 것이 아니라 내 다른 필터와 겹치게 고치는 것도 막힌다
        long other = create(1, "다른 것", "smart", "c");
        mvc.perform(put("/api/alm/me/filters/{id}", other).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"내 것\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("같은 이름의 필터가 있습니다"));

        // 제 이름 그대로 저장하는 것은 중복이 아니다
        mvc.perform(put("/api/alm/me/filters/{id}", other).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"다른 것\",\"query\":\"d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("d"));
    }

    @Test
    void AQL은_저장할_때_문법을_보고_위치까지_알려준다() throws Exception {
        // 못 여는 필터를 꽂아 두고 누를 때마다 400을 보느니 저장을 거절한다
        mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"깨진 것\",\"kind\":\"aql\",\"query\":\"status == done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("연산자를 모릅니다: =="))
                .andExpect(jsonPath("$.position").value(7))
                .andExpect(jsonPath("$.expected[0]").value("="));
        assertThat(filters.count()).isZero();

        // 같은 문자열도 smart면 통과한다 — 검사는 kind가 aql일 때만이다
        create(1, "스마트", "smart", "status == done");

        long ok = create(1, "멀쩡한 것", "aql", "status = done ORDER BY updated DESC");

        // 고칠 때도 같은 검사를 탄다
        mvc.perform(put("/api/alm/me/filters/{id}", ok).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"statuss = done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("필드를 모릅니다: statuss"));

        // 종류만 aql로 바꿔도 이미 저장돼 있던 문자열이 검사 대상이다
        long smart = create(1, "나중에 AQL", "smart", "status == done");
        mvc.perform(put("/api/alm/me/filters/{id}", smart).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"aql\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("연산자를 모릅니다: =="));
    }

    @Test
    void 이름과_종류와_질의를_검증한다() throws Exception {
        badRequest("{\"name\":\"  \",\"kind\":\"smart\",\"query\":\"a\"}", "필터 이름을 입력하세요");
        badRequest("{\"name\":\"" + "가".repeat(61) + "\",\"kind\":\"smart\",\"query\":\"a\"}",
                "필터 이름은 60자 이하여야 합니다");
        badRequest("{\"name\":\"x\",\"kind\":\"jql\",\"query\":\"a\"}", "필터 종류는 smart 또는 aql입니다");
        badRequest("{\"name\":\"x\",\"kind\":\"smart\",\"query\":\"\"}", "필터 질의를 입력하세요");
        badRequest("{\"name\":\"x\",\"kind\":\"smart\",\"query\":\"" + "가".repeat(4001) + "\"}",
                "필터 질의는 4000자 이하여야 합니다");
        assertThat(filters.count()).isZero();

        // 경계값은 통과한다
        create(1, "가".repeat(60), "smart", "가".repeat(4000));
    }

    // ── 도우미 ──

    private long create(long userId, String name, String kind, String query) throws Exception {
        String body = mvc.perform(post("/api/alm/me/filters").with(asUser(userId, "User" + userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new Create(name, kind, query))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private void badRequest(String json, String message) throws Exception {
        mvc.perform(post("/api/alm/me/filters").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(message));
    }

    private record Create(String name, String kind, String query) {}
}
