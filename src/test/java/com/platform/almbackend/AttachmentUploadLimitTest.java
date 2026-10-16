package com.platform.almbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 첨부 한도 계약(F) — 실제 톰캣 multipart 파싱을 거친다(MockMvc는 multipart 한도를 적용하지 않는다).
 * 파일 한도를 1MB로 낮춰 파일 한도 = 1MB, 요청 한도 = 2MB(경계·헤더 몫 1MB)를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=1MB")
@ActiveProfiles("test")
@Import({TestConfig.class, AttachmentUploadLimitTest.StubJwt.class})
class AttachmentUploadLimitTest {
    private static final int MB = 1024 * 1024;

    @LocalServerPort int port;
    @Autowired MultipartConfigElement multipart;

    /** 서명 검증 없이 subject=1 토큰을 만든다 — 한도 검사는 인증 뒤(DispatcherServlet)에서 일어난다 */
    @TestConfiguration
    static class StubJwt {
        @Bean
        @Primary
        JwtDecoder stubJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("1")
                    .claim("name", "Alice")
                    .claim("email", "alice@test.com")
                    .claim("roles", List.of("USER"))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        }
    }

    @Test
    void 요청_한도는_파일_한도에_1MB를_더한_값이다() {
        assertThat(multipart.getMaxFileSize()).isEqualTo(MB);
        assertThat(multipart.getMaxRequestSize()).isEqualTo(2L * MB);
    }

    @Test
    void 한도를_넘는_파일은_413과_오류_JSON이다() throws Exception {
        HttpResponse<String> response = upload(MB + 200 * 1024);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(new ObjectMapper().readTree(response.body()).get("error").asText())
                .isEqualTo("첨부 파일은 1MB까지 올릴 수 있습니다");
    }

    @Test
    void 한도와_딱_맞는_파일은_multipart_한도에_걸리지_않는다() throws Exception {
        // 없는 이슈라 업로드 자체는 404다 — 중요한 것은 413이 아니라 서비스까지 도달했다는 것
        HttpResponse<String> response = upload(MB);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> upload(int bytes) throws Exception {
        String boundary = "----alm" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[bytes]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/alm/issues/999999/attachments"))
                .header("Authorization", "Bearer test")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
