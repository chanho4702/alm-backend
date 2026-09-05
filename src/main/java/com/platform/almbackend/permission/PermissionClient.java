package com.platform.almbackend.permission;

/**
 * 권한의 단일 진실 소스는 org-service다 — ALM은 JWT 역할로 인가를 자체 판단하지 않는다.
 * 프로젝트 권한도 전역 관리자도 여기를 거친다(2026-09-05, Keycloak realm 역할 ADMIN 판정 제거).
 */
public interface PermissionClient {

    /** 프로젝트 단위 판정 */
    PermissionDecision check(long userId, long projectId, AlmAction action);

    /** 전역 판정 — GLOBAL 리소스. 전역 관리자는 {@code checkGlobal(userId, ADMIN)}이다. */
    PermissionDecision checkGlobal(long userId, AlmAction action);

    AccessScope accessibleProjects(long userId);

    boolean grantProjectAdmin(long userId, long projectId);

    int revokeProjectGrants(long projectId);
}
