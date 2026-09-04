package com.platform.almbackend;

import com.platform.almbackend.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 메일 서버가 없는 기본 설치 — 개인 설정이 그 사실을 알리고, 스위치를 켜도 발송을 시도하지 않는다 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class MailUnconfiguredTest {

    @Autowired WebApplicationContext context;
    @Autowired UserPreferenceRepository preferences;
    @MockitoBean JavaMailSender mailSender;

    MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        preferences.deleteAllInBatch();
    }

    @Test
    void 메일_서버가_없으면_mailConfigured가_false다() throws Exception {
        mvc.perform(get("/api/alm/me/preferences").with(asUser(9, "Dan")))
                .andExpect(jsonPath("$.mailConfigured").value(false))
                .andExpect(jsonPath("$.emailEnabled").value(false));
        mvc.perform(put("/api/alm/me/preferences").with(asUser(9, "Dan"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailEnabled").value(true))
                .andExpect(jsonPath("$.mailConfigured").value(false));
        verify(mailSender, after(300).never()).send(any(SimpleMailMessage.class));
    }
}
