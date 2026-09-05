package com.platform.almbackend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.almbackend.directory.DirectoryMember;
import com.platform.almbackend.directory.MemberDirectory;
import com.platform.almbackend.permission.PermissionDecision;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 계정 상태 게이트 — 정지·비활성·승인 대기 계정은 ALM REST 전체에서 막힌다(2026-09-05).
 *
 * <p>왜 필요한가: 권한 판정은 프로젝트를 건드리는 경로만 지킨다. 레지스트리·스킴 읽기처럼 프로젝트가
 * 없는 전역 읽기 엔드포인트는 grant를 묻지 않으므로, org REST가 `/me` 외 전부를 막아 둔 승인 대기 계정이
 * ALM에서는 설정을 그대로 읽을 수 있었다. 격리 기준을 org와 같게 맞춘다.
 *
 * <p>세 가지가 이 게이트의 전부다.
 * <ul>
 *   <li>{@code PENDING}·{@code SUSPENDED}·{@code DEACTIVATED} → 403, 문구는 전역 관리자 거부와 같다
 *       ({@link PermissionDecision#accountMessage(String)}가 한 곳에서 정한다).</li>
 *   <li><b>디렉터리에 없으면 통과.</b> org의 member 행은 그 사람의 첫 org 호출 때 생기는데 ALM이 먼저
 *       불릴 수 있다 — 없는 것을 막으면 정상 사용자가 미러링 순서에 따라 무작위로 차단된다. 이건
 *       org-service가 gRPC {@code CheckPermission}에서 쓰는 규칙과 같다.</li>
 *   <li>org 불능(UNAVAILABLE·DEADLINE) → 503. 장애를 "정지된 계정"으로 말하지 않는다.</li>
 * </ul>
 *
 * <p>그 밖의 조회 실패(org의 오류 응답 등)는 <b>통과</b>시킨다. 여기서 막으면 org 버그가 사용자에게
 * "당신 계정이 정지됐다"로 보이고, 이 게이트는 인가의 2차 방어일 뿐이라 권한이 필요한 경로는
 * {@code GrpcPermissionClient}가 여전히 fail-closed로 닫는다. 대신 warn으로 남긴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountStatusInterceptor implements HandlerInterceptor {

    private final MemberDirectory directory;

    // 권한 판정과 같은 30초다(GrpcPermissionClient). 대가도 같다: 방금 정지된 사람이 최대 30초 더
    // 들어올 수 있고, 방금 미러된 PENDING 계정이 그만큼 늦게 막힌다. 조회 실패는 캐시하지 않는다.
    private final Cache<Long, Optional<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long userId = currentUserId();
        if (userId == null) return true;
        Optional<String> status = cache.get(userId, this::status);
        String message = status.map(PermissionDecision::accountMessage).orElse(null);
        if (message != null) {
            log.warn("계정 상태로 차단: user={} status={} {} {}",
                    userId, status.orElse(""), request.getMethod(), request.getRequestURI());
            throw new ForbiddenException(message);
        }
        return true;
    }

    /**
     * 캐시를 비운다. 지금은 테스트 격리용이고, org가 상태 변경을 알려 주는 경로가 생기면
     * 그 훅이 여기를 부른다 — 그때까지 반영 지연은 30초다.
     */
    public void evictAll() {
        cache.invalidateAll();
    }

    /** org가 아는 상태. 그런 사람이 없으면 empty(통과), 못 물어봤으면 던진다(503) */
    private Optional<String> status(long userId) {
        MemberDirectory.Lookup lookup = directory.lookup(List.of(userId));
        switch (lookup.outcome()) {
            case UNAVAILABLE -> throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
            case FAILED -> {
                log.warn("계정 상태를 읽지 못해 통과시킨다: user={}", userId);
                return Optional.empty();
            }
            case OK -> {
                DirectoryMember member = lookup.members().get(userId);
                return Optional.ofNullable(member == null ? null : member.status());
            }
        }
        return Optional.empty();
    }

    /** JWT sub. 인증 자체는 시큐리티가 이미 강제했다 — 숫자가 아니면 컨트롤러가 400으로 답하게 둔다. */
    private static Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) return null;
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
