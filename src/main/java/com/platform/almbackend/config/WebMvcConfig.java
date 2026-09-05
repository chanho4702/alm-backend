package com.platform.almbackend.config;

import com.platform.almbackend.security.AccountStatusInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 계정 상태 게이트를 ALM REST 표면 전체에 건다. 이 서비스의 REST는 전부 {@code /api/alm/**}이므로
 * 한 패턴으로 덮이고, actuator(헬스체크)와 내부 gRPC는 자연히 빠진다 — 상태를 확인하려고 org를 부르는
 * 게이트가 헬스체크까지 org에 묶으면 org가 죽을 때 이 서비스도 죽은 것으로 보고된다.
 *
 * <p>인터셉터를 고른 이유: 필터에서 던진 예외는 {@code @RestControllerAdvice}가 잡지 못해 오류 계약
 * ({@code {"error": ...}})을 손으로 다시 써야 한다. 인터셉터의 예외는 평소 경로를 그대로 탄다.
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AccountStatusInterceptor accountStatus;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accountStatus).addPathPatterns("/api/alm/**");
    }
}
