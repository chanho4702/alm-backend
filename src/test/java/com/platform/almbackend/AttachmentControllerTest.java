package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.repository.IssueAttachmentRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static com.platform.almbackend.TestAuth.asUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이슈 첨부 계약. 테스트 프로필은 로컬 파일 저장소를 쓴다 — 운영은 S3 호환(MinIO)이며
 * 저장소 구현만 갈린다(AttachmentStorage). 다운로드는 항상 attachment 처분으로 내려
 * 브라우저가 파일을 실행하지 않게 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class AttachmentControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired IssueAttachmentRepository attachments;
    @Autowired TestConfig.FakePermissionClient permissions;

    private long projectId;
    private long issueId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        attachments.deleteAllInBatch();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.setAllowed(true);
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"alm\",\"name\":\"ALM 제품\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        projectId = JSON.readTree(body).get("id").asLong();
        String issue = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "Alice"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"작업\",\"description\":\"\",\"type\":\"TASK\","
                                + "\"status\":\"todo\",\"priority\":\"MEDIUM\",\"details\":{}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        issueId = JSON.readTree(issue).get("id").asLong();
    }

    @Test
    void 올리고_목록에서_보고_내려받는다() throws Exception {
        byte[] bytes = "hello attachment".getBytes(StandardCharsets.UTF_8);
        String body = mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "메모.txt", "text/plain", bytes))
                        .with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filename").value("메모.txt"))
                .andExpect(jsonPath("$.sizeBytes").value(bytes.length))
                // 클라이언트가 보낸 타입을 믿지 않는다 — 내용으로 판별해 모르면 octet-stream
                .andExpect(jsonPath("$.contentType").value("application/octet-stream"))
                .andExpect(jsonPath("$.uploadedBy").value(1))
                .andReturn().getResponse().getContentAsString();
        long attachmentId = JSON.readTree(body).get("id").asLong();

        mvc.perform(get("/api/alm/issues/{id}/attachments", issueId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(attachmentId));

        mvc.perform(get("/api/alm/attachments/{id}", attachmentId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename*=UTF-8''%EB%A9%94%EB%AA%A8.txt"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void 이미지는_내용으로_판별하고_인라인은_안전한_형식만_허용한다() throws Exception {
        String png = mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "shot.png", "application/octet-stream", PNG))
                        .with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn().getResponse().getContentAsString();
        long pngId = JSON.readTree(png).get("id").asLong();
        mvc.perform(get("/api/alm/attachments/{id}/inline", pngId).with(asUser(1, "Alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "inline; filename*=UTF-8''shot.png"));

        String svg = mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "x.svg", "image/svg+xml",
                                "<svg onload=alert(1)/>".getBytes(StandardCharsets.UTF_8)))
                        .with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long svgId = JSON.readTree(svg).get("id").asLong();
        mvc.perform(get("/api/alm/attachments/{id}/inline", svgId).with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("인라인 표시를 허용하지 않는 첨부 형식입니다"));
    }

    @Test
    void 지우면_목록과_다운로드에서_사라진다() throws Exception {
        String body = mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "a.bin", null, new byte[]{1, 2, 3}))
                        .with(asUser(1, "Alice")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long attachmentId = JSON.readTree(body).get("id").asLong();

        mvc.perform(delete("/api/alm/attachments/{id}", attachmentId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/alm/attachments/{id}", attachmentId).with(asUser(1, "Alice")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/alm/issues/{id}/attachments", issueId).with(asUser(1, "Alice")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 빈_파일은_거부하고_권한이_없으면_403이다() throws Exception {
        mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
                        .with(asUser(1, "Alice")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("빈 파일은 올릴 수 없습니다"));

        permissions.setAllowed(false);
        mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}))
                        .with(asUser(9, "Eve")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 이슈를_지우면_첨부도_사라진다() throws Exception {
        mvc.perform(multipart("/api/alm/issues/{id}/attachments", issueId)
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1}))
                        .with(asUser(1, "Alice")))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/alm/issues/{id}", issueId).with(asUser(1, "Alice")))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals(0, attachments.count());
    }
}
