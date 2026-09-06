package com.platform.almbackend.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.TestConfig;
import com.platform.almbackend.domain.Sprint;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
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

import java.time.LocalDate;
import java.util.Set;

import static com.platform.almbackend.TestAuth.asUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AQL 실행 계약 — 스펙 §6 벡터를 H2에서 끝까지 돌린다. 파싱만이 아니라 해석(이름→id)·Specification·
 * 접근 범위·보관 제외까지 한 줄의 AQL이 실제로 무엇을 고르는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class AqlQueryControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired WebApplicationContext context;
    MockMvc mvc;
    @Autowired ProjectRepository projects;
    @Autowired IssueRepository issues;
    @Autowired SprintRepository sprints;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired TestConfig.FakeMemberDirectory directory;

    private long almId;
    private long opsId;

    @BeforeEach
    void reset() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.reset();
        directory.reset();
        // 사람은 org가 원장이다 — 이름 해석은 이 표를 본다
        directory.put(1, "테스터", "tester@test.com", "ACTIVE");
        directory.put(2, "김찬호", "kim@test.com", "ACTIVE");
        directory.put(3, "이영희", "lee@test.com", "ACTIVE");
        directory.put(4, "박민수", "park@test.com", "ACTIVE");

        almId = project("alm", "ALM 제품");
        opsId = project("ops", "운영");
        long sprintId = sprints.save(Sprint.of(almId, 1, "스프린트 1")).getId();
        LocalDate today = LocalDate.now();

        // ALM-1 마감 이틀 뒤·높음·버그·김찬호
        create(almId, "로그인 버그", "설명", "bug", "todo", "high", 2L,
                "\"labels\":[\"backend\",\"auth\"],\"dueDate\":\"" + today.plusDays(2) + "\"");
        // ALM-2 진행 중·스프린트 안·본인 담당·예상 3.5시간
        create(almId, "보드 개선", "설명", "story", "inprogress", "medium", 1L,
                "\"labels\":[\"frontend\"],\"sprintId\":" + sprintId + ",\"estimateHours\":3.50");
        // ALM-3 완료·해결 있음·"결제"가 제목에 — 해결은 생성이 아니라 수정 때만 붙는다
        long resolved = create(almId, "결제 문서 정리", "설명", "task", "done", "low", 2L, "");
        mvc.perform(put("/api/alm/issues/{id}", resolved).with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"결제 문서 정리\",\"description\":\"설명\",\"type\":\"task\","
                                + "\"status\":\"done\",\"priority\":\"low\",\"assigneeId\":2,"
                                + "\"details\":{\"resolution\":\"DONE\"},\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        // ALM-4 담당자 없음·마감 하루 뒤
        create(almId, "검색 느림", "설명", "task", "todo", "lowest", null,
                "\"labels\":[\"api v2\"],\"dueDate\":\"" + today.plusDays(1) + "\"");
        // ALM-5 완료·박민수 담당(이름 중복 확장 시험에 쓴다)
        create(almId, "권한 정리", "설명", "task", "done", "medium", 4L, "\"labels\":[]");
        // ALM-6 보관 — 기본 검색에서 빠진다
        long archived = create(almId, "옛날 이슈", "설명", "task", "todo", "low", 3L, "\"labels\":[]");
        mvc.perform(post("/api/alm/issues/{id}/archive", archived).with(asUser(1, "테스터")))
                .andExpect(status().isOk());
        // OPS-1 다른 프로젝트
        create(opsId, "배포 자동화", "설명", "task", "todo", "medium", 3L, "\"labels\":[]");
    }

    // ── §6 테스트 벡터 ──

    @Test
    void 벡터1_상태_이름과_currentUser() throws Exception {
        JsonNode result = query("status = \"진행 중\" AND assignee = currentUser()", 1);
        assertThat(result.get("total").asLong()).isEqualTo(1);
        assertThat(result.get("items").get(0).get("title").asText()).isEqualTo("보드 개선");
        assertThat(result.get("echoedAql").asText()).isEqualTo("status = \"진행 중\" AND assignee = currentUser()");
    }

    @Test
    void 벡터2_괄호_OR와_두_기준_정렬() throws Exception {
        JsonNode result = query(
                "project = ALM AND (priority >= high OR due <= +3d) ORDER BY due ASC, priority DESC", 1);
        assertThat(titles(result)).containsExactly("검색 느림", "로그인 버그");
    }

    @Test
    void 벡터3_IN과_NOT() throws Exception {
        JsonNode result = query("labels IN (backend, \"api v2\") AND NOT type = 버그", 1);
        assertThat(titles(result)).containsExactly("검색 느림");
    }

    @Test
    void 벡터4_IS_EMPTY와_상태분류_부정() throws Exception {
        JsonNode result = query("sprint IS EMPTY AND statusCategory != complete", 1);
        assertThat(titles(result)).containsExactlyInAnyOrder("로그인 버그", "검색 느림", "배포 자동화");
    }

    @Test
    void 벡터5_텍스트_포함과_월초_이후와_담당자_있음() throws Exception {
        JsonNode result = query("text ~ 결제 AND created >= startOfMonth() AND assignee IS NOT EMPTY", 1);
        assertThat(titles(result)).containsExactly("결제 문서 정리");
    }

    @Test
    void 벡터6_한국어_별칭과_사람_이름() throws Exception {
        JsonNode result = query("상태 = 완료 AND 담당자 = 김찬호", 1);
        assertThat(titles(result)).containsExactly("결제 문서 정리");
    }

    @Test
    void 벡터7_상대날짜와_방향없는_정렬() throws Exception {
        // 방금 만든 이슈뿐이라 14일 전보다 오래된 건 없다
        assertThat(query("resolution IS EMPTY AND updated < -14d ORDER BY updated", 1).get("total").asLong())
                .isZero();
        // 반대쪽은 해결이 붙은 ALM-3만 빠진다
        JsonNode recent = query("resolution IS EMPTY AND updated > -14d ORDER BY updated", 1);
        assertThat(titles(recent)).doesNotContain("결제 문서 정리").hasSize(5);
    }

    // ── 해석 ──

    @Test
    void 같은_이름이_둘이면_IN으로_넓힌다() throws Exception {
        // org에 김찬호가 한 명 더 생긴다 — 프로젝트와 무관하게 디렉터리 전체에서 이름을 맞춘다
        directory.put(4, "김찬호", "kim2@test.com", "ACTIVE");
        JsonNode result = query("상태 = 완료 AND 담당자 = 김찬호", 1);
        assertThat(titles(result)).containsExactlyInAnyOrder("결제 문서 정리", "권한 정리");
    }

    @Test
    void 사람은_이메일_로컬파트_숫자id로도_찾는다() throws Exception {
        assertThat(query("assignee = kim@test.com", 1).get("total").asLong()).isEqualTo(2);
        assertThat(query("assignee = kim", 1).get("total").asLong()).isEqualTo(2);
        assertThat(query("assignee = 2", 1).get("total").asLong()).isEqualTo(2);
    }

    @Test
    void 이름이_부분일치로_잡히지_않는다() throws Exception {
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"assignee = 김찬\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("사용자를 찾을 수 없습니다: 김찬"));
    }

    @Test
    void 스프린트와_예상시간과_키로_찾는다() throws Exception {
        assertThat(titles(query("sprint = \"스프린트 1\"", 1))).containsExactly("보드 개선");
        assertThat(titles(query("estimate >= 3", 1))).containsExactly("보드 개선");
        assertThat(titles(query("key = ALM-1", 1))).containsExactly("로그인 버그");
        assertThat(query("key ~ alm", 1).get("total").asLong()).isEqualTo(5);
    }

    // ── 범위·보관 ──

    @Test
    void 접근_범위_밖의_프로젝트는_무엇을_쓰든_안_나온다() throws Exception {
        permissions.setAccessScope(AccessScope.of(Set.of(opsId)));
        assertThat(titles(query("", 1))).containsExactly("배포 자동화");
        // 볼 수 없는 프로젝트는 이름조차 풀리지 않는다
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"project = ALM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("프로젝트를 찾을 수 없습니다: ALM"));
    }

    @Test
    void 보관된_이슈는_기본으로_빠지고_archived로만_보인다() throws Exception {
        assertThat(titles(query("", 1))).doesNotContain("옛날 이슈").hasSize(6);
        assertThat(titles(query("archived = true", 1))).containsExactly("옛날 이슈");
        assertThat(titles(query("archived = true AND project = ALM", 1))).containsExactly("옛날 이슈");
        assertThat(query("archived = false", 1).get("total").asLong()).isEqualTo(6);
    }

    // ── NULL 안전 ──

    @Test
    void 부정_연산자는_JQL처럼_빈_값을_제외한다() throws Exception {
        // assignee != 2 에 담당자 미지정("검색 느림")은 안 들어간다
        assertThat(titles(query("project = ALM AND assignee != 2", 1)))
                .containsExactlyInAnyOrder("보드 개선", "권한 정리");
        // 넣으려면 명시한다
        assertThat(titles(query("project = ALM AND (assignee != 2 OR assignee IS EMPTY)", 1)))
                .containsExactlyInAnyOrder("보드 개선", "검색 느림", "권한 정리");
        // NOT IN도 같고, 라벨이 비면 labels != … 에서 빠진다
        assertThat(titles(query("project = ALM AND labels NOT IN (backend)", 1)))
                .containsExactlyInAnyOrder("보드 개선", "검색 느림");
    }

    @Test
    void 집합_여집합인_NOT은_빈_값을_포함한다() throws Exception {
        // 필드 연산자(!=)와 달리 NOT은 집합을 뒤집는다 — JQL도 둘을 가른다
        assertThat(titles(query("project = ALM AND NOT labels = backend", 1)))
                .containsExactlyInAnyOrder("보드 개선", "결제 문서 정리", "검색 느림", "권한 정리");
        assertThat(titles(query("project = ALM AND NOT assignee = 2", 1)))
                .containsExactlyInAnyOrder("보드 개선", "검색 느림", "권한 정리");
    }

    @Test
    void 포함_검색은_와일드카드_문자를_글자로_본다() throws Exception {
        create(almId, "100% 할인_코드", "설명", "task", "todo", "low", null, "\"labels\":[]");
        create(almId, "할인X코드", "설명", "task", "todo", "low", null, "\"labels\":[]");

        // '%'를 안 감싸면 전체가 매치된다
        assertThat(titles(query("text ~ \"%\"", 1))).containsExactly("100% 할인_코드");
        // '_'는 아무 한 글자가 아니라 밑줄 글자 그대로다
        assertThat(titles(query("text ~ \"할인_코드\"", 1))).containsExactly("100% 할인_코드");
    }

    @Test
    void org_디렉터리를_못_읽으면_사용자_없음이_아니라_503이다() throws Exception {
        directory.setFailed(true);
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"assignee = 김찬호\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("사용자 디렉터리를 읽을 수 없어 이름을 확인하지 못했습니다"));
    }

    @Test
    void 질의가_너무_크거나_깊거나_길면_400으로_거절한다() throws Exception {
        // 깊은 괄호 — 예전엔 StackOverflowError가 500이 됐다
        String deep = "(".repeat(200) + "status = done" + ")".repeat(200);
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new Body(deep, 0, 50))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("너무 깊게 중첩됐습니다 (최대 50단계)"));

        // 평탄한 AND는 깊이 제한에 안 걸려서 절 수를 따로 센다
        // 4000자 상한에 먼저 걸리지 않게 짧은 절로 250개를 만든다
        String many = String.join(" AND ", java.util.Collections.nCopies(250, "key = a"));
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new Body(many, 0, 50))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("조건이 너무 많습니다 (최대 200개)"));

        // 문자열 자체가 길면 파서까지 가지도 않는다
        String huge = "status = done AND " + "summary ~ \"" + "가".repeat(4000) + "\"";
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new Body(huge, 0, 50))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("AQL은 4000자 이하여야 합니다"));

        // 검증 엔드포인트도 같은 상한을 가진다
        mvc.perform(post("/api/alm/issues/query/validate").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new ValidateBody(huge))))
                .andExpect(status().isBadRequest());
    }

    // ── 페이징·정렬 ──

    @Test
    void 페이징은_total을_유지하고_기본_정렬은_수정일_역순이다() throws Exception {
        JsonNode page = query("", 1, 0, 2);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("total").asLong()).isEqualTo(6);
        assertThat(page.get("size").asInt()).isEqualTo(2);
        // 마지막에 만든 것이 맨 앞이다
        assertThat(page.get("items").get(0).get("title").asText()).isEqualTo("배포 자동화");
    }

    @Test
    void 정렬할_수_없는_필드는_거절한다() throws Exception {
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"ORDER BY project\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("정렬할 수 없는 필드입니다: project"))
                .andExpect(jsonPath("$.expected[0]").value("key"));
    }

    // ── 오류 계약(§3) ──

    @Test
    void 오류는_문구와_위치와_후보를_함께_준다() throws Exception {
        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"status == done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("연산자를 모릅니다: =="))
                .andExpect(jsonPath("$.position").value(7))
                .andExpect(jsonPath("$.expected[0]").value("="));

        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"statuss = done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("필드를 모릅니다: statuss"))
                .andExpect(jsonPath("$.position").value(0));

        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"priority ~ high\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'~'는 텍스트 필드에만 쓸 수 있습니다 (priority)"))
                // 틀린 건 필드가 아니라 연산자다 — 밑줄은 '~' 자리에 그어진다
                .andExpect(jsonPath("$.position").value(9));

        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"status > done\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'>'는 날짜·숫자 필드에만 쓸 수 있습니다 (status)"))
                .andExpect(jsonPath("$.position").value(7));

        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"due > yesterday\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("날짜 형식이 아닙니다: yesterday"))
                .andExpect(jsonPath("$.position").value(6));

        mvc.perform(post("/api/alm/issues/query").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"resolved > -7d\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("아직 지원하지 않는 필드입니다: resolved"));
    }

    // ── 검증·자동완성 ──

    @Test
    void 검증은_문법만_보고_AST를_돌려준다() throws Exception {
        mvc.perform(post("/api/alm/issues/query/validate").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aql\":\"상태 = 완료 ORDER BY due\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.fields[0]").value("status"))
                .andExpect(jsonPath("$.ast.where.kind").value("compare"))
                .andExpect(jsonPath("$.ast.orderBy[0].direction").value("asc"));

        // 값이 실재하는지는 검증하지 않는다 — 실행할 때 확인한다
        mvc.perform(post("/api/alm/issues/query/validate").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"status = 없는상태\"}"))
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(post("/api/alm/issues/query/validate").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"aql\":\"(status = done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("괄호를 닫아야 합니다"))
                .andExpect(jsonPath("$.position").value(14));
    }

    @Test
    void 자동완성_사전은_필드와_값_후보를_준다() throws Exception {
        String body = mvc.perform(get("/api/alm/issues/query/fields").with(asUser(1, "테스터")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keywords[0]").value("AND"))
                .andReturn().getResponse().getContentAsString();
        JsonNode fields = JSON.readTree(body).get("fields");
        JsonNode status = null;
        for (JsonNode field : fields) if ("status".equals(field.get("name").asText())) status = field;
        assertThat(status).isNotNull();
        assertThat(status.get("aliases").get(0).asText()).isEqualTo("상태");
        assertThat(status.get("values").toString()).contains("진행 중");
        // 지원하지 않는 필드는 사전에 없다
        assertThat(body).doesNotContain("\"resolved\"");
    }

    // ── 도우미 ──

    private JsonNode query(String aql, long userId) throws Exception {
        return query(aql, userId, 0, 50);
    }

    private JsonNode query(String aql, long userId, int page, int size) throws Exception {
        String body = mvc.perform(post("/api/alm/issues/query").with(asUser(userId, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(new Body(aql, page, size))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private record Body(String aql, int page, int size) {}

    private record ValidateBody(String aql) {}

    private java.util.List<String> titles(JsonNode result) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (JsonNode item : result.get("items")) titles.add(item.get("title").asText());
        return titles;
    }

    private long project(String key, String name) throws Exception {
        String body = mvc.perform(post("/api/alm/projects").with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private long create(long projectId, String title, String description, String type, String status,
                        String priority, Long assignee, String details) throws Exception {
        String body = mvc.perform(post("/api/alm/projects/{id}/issues", projectId).with(asUser(1, "테스터"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"description\":\"" + description
                                + "\",\"type\":\"" + type + "\",\"status\":\"" + status
                                + "\",\"priority\":\"" + priority + "\",\"assigneeId\":" + assignee
                                + ",\"details\":{" + details + "}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }
}
