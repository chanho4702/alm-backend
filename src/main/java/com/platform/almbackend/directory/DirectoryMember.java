package com.platform.almbackend.directory;

/**
 * org-service가 아는 사람 한 명. {@code status}는 {@code PENDING|ACTIVE|SUSPENDED|DEACTIVATED},
 * {@code kind}는 {@code HUMAN|AGENT}이고 이메일이 없으면 빈 문자열이다(proto 계약 그대로).
 * 값이 뒤에 늘 수 있으므로 문자열로 두고, 아는 값만 분기한다.
 */
public record DirectoryMember(long id, String displayName, String email, String status, String kind) {

    public static final String DEACTIVATED = "DEACTIVATED";

    public DirectoryMember {
        displayName = displayName == null ? "" : displayName;
        email = email == null ? "" : email.trim();
        status = status == null ? "" : status;
        kind = kind == null ? "" : kind;
    }

    /** 이 계정은 떠났다 — 알림을 보낼 곳이 아니다 */
    public boolean deactivated() {
        return DEACTIVATED.equals(status);
    }

    public boolean hasEmail() {
        return !email.isEmpty();
    }
}
