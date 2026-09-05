package com.platform.almbackend.permission;

import com.platform.common.error.ForbiddenException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 전역 관리자 판정 — org-service {@code CheckPermission(GLOBAL, ADMIN)} 하나가 진실 소스다(2026-09-05).
 * 그 전에는 Keycloak realm 역할 {@code ADMIN}(JWT roles)으로 봤다: 같은 사람에 대해 wiki와 ALM이 서로
 * 다른 답을 낼 수 있는 구조였다. 부트스트랩 관리자는 org 시드({@code PLATFORM_BOOTSTRAP_ADMIN_ID})가 만든다.
 *
 * <p>쓰는 법은 {@code @PreAuthorize("@globalAdmin.check(authentication)")}. 판정이 세 갈래인 것이 요점이다:
 * <ul>
 *   <li>허용 → 통과</li>
 *   <li>거부 → 403 {@code {"error": ...}}, 계정 상태로 막힌 것이면 그 사실을 그대로 말한다(승인 대기·정지)</li>
 *   <li>org 불능 → 503. <b>권한 없음으로 오인하지 않는다</b> — org가 죽은 동안 관리자에게
 *       "당신은 관리자가 아닙니다"라고 답하면 사람이 잘못된 조치를 한다.</li>
 * </ul>
 * 503은 {@link GrpcPermissionClient}가 던지는 {@code ServiceUnavailableException}이 그대로 올라간다.
 */
@Component("globalAdmin")
@RequiredArgsConstructor
public class GlobalAdminGuard {

    private final PermissionClient permissions;

    /** SpEL에서 부른다 — 통과하면 true, 아니면 던진다(사유를 담은 403 / 장애면 503) */
    public boolean check(Authentication authentication) {
        long userId = userId(authentication);
        PermissionDecision decision = permissions.checkGlobal(userId, AlmAction.ADMIN);
        if (decision.allowed()) return true;
        String message = decision.accountMessage();
        throw new ForbiddenException(message == null ? "전역 관리자만 할 수 있습니다" : message);
    }

    private static long userId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("전역 관리자만 할 수 있습니다");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("JWT sub는 숫자 사용자 ID여야 합니다", e);
        }
    }
}
