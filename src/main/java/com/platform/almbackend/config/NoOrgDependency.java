package com.platform.almbackend.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 오퍼레이션은 org-service를 부르지 않는다 — 따라서 권한 서비스 불능(503)이 날 수 없다.
 *
 * <p><b>문서 전용 표식이며 보안 통제가 아니다.</b> 붙이거나 떼도 동작은 바뀌지 않는다.
 * {@link OpenApiConfig}의 OperationCustomizer가 503을 붙일지 말지 판단할 때만 읽는다.
 *
 * <p>alm의 거의 모든 엔드포인트는 org gRPC로 권한을 판정하므로(프로젝트 권한 또는
 * {@code @PreAuthorize("@globalAdmin.check(...)")}) 503이 기본값이고, 예외만 여기에 표시한다.
 * 새 엔드포인트는 표시하지 않는 쪽이 안전하다 — 없는 503을 적는 편이 있는 503을 빠뜨리는 것보다 낫다.
 *
 * <p>표시한 메서드에 나중에 권한 판정이 생기면 표식을 떼야 한다. {@code OpenApiDocsTest}가
 * 503이 없는 오퍼레이션 목록을 그대로 확인하므로, 목록이 바뀌면 테스트가 먼저 깨진다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface NoOrgDependency {
}
