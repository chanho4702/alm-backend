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
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
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
}
