package com.platform.almbackend.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.TestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * `/v3/api-docs`가 문서 생성기가 쓸 수 있는 상태인지 지킨다 — 태그·요약이 빠진 오퍼레이션이 하나라도
 * 있으면 생성된 페이지에 빈 칸이 생기고, 내부 전용 경로가 새면 공개 문서에 그대로 실린다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class OpenApiDocsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "options", "head", "trace");

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    JsonNode spec;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String body = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        spec = JSON.readTree(body);
    }

    @Test
    void 스펙은_토큰_없이_읽히고_경로가_들어_있다() {
        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("info").path("title").asText()).isEqualTo("ALM API");
        assertThat(spec.path("info").path("description").asText()).isNotBlank();
        assertThat(operations()).hasSizeGreaterThan(100);
    }

    @Test
    void 모든_오퍼레이션에_태그와_요약이_있다() {
        List<String> missing = new ArrayList<>();
        for (Operation operation : operations()) {
            JsonNode tags = operation.node().path("tags");
            if (!tags.isArray() || tags.isEmpty()) missing.add(operation.id() + " — 태그 없음");
            String summary = operation.node().path("summary").asText("");
            if (summary.isBlank()) missing.add(operation.id() + " — 요약 없음");
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void 쓰인_태그는_모두_설명을_가지고_중복되지_않는다() {
        Map<String, String> declared = new java.util.LinkedHashMap<>();
        for (JsonNode tag : spec.path("tags")) {
            String name = tag.path("name").asText();
            assertThat(declared).as("태그 %s가 중복 선언됨", name).doesNotContainKey(name);
            declared.put(name, tag.path("description").asText(""));
        }
        Set<String> used = new HashSet<>();
        for (Operation operation : operations()) {
            for (JsonNode tag : operation.node().path("tags")) used.add(tag.asText());
        }
        for (String name : used) {
            assertThat(declared).as("태그 %s에 설명이 없다", name).containsKey(name);
            assertThat(declared.get(name)).as("태그 %s에 설명이 없다", name).isNotBlank();
        }
    }

    @Test
    void 내부_전용_경로는_문서에_없다() {
        for (Iterator<String> it = spec.path("paths").fieldNames(); it.hasNext(); ) {
            String path = it.next();
            assertThat(path).doesNotStartWith("/internal").doesNotStartWith("/actuator");
            assertThat(path).startsWith("/api/alm");
        }
    }

    @Test
    void bearerAuth를_전역으로_요구한다() {
        JsonNode scheme = spec.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("description").asText()).contains("chanho_pat_");

        List<String> global = new ArrayList<>();
        for (JsonNode requirement : spec.path("security")) {
            requirement.fieldNames().forEachRemaining(global::add);
        }
        assertThat(global).contains("bearerAuth");
    }

    @Test
    void 공통_오류_응답이_붙는다() {
        JsonNode error = spec.path("components").path("schemas").path("PlatformError");
        assertThat(error.path("properties").path("error").path("type").asText()).isEqualTo("string");

        for (Operation operation : operations()) {
            JsonNode responses = operation.node().path("responses");
            assertThat(responses.has("401")).as("%s에 401이 없다", operation.id()).isTrue();
            assertThat(responses.has("403")).as("%s에 403이 없다", operation.id()).isTrue();
            if (operation.path().contains("{")) {
                assertThat(responses.has("404")).as("%s에 404가 없다", operation.id()).isTrue();
            }
        }
    }

    @Test
    void 본문을_받는_오퍼레이션에는_모두_400이_붙는다() {
        List<String> missing = new ArrayList<>();
        List<String> spurious = new ArrayList<>();
        for (Operation operation : operations()) {
            boolean hasBody = operation.node().has("requestBody");
            boolean has400 = operation.node().path("responses").has("400");
            if (hasBody && !has400) missing.add(operation.id());
            if (!hasBody && has400) spurious.add(operation.id());
        }
        assertThat(missing).as("본문을 받는데 400이 없다").isEmpty();
        assertThat(spurious).as("본문이 없는데 400이 붙었다").isEmpty();
    }

    /**
     * 503은 org-service 권한 판정이 있는 오퍼레이션에만 붙는다. 아래 목록은 org를 부르지 않는다고
     * 코드로 확인해 {@code @NoOrgDependency}로 표시한 것들이다 — 표식이 늘거나 줄면 여기서 먼저 깨진다.
     */
    @Test
    void org_권한을_타지_않는_오퍼레이션에만_503이_없다() {
        List<String> without503 = new ArrayList<>();
        for (Operation operation : operations()) {
            if (!operation.node().path("responses").has("503")) without503.add(operation.id());
        }
        assertThat(without503).containsExactlyInAnyOrder(
                // 대시보드 — 저장소만 쓴다
                "get /api/alm/dashboards", "post /api/alm/dashboards",
                "get /api/alm/dashboards/{id}", "put /api/alm/dashboards/{id}",
                "delete /api/alm/dashboards/{id}",
                // 개인 설정·공지 배너 읽기
                "get /api/alm/me/preferences", "put /api/alm/me/preferences", "get /api/alm/banner",
                // 내 알림 — 프로젝트 권한을 묻지 않는다
                "get /api/alm/notifications", "post /api/alm/notifications/{id}/read",
                "post /api/alm/notifications/read-all",
                // 레지스트리·스킴 읽기 — 로그인이면 된다(쓰기는 전역 관리자라 org를 탄다)
                "get /api/alm/settings/categories",
                "get /api/alm/settings/statuses", "get /api/alm/settings/statuses/usage",
                "get /api/alm/settings/link-types", "get /api/alm/settings/link-types/usage",
                "get /api/alm/settings/priorities", "get /api/alm/settings/priorities/usage",
                "get /api/alm/settings/issue-types", "get /api/alm/settings/issue-types/usage",
                "get /api/alm/settings/schemes", "get /api/alm/settings/schemes/{id}/projects/count");
    }

    @Test
    void 낙관적_락_PUT에만_409가_붙는다() {
        List<String> with409 = new ArrayList<>();
        for (Operation operation : operations()) {
            if (operation.node().path("responses").has("409")) with409.add(operation.method() + " " + operation.path());
        }
        assertThat(with409).containsExactlyInAnyOrder(
                "put /api/alm/projects/{projectId}",
                "put /api/alm/issues/{issueId}",
                "put /api/alm/sprints/{sprintId}",
                "put /api/alm/versions/{versionId}");
    }

    @Test
    void 인증_주체는_요청_파라미터로_새지_않는다() {
        for (Operation operation : operations()) {
            for (JsonNode parameter : operation.node().path("parameters")) {
                assertThat(parameter.path("name").asText())
                        .as("%s의 파라미터에 인증 주체가 노출됨", operation.id())
                        .isNotEqualTo("jwt");
            }
        }
    }

    private record Operation(String path, String method, JsonNode node) {
        String id() {
            return method + " " + path;
        }
    }

    private List<Operation> operations() {
        List<Operation> found = new ArrayList<>();
        JsonNode paths = spec.path("paths");
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String path = it.next();
            JsonNode item = paths.path(path);
            for (Iterator<String> methods = item.fieldNames(); methods.hasNext(); ) {
                String method = methods.next();
                if (!HTTP_METHODS.contains(method)) continue;
                found.add(new Operation(path, method, item.path(method)));
            }
        }
        return found;
    }
}
