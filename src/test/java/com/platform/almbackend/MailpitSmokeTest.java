package com.platform.almbackend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.NotificationRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.UserPreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 SMTP 전송 스모크 — 목 발송기가 아니라 진짜 JavaMailSender가 로컬 Mailpit(11025)으로 보내고,
 * Mailpit API(18025)에서 도착을 확인한다. 상시 테스트가 아니라 수동 검증용: `ALM_MAILPIT_SMOKE=1`일 때만 돈다.
 *   docker run -d --name mailpit-smoke -p 127.0.0.1:11025:1025 -p 127.0.0.1:18025:8025 axllent/mailpit
 */
@SpringBootTest(properties = {
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=11025",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "platform.alm.mail.from=alm@smoke.local",
        "platform.alm.mail.public-url=http://localhost/alm",
})
@ActiveProfiles("test")
@Import(TestConfig.class)
@EnabledIfEnvironmentVariable(named = "ALM_MAILPIT_SMOKE", matches = "1")
class MailpitSmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired NotificationRepository notifications;
    @Autowired UserPreferenceRepository preferences;
    @Autowired TestConfig.FakePermissionClient permissions;

    @Test
    void 실제_SMTP로_배정_알림_메일이_Mailpit에_도착한다() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        notifications.deleteAllInBatch();
        preferences.deleteAllInBatch();
        issues.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);

        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"smoke\",\"name\":\"스모크\",\"description\":\"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long projectId = JSON.readTree(body).get("id").asLong();

        // Bob이 한 번 다녀가며 주소 스냅샷 + 스위치 on
        mvc.perform(put("/api/alm/me/preferences").with(asUser(2, "Bob"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk());

        // Alice가 Bob에게 배정 → ASSIGNED 알림 + 메일
        mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"메일 스모크\",\"description\":\"\",\"type\":\"task\",\"status\":\"todo\",\"priority\":\"MEDIUM\",\"assigneeId\":2,\"details\":{}}"))
                .andExpect(status().isCreated());

        HttpClient http = HttpClient.newHttpClient();
        JsonNode found = null;
        for (int i = 0; i < 20 && found == null; i++) {
            Thread.sleep(500);
            HttpResponse<String> res = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:18025/api/v1/messages")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            for (JsonNode m : JSON.readTree(res.body()).path("messages")) {
                if (m.path("Subject").asText().startsWith("[ALM]")) found = m;
            }
        }
        assertThat(found).as("Mailpit에 [ALM] 메일이 도착해야 한다").isNotNull();
        assertThat(found.path("To").get(0).path("Address").asText()).isEqualTo("bob@test.com");
        assertThat(found.path("From").path("Address").asText()).isEqualTo("alm@smoke.local");

        HttpResponse<String> detail = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:18025/api/v1/message/" + found.path("ID").asText())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String text = JSON.readTree(detail.body()).path("Text").asText();
        assertThat(text).contains("/projects/" + projectId + "/issues?issue=SMOKE-1");
        System.out.println("[mailpit] subject=" + found.path("Subject").asText());
        System.out.println("[mailpit] text=\n" + text);
    }
}
