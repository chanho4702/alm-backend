package com.platform.almbackend.config;

import com.platform.almbackend.directory.GrpcMemberDirectory;
import com.platform.almbackend.directory.MemberDirectory;
import com.platform.almbackend.permission.GrpcPermissionClient;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT 디코더(JWKS + issuer/audience 검증)와 roles→ROLE_ 변환기는 common-starter가 준다(S-02).
 * 여기에는 이 서비스만의 것 — 경로 정책과 org gRPC 채널 — 만 남긴다.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // OpenAPI 스펙은 토큰 없이 읽는다 — 게이트웨이·nginx가 /v3를 라우팅하지 않아 클러스터 내부 전용이다
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }

    @Bean(destroyMethod = "shutdown")
    @Qualifier("orgChannel")
    ManagedChannel orgChannel(
            @Value("${platform.org-grpc.host}") String host,
            @Value("${platform.org-grpc.port}") int port) {
        // 콜드 스타트 방지: 첫 요청이 이름 해석·연결 수립에 1초 넘게 걸려 DEADLINE_EXCEEDED(→503)가 나던 것을
        // 시작 시 연결을 선점(getState(true))하고 keepalive로 유휴 끊김을 막아 없앤다(2026-09-05 실측).
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .keepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .build();
        channel.getState(true);
        return channel;
    }

    @Bean
    @ConditionalOnMissingBean(PermissionClient.class)
    PermissionClient permissionClient(@Qualifier("orgChannel") ManagedChannel channel,
                                      @Value("${platform.org-grpc.deadline-seconds:5}") long deadlineSeconds) {
        // waitForReady: 연결이 아직 없으면 데드라인 안에서 기다린다(콜드 스타트에 즉시 UNAVAILABLE로 떨어지지 않게)
        return new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel).withWaitForReady(), deadlineSeconds);
    }

    /** 사람의 이름·이메일도 org-service가 원장이다 — 권한과 같은 채널을 쓴다 */
    @Bean
    @ConditionalOnMissingBean(MemberDirectory.class)
    MemberDirectory memberDirectory(@Qualifier("orgChannel") ManagedChannel channel,
                                    @Value("${platform.org-grpc.deadline-seconds:5}") long deadlineSeconds) {
        return new GrpcMemberDirectory(PermissionServiceGrpc.newBlockingStub(channel).withWaitForReady(), deadlineSeconds);
    }
}
