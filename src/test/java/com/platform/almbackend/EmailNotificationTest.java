package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.NotificationRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이메일 알림 채널 — 메일 서버를 준 컨텍스트다(기본 컨텍스트에서는 host가 비어 채널이 꺼져 있다,
 * {@link MailUnconfiguredTest}). 스위치를 켠 사람에게만, 커밋 뒤에 한 통 나간다.
 */
@SpringBootTest(properties = "spring.mail.host=smtp.test")
@ActiveProfiles("test")
@Import(TestConfig.class)
class EmailNotificationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired NotificationRepository notifications;
    @Autowired UserPreferenceRepository preferences;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.FakeMemberDirectory directory;
    @MockitoBean JavaMailSender mailSender;

    MockMvc mvc;
    long projectId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAllInBatch();
        preferences.deleteAllInBatch();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        directory.reset();
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"mail\",\"name\":\"메일\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
    }

    private void assignIssueToBob() throws Exception {
        createIssueAssignedToBob();
    }

    /** @return 만든 이슈의 (id, version) */
    private long[] createIssueAssignedToBob() throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"배정\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"assigneeId\":2,\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new long[]{JSON.readTree(body).get("id").asLong(), JSON.readTree(body).get("version").asLong()};
    }

    private void enableMailForBob() throws Exception {
        enableMailFor(2, "Bob");
    }

    private void enableMailFor(long id, String name) throws Exception {
        mvc.perform(put("/api/alm/me/preferences").with(asUser(id, name))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void 개인_설정_응답에_스위치와_서버_구성_여부가_실린다() throws Exception {
        mvc.perform(get("/api/alm/me/preferences").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.emailEnabled").value(false))
                .andExpect(jsonPath("$.mailConfigured").value(true))
                .andExpect(jsonPath("$.startPage").value("home"));
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.mailConfigured").value(true));
        // emailEnabled를 안 보내는 기존 프론트 요청이 스위치를 끄지 않는다
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startPage\":\"projects\"}"))
                .andExpect(jsonPath("$.emailEnabled").value(true));
    }

    @Test
    void 스위치를_켠_수신자에게_커밋_뒤_한_통_나간다() throws Exception {
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk());

        assignIssueToBob();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());
        SimpleMailMessage message = sent.getValue();
        assertThat(message.getTo()).containsExactly("bob@test.com");
        assertThat(message.getSubject()).startsWith("[ALM] ").contains("Alice");
        assertThat(message.getText())
                .contains("/projects/" + projectId + "/issues?issue=MAIL-1");
    }

    /**
     * 메일은 플레인 텍스트라 앱의 색 있는 아이콘을 실을 수 없다 — 종류는 제목 접두 이모지가,
     * 상태는 본문 한 줄이 대신한다. 이모지만으로 뜻을 전하지 않고 늘 이름과 함께 쓴다.
     */
    @Test
    void 제목에_종류_이모지가_붙는다() throws Exception {
        enableMailForBob();
        assignIssueToBob();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());
        assertThat(sent.getValue().getSubject()).startsWith("[ALM] 📌 ").contains("Alice");
    }

    @Test
    void 상태_변경_메일은_이전과_이후_상태를_의미_이모지와_함께_싣는다() throws Exception {
        enableMailForBob();
        long[] issue = createIssueAssignedToBob();

        mvc.perform(put("/api/alm/issues/{id}", issue[0]).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"배정\",\"description\":\"\",\"type\":\"task\",\"status\":\"done\","
                                + "\"priority\":\"MEDIUM\",\"assigneeId\":2,\"details\":{},"
                                + "\"expectedVersion\":" + issue[1] + "}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        // 배정(생성) 한 통 + 상태 변경 한 통
        verify(mailSender, timeout(3000).times(2)).send(sent.capture());
        SimpleMailMessage statusMail = sent.getAllValues().get(1);
        assertThat(statusMail.getSubject()).startsWith("[ALM] 🔄 ");
        assertThat(statusMail.getText()).contains("상태: 할 일 → ✅ 완료");
    }

    @Test
    void 스위치가_꺼져_있으면_알림함만_남고_메일은_없다() throws Exception {
        assignIssueToBob();

        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("ASSIGNED"));
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void 주소를_모르면_보내지_않는다() throws Exception {
        // 토큰에 email이 없는 사용자는 스위치를 켜도 보낼 곳이 없다
        mvc.perform(put("/api/alm/me/preferences").with(TestAuth.asUserWithoutEmail(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true));

        assignIssueToBob();

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
        mvc.perform(get("/api/alm/notifications").with(asUser(2, "Bob")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * 주소의 정본은 org-service다(2026-09-05). 개인 설정에 남은 주소는 로그인 때 찍힌 스냅샷이라
     * org에서 이메일을 바꾸면 낡는다 — 발송 시점에 디렉터리를 읽는 이유다.
     */
    @Test
    void 디렉터리_주소가_스냅샷보다_우선한다() throws Exception {
        enableMailForBob(); // 스냅샷은 bob@test.com
        directory.put(2, "Bob", "bob@org.example", "ACTIVE");

        assignIssueToBob();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("bob@org.example");
        // 쓰기 트랜잭션이 남의 서비스를 기다리지 않는다 — 조회는 커밋 뒤 발송 스레드에서 한다
        assertThat(directory.calls()).isNotEmpty()
                .allSatisfy(call -> assertThat(call.inTransaction()).isFalse());
    }

    /**
     * 워처가 여럿인 이슈의 상태가 바뀌면 알림도 여럿이다. 주소 조회는 그 수만큼 왕복하지 않고
     * {@code GetMembers(ids[])} 한 번이며, 커밋 뒤에 일어난다.
     */
    @Test
    void 수신자가_여럿이면_디렉터리를_커밋_뒤_한_번만_읽는다() throws Exception {
        enableMailFor(1, "Alice");
        enableMailForBob();
        long[] issue = createIssueAssignedToBob(); // 배정 메일 한 통(Bob)
        verify(mailSender, timeout(3000)).send(any(SimpleMailMessage.class));

        // 배정 건의 조회 기록을 지우고 상태 변경만 본다
        directory.reset();
        directory.put(1, "Alice", "alice@org.example", "ACTIVE");
        directory.put(2, "Bob", "bob@org.example", "ACTIVE");

        // 제3자가 상태를 바꾸면 워처 둘(보고자 Alice·담당자 Bob)에게 간다
        mvc.perform(put("/api/alm/issues/{id}", issue[0]).with(asUser(3, "Carol"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"배정\",\"description\":\"\",\"type\":\"task\",\"status\":\"done\","
                                + "\"priority\":\"MEDIUM\",\"assigneeId\":2,\"details\":{},"
                                + "\"expectedVersion\":" + issue[1] + "}"))
                .andExpect(status().isOk());

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000).times(3)).send(sent.capture());
        assertThat(sent.getAllValues().subList(1, 3))
                .extracting(message -> message.getTo()[0])
                .containsExactlyInAnyOrder("alice@org.example", "bob@org.example");

        // 계정 상태 게이트도 같은 디렉터리를 쓰지만 그건 요청자 한 명짜리 조회다 — 발송용 묶음 조회는 한 번뿐
        List<TestConfig.FakeMemberDirectory.Call> batched = directory.calls().stream()
                .filter(call -> call.ids().size() > 1)
                .toList();
        assertThat(batched).hasSize(1);
        assertThat(batched.get(0).ids()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(directory.calls()).allSatisfy(call -> assertThat(call.inTransaction()).isFalse());
    }

    /** org가 잠깐 불능이어도 알림 메일은 나간다 — 주소 조회는 인가 결정이 아니다 */
    @Test
    void 디렉터리를_못_읽으면_스냅샷으로_보낸다() throws Exception {
        enableMailForBob();
        directory.setUnavailable(true);

        assignIssueToBob();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("bob@test.com");
    }

    /** 디렉터리에 있지만 이메일이 비어 있으면 스냅샷으로 떨어진다 */
    @Test
    void 디렉터리에_주소가_없으면_스냅샷으로_보낸다() throws Exception {
        enableMailForBob();
        directory.put(2, "Bob", "", "ACTIVE");

        assignIssueToBob();

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(3000)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("bob@test.com");
    }

    /** 떠난 사람에게는 스냅샷 주소가 남아 있어도 보내지 않는다 */
    @Test
    void 비활성된_계정에는_보내지_않는다() throws Exception {
        enableMailForBob();
        directory.put(2, "Bob", "bob@org.example", "DEACTIVATED");

        assignIssueToBob();

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
        // 알림함에는 남는다(본인이 REST로 못 읽는 것은 계정 상태 게이트의 몫 — AccountStatusGateTest)
        assertThat(notifications.count()).isEqualTo(1);
    }
}
