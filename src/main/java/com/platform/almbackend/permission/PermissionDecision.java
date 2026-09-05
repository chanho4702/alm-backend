package com.platform.almbackend.permission;

/**
 * org-service의 권한 판정 결과. 거부 사유(common-proto 0.16.0 {@code denied_reason})까지 실어
 * 사용자에게 할 말을 정한다 — "승인 대기 중"과 "권한이 없다"는 다음 행동이 다르다(관리자에게 승인을
 * 요청할 것인가, 리소스 관리자에게 권한을 요청할 것인가).
 *
 * <p><b>이 값으로 장애를 판단하지 않는다.</b> org 불능은 gRPC 상태 코드(UNAVAILABLE/DEADLINE_EXCEEDED)로
 * 오고 {@link com.platform.common.error.ServiceUnavailableException}이 되어 503으로 나간다. 이 레코드는
 * 언제나 "정상적으로 판정한 결과"다. 모르는 사유는 일반 거부로 다룬다 — 값은 뒤에 늘 수 있다.
 */
public record PermissionDecision(boolean allowed, String deniedReason) {

    public static final String PENDING = "PENDING";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String DEACTIVATED = "DEACTIVATED";

    public PermissionDecision {
        deniedReason = deniedReason == null ? "" : deniedReason;
    }

    public static PermissionDecision allow() {
        return new PermissionDecision(true, "");
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(false, reason);
    }

    /**
     * 계정 상태 때문에 막혔다면 그 사실을 그대로 알리는 한국어 문구, 권한이 모자란 것뿐이면 null.
     * 호출측이 null일 때 자기 맥락에 맞는 문구를 쓴다("전역 관리자만 할 수 있습니다" 같은).
     */
    public String accountMessage() {
        return switch (deniedReason) {
            case PENDING -> "승인 대기 중인 계정입니다";
            case SUSPENDED -> "정지된 계정입니다";
            case DEACTIVATED -> "비활성된 계정입니다";
            default -> null;
        };
    }
}
