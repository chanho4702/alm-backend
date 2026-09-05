package com.platform.almbackend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

/**
 * `GET /v3/api-docs`로 나가는 OpenAPI 3 스펙. Swagger UI는 싣지 않는다 — 문서는 myFront 생성기가
 * 이 JSON을 읽어 만든다. 게이트웨이·nginx가 `/v3`를 라우팅하지 않으므로 클러스터 내부에서만 보인다.
 *
 * <p>오류 계약은 common-starter와 같은 `{"error": 메시지}`다(스키마 이름 {@code PlatformError}).
 * 오퍼레이션마다 손으로 적지 않고 {@link #commonErrorResponses()}가 일괄로 붙인다.
 */
@Configuration
public class OpenApiConfig {

    /** 인증 주체는 서버가 토큰에서 꺼낸다 — 요청 파라미터로 문서에 새면 안 된다 */
    static {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(AuthenticationPrincipal.class);
    }

    /**
     * 공통 오류 설명 — 세 서비스(wiki·alm·org)가 글자 그대로 공유한다(2026-09-05 팀 확정).
     * 문서 페이지가 나란히 놓이므로 임의로 바꾸지 않는다. 바꾸면 {@code OpenApiDocsTest}가 먼저 깨진다.
     */
    static final Map<String, String> ERROR_DESCRIPTIONS = Map.of(
            "400", "요청 검증 실패",
            "401", "인증 실패 — 토큰 없음·만료·무효",
            "403", "권한 없음",
            "404", "대상 없음",
            "409", "버전 충돌 — expectedVersion 불일치",
            "503", "권한 서비스(org) 불능");

    private static final String BEARER_SCHEME = "bearerAuth";
    private static final String ERROR_SCHEMA = "PlatformError";
    private static final String ERROR_REF = "#/components/schemas/" + ERROR_SCHEMA;

    @Bean
    OpenAPI almOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ALM API")
                        .version("0.1.0")
                        .description("""
                                프로젝트·이슈·스프린트·보드를 다루는 ALM 서비스의 REST API. \
                                모든 경로는 `/api/alm` 아래에 있고, 권한은 org-service가 프로젝트 단위로 판정한다. \
                                오류 응답은 `{"error": "메시지"}` 한 가지 형태다."""))
                .servers(List.of(new Server().url("/").description("게이트웨이 뒤 같은 오리진")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .tags(tags())
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("개인 API 토큰 `chanho_pat_…` 또는 세션 JWT"))
                        .addSchemas(ERROR_SCHEMA, errorSchema()));
    }

    /**
     * 코드가 실제로 내는 공통 오류만 붙인다. 401·403은 모든 경로에서 나고(인증 필수 + org-service 권한 판정),
     * 404는 경로 변수로 대상을 지목하는 경로에서, 409는 `expectedVersion`을 받는 PUT에서만 난다.
     *
     * <p>springdoc은 기본값으로 {@code @RestControllerAdvice}(common-starter의 예외 핸들러)가 다루는
     * 상태를 <b>모든</b> 오퍼레이션에 복사한다 — 그러면 GET에도 404·409가 달린다.
     * {@code springdoc.override-with-generic-response=false}(application.yml)로 그 복사를 끄고,
     * 여기서 규칙대로만 붙인다. wiki·org 서비스도 같은 방식이다.
     */
    @Bean
    OperationCustomizer commonErrorResponses() {
        return (operation, handlerMethod) -> {
            addError(operation, "401");
            addError(operation, "403");
            if (hasPathVariable(handlerMethod)) {
                addError(operation, "404");
            }
            if (hasRequestBody(handlerMethod)) {
                addError(operation, "400");
            }
            String conflict = conflictReason(handlerMethod);
            if (conflict != null) {
                addError(operation, "409", conflict);
            }
            if (dependsOnOrg(handlerMethod)) {
                addError(operation, "503");
            }
            return operation;
        };
    }

    /**
     * 409 사유. 낙관적 락(요청 본문의 {@code expectedVersion})은 자동 판별하고, 그 밖의 업무 충돌은
     * {@link ConflictResponse}가 사유를 준다. 둘 다인 엔드포인트는 두 사유를 이어 붙인다.
     * 어느 쪽도 아니면 409를 내지 않는 엔드포인트이므로 null이다.
     */
    private static String conflictReason(HandlerMethod handlerMethod) {
        String lock = isPut(handlerMethod) && hasOptimisticLock(handlerMethod)
                ? ERROR_DESCRIPTIONS.get("409")
                : null;
        ConflictResponse business = handlerMethod.getMethodAnnotation(ConflictResponse.class);
        if (lock == null) return business == null ? null : business.value();
        return business == null ? lock : lock + " / " + business.value();
    }

    private static void addError(Operation operation, String status) {
        addError(operation, status, ERROR_DESCRIPTIONS.get(status));
    }

    private static void addError(Operation operation, String status, String description) {
        if (operation.getResponses() == null) return;
        operation.getResponses().addApiResponse(status, new ApiResponse()
                .description(description)
                .content(new Content().addMediaType("application/json",
                        new MediaType().schema(new Schema<>().$ref(ERROR_REF)))));
    }

    private static Schema<?> errorSchema() {
        return new ObjectSchema()
                .name(ERROR_SCHEMA)
                .description("오류 응답. 메시지는 한국어이며 화면에 그대로 노출된다.")
                .addProperty("error", new StringSchema()
                        .description("사용자에게 보여줄 오류 메시지")
                        .example("이슈를 찾을 수 없습니다"))
                .addRequiredItem("error");
    }

    private static boolean hasPathVariable(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(PathVariable.class)) return true;
        }
        return false;
    }

    /** 본문을 받으면 bean validation·역직렬화가 400을 낼 수 있다. 파일 업로드도 본문이다 */
    private static boolean hasRequestBody(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (parameter.hasParameterAnnotation(RequestBody.class)) return true;
            if (parameter.hasParameterAnnotation(RequestPart.class)) return true;
            Class<?> type = parameter.getParameterType();
            if (MultipartFile.class.isAssignableFrom(type)) return true;
            if (type.isArray() && MultipartFile.class.isAssignableFrom(type.getComponentType())) return true;
        }
        return false;
    }

    /**
     * org-service gRPC로 권한을 판정하는 오퍼레이션인가 — 불능이면 {@code ServiceUnavailableException}이
     * 503으로 올라간다({@code GrpcPermissionClient}는 UNAVAILABLE·DEADLINE_EXCEEDED만 그렇게 다루고
     * 나머지는 fail-closed다). 프로젝트 권한이든 전역 관리자 판정이든 같은 클라이언트를 탄다.
     * 부르지 않는 소수는 {@link NoOrgDependency}로 표시해 둔다.
     */
    private static boolean dependsOnOrg(HandlerMethod handlerMethod) {
        return !AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), NoOrgDependency.class)
                && !AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), NoOrgDependency.class);
    }

    private static boolean isPut(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PutMapping.class) != null;
    }

    /** 요청 본문 레코드에 `expectedVersion`이 있으면 낙관적 락을 검사하는 엔드포인트다 */
    private static boolean hasOptimisticLock(HandlerMethod handlerMethod) {
        for (MethodParameter parameter : handlerMethod.getMethodParameters()) {
            if (!parameter.hasParameterAnnotation(RequestBody.class)) continue;
            Class<?> type = parameter.getParameterType();
            if (!type.isRecord()) continue;
            for (RecordComponent component : type.getRecordComponents()) {
                if ("expectedVersion".equals(component.getName())) return true;
            }
        }
        return false;
    }

    /**
     * 태그 = 생성되는 문서 페이지 하나. 리소스 하나를 통째로 맡는 컨트롤러는 클래스에 붙은
     * {@code @Tag(name, description)}이 곧 태그다. 한 컨트롤러가 여러 리소스를 담는 협업·설정만
     * 메서드마다 이름을 나눠 붙이고, 그 설명을 여기서 한 번 준다.
     */
    private static List<io.swagger.v3.oas.models.tags.Tag> tags() {
        return List.of(
                tag("Comments", "이슈 댓글과 멘션"),
                tag("Worklogs", "작업 시간 기록과 프로젝트 집계"),
                tag("Issue Links", "이슈 사이의 연결(차단·복제 등)"),
                tag("Status Categories", "상태 카테고리 레지스트리"),
                tag("Statuses", "상태 레지스트리와 사용량"),
                tag("Issue Types", "이슈 타입 레지스트리와 사용량"),
                tag("Priorities", "우선순위 레지스트리와 사용량"),
                tag("Link Types", "이슈 연결 타입 레지스트리와 사용량"),
                tag("Settings Schemes", "설정 스킴 정의와 기본 스킴 지정"),
                tag("Project Settings", "프로젝트에 적용되는 설정 스킴과 개별 재정의"));
    }

    private static io.swagger.v3.oas.models.tags.Tag tag(String name, String description) {
        return new io.swagger.v3.oas.models.tags.Tag().name(name).description(description);
    }
}
