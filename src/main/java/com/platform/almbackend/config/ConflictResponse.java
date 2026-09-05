package com.platform.almbackend.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 엔드포인트가 낙관적 락과 별개로 실제 409를 낸다는 표시와 그 사유. {@link OpenApiConfig}의
 * OperationCustomizer가 읽어 문서에 409 응답을 붙인다. org-service와 같은 패턴이다.
 *
 * <p>사유는 서비스가 던지는 {@code ConflictException} 메시지와 같게 적는다 — 그 메시지가 그대로
 * {@code {"error": …}}로 나가 화면에 뜬다. 한 엔드포인트가 여러 사유로 409를 내면 {@code " / "}로 잇는다.
 *
 * <p>낙관적 락 409는 이 표식이 아니라 요청 본문의 {@code expectedVersion}으로 자동 판별한다. 둘 다인
 * 엔드포인트(버전 수정)는 두 사유가 합쳐져 나간다.
 *
 * <p>Swagger의 {@code @ApiResponse}를 직접 쓰지 않는 이유: 오퍼레이션에 {@code @ApiResponse}가 하나라도
 * 붙으면 springdoc이 반환 타입에서 자동으로 만들던 성공 응답(200/201)을 더 이상 넣지 않는다.
 * 성공 응답 스키마를 손으로 다시 적으면 반환 타입이 바뀔 때 조용히 어긋난다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConflictResponse {

    /** 화면에 그대로 나갈 수 있는 한국어 사유. 여러 개면 {@code " / "}로 잇는다. */
    String value();
}
