package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 인박스(V40) — 커서 페이징·필터·미루기(해제 시각이 지나면 위로·읽지 않음으로)·보관·일괄 처리·본인 것만.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class NotificationInboxTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ME = 2;
    private static final Instant BASE = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestConfig.FakePermissionClient permissions;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        permissions.reset();
        jdbc.update("delete from notification");
    }

    @AfterEach
    void tearDown() {
        permissions.reset();
    }

    /** n분 뒤에 온 알림 */
    private long insert(long userId, String type, int minute, boolean read) {
        Timestamp at = Timestamp.from(BASE.plus(minute, ChronoUnit.MINUTES));
        jdbc.update("insert into notification (user_id, issue_id, issue_key, actor_id, type, detail, is_read, created_at, sort_at) "
                + "values (?, null, ?, 1, ?, null, ?, ?, ?)", userId, "AL-" + minute, type, read, at, at);
        return jdbc.queryForObject("select max(id) from notification", Long.class);
    }

    private JsonNode inbox(String query) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/alm/notifications/inbox" + query).with(asUser(ME, "Bob")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    @Test
    void 커서로_끝까지_넘기면_최신순으로_겹치지도_빠지지도_않는다() throws Exception {
        for (int i = 0; i < 7; i++) insert(ME, "COMMENTED", i, false);
        insert(ME, "COMMENTED", 3, false);   // 같은 시각 두 건 — id가 순서를 가른다
        insert(99, "COMMENTED", 10, false);  // 남의 것

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = inbox("?limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(ids(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(8).doesNotHaveDuplicates();
        // sortAt 내림차순, 같으면 id 내림차순
        List<Long> sorted = jdbc.queryForList(
                "select id from notification where user_id = ? order by sort_at desc, id desc", Long.class, ME);
        assertThat(seen).isEqualTo(sorted);
        assertThat(inbox("").get("unreadCount").asLong()).isEqualTo(8);
    }

    @Test
    void 필터는_읽지_않음_멘션_배정_자동화를_고른다() throws Exception {
        long mention = insert(ME, "MENTIONED", 1, false);
        long assigned = insert(ME, "ASSIGNED", 2, true);
        long automation = insert(ME, "AUTOMATION", 3, false);
        long comment = insert(ME, "COMMENTED", 4, true);

        assertThat(ids(inbox("?filter=all"))).containsExactly(comment, automation, assigned, mention);
        assertThat(ids(inbox("?filter=unread"))).containsExactly(automation, mention);
        assertThat(ids(inbox("?filter=mentions"))).containsExactly(mention);
        assertThat(ids(inbox("?filter=assigned"))).containsExactly(assigned);
        assertThat(ids(inbox("?filter=automation"))).containsExactly(automation);
        mvc.perform(get("/api/alm/notifications/inbox?filter=spam").with(asUser(ME, "Bob")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("알 수 없는 알림 필터")));
        mvc.perform(get("/api/alm/notifications/inbox?cursor=xyz").with(asUser(ME, "Bob")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("잘못된 커서입니다"));
        mvc.perform(get("/api/alm/notifications/inbox?limit=101").with(asUser(ME, "Bob")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 미루면_인박스에서_빠지고_해제_시각이_지나면_읽지_않음으로_맨_위에_돌아온다() throws Exception {
        long old = insert(ME, "COMMENTED", 0, true);
        long newer = insert(ME, "COMMENTED", 30, false);
        String until = Instant.now().plus(2, ChronoUnit.HOURS).toString();

        mvc.perform(post("/api/alm/notifications/{id}/snooze", old).with(asUser(ME, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"" + until + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozedUntil").exists())
                .andExpect(jsonPath("$.read").value(false));
        assertThat(ids(inbox(""))).containsExactly(newer);
        assertThat(ids(inbox("?filter=snoozed"))).containsExactly(old);
        assertThat(inbox("").get("unreadCount").asLong()).isEqualTo(1); // 미룬 동안은 배지에 안 센다
        // 옛 목록 API도 같은 규칙
        mvc.perform(get("/api/alm/notifications").with(asUser(ME, "Bob")))
                .andExpect(jsonPath("$.length()").value(1));

        // 해제 시각이 지났다(시계 대신 행을 과거로)
        Timestamp passed = Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES));
        jdbc.update("update notification set snoozed_until = ?, sort_at = ? where id = ?", passed, passed, old);
        JsonNode page = inbox("");
        assertThat(ids(page)).containsExactly(old, newer); // 해제 시각 자리 = 맨 위
        assertThat(page.get("items").get(0).get("read").asBoolean()).isFalse();
        assertThat(page.get("unreadCount").asLong()).isEqualTo(2);
        assertThat(ids(inbox("?filter=snoozed"))).isEmpty();
    }

    @Test
    void 미루기_취소는_원래_자리로_검증은_400_남의_것은_403() throws Exception {
        long a = insert(ME, "COMMENTED", 0, false);
        long b = insert(ME, "COMMENTED", 1, false);
        mvc.perform(post("/api/alm/notifications/{id}/snooze", a).with(asUser(ME, "Bob"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"until\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/alm/notifications/{id}/snooze", a).with(asUser(ME, "Bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.snoozedUntil").doesNotExist());
        assertThat(ids(inbox(""))).containsExactly(b, a);

        mvc.perform(post("/api/alm/notifications/{id}/snooze", a).with(asUser(ME, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"until\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("미루기 시각은 지금보다 뒤여야 합니다"));
        mvc.perform(post("/api/alm/notifications/{id}/snooze", a).with(asUser(ME, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"" + Instant.now().plus(400, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("미루기는 1년 뒤까지만 할 수 있습니다"));
        mvc.perform(post("/api/alm/notifications/{id}/snooze", a).with(asUser(7, "Eve"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("본인 알림만 미룰 수 있습니다"));
        mvc.perform(post("/api/alm/notifications/{id}/archive", 9_999_999).with(asUser(ME, "Bob")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 보관은_삭제가_아니라_보관함으로_가고_되돌릴_수_있다_보관한_것은_미룰_수_없다() throws Exception {
        long a = insert(ME, "MENTIONED", 0, false);
        long b = insert(ME, "MENTIONED", 1, false);
        mvc.perform(post("/api/alm/notifications/{id}/archive", a).with(asUser(ME, "Bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.archivedAt").exists());
        assertThat(ids(inbox(""))).containsExactly(b);
        assertThat(ids(inbox("?filter=mentions"))).containsExactly(b);
        assertThat(ids(inbox("?filter=archived"))).containsExactly(a);
        assertThat(jdbc.queryForObject("select count(*) from notification", Long.class)).isEqualTo(2);
        mvc.perform(post("/api/alm/notifications/{id}/snooze", a).with(asUser(ME, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"" + Instant.now().plus(1, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("보관한 알림은 미룰 수 없습니다"));

        mvc.perform(post("/api/alm/notifications/{id}/unarchive", a).with(asUser(ME, "Bob"))).andExpect(status().isOk());
        assertThat(ids(inbox(""))).containsExactly(b, a);
    }

    @Test
    void 일괄_처리는_본인_것만_세고_결과와_배지를_돌려준다() throws Exception {
        long a = insert(ME, "COMMENTED", 0, false);
        long b = insert(ME, "COMMENTED", 1, false);
        long c = insert(ME, "COMMENTED", 2, false);
        long theirs = insert(99, "COMMENTED", 3, false);

        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(ME, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + b + "," + theirs + "," + a + "],\"action\":\"read\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.unreadCount").value(1));
        assertThat(jdbc.queryForObject("select is_read from notification where id = ?", Boolean.class, theirs)).isFalse();

        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(ME, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "],\"action\":\"unread\"}"))
                .andExpect(jsonPath("$.updated").value(1)).andExpect(jsonPath("$.unreadCount").value(2));
        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(ME, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + c + "],\"action\":\"archive\"}"))
                .andExpect(jsonPath("$.updated").value(2)).andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(ids(inbox(""))).containsExactly(b);
        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(ME, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + c + "],\"action\":\"unarchive\"}"))
                .andExpect(jsonPath("$.updated").value(2));

        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(ME, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "],\"action\":\"delete\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("알 수 없는 일괄 처리")));
        mvc.perform(post("/api/alm/notifications/bulk").with(asUser(ME, "Bob")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[],\"action\":\"read\"}"))
                .andExpect(status().isBadRequest());
    }
}
