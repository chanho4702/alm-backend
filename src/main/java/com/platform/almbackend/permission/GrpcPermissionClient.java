package com.platform.almbackend.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class GrpcPermissionClient implements PermissionClient {
    private record CacheKey(long userId, long projectId, AlmAction action) {}

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    private final Cache<CacheKey, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public boolean isAllowed(long userId, long projectId, AlmAction action) {
        return cache.get(new CacheKey(userId, projectId, action), key -> {
            try {
                return deadline().checkPermission(CheckPermissionRequest.newBuilder()
                        .setUserId(key.userId())
                        .setResourceType(ResourceType.PROJECT)
                        .setResourceId(String.valueOf(key.projectId()))
                        .setAction(toProto(key.action()))
                        .build()).getAllowed();
            } catch (Exception e) {
                if (isUnavailable(e)) {
                    log.error("권한 서비스 불가 — 503 전파: user={} project={} action={}",
                            key.userId(), key.projectId(), key.action(), e);
                    throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다", e);
                }
                log.warn("권한 조회 실패 — fail-closed: user={} project={} action={}",
                        key.userId(), key.projectId(), key.action(), e);
                return false;
            }
        });
    }

    @Override
    public AccessScope accessibleProjects(long userId) {
        try {
            ListUserGrantsResponse response = deadline().listUserGrants(
                    ListUserGrantsRequest.newBuilder().setUserId(userId).build());
            if (response.getGrantsList().stream().anyMatch(grant -> grant.getResourceType() == ResourceType.GLOBAL)) {
                return AccessScope.global();
            }
            Set<Long> ids = response.getGrantsList().stream()
                    .filter(grant -> grant.getResourceType() == ResourceType.PROJECT)
                    .map(grant -> parseId(grant.getResourceId()))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            return AccessScope.of(ids);
        } catch (Exception e) {
            if (isUnavailable(e)) {
                throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다", e);
            }
            log.warn("프로젝트 grant 조회 실패 — fail-closed: user={}", userId, e);
            return AccessScope.of(Set.of());
        }
    }

    @Override
    public boolean grantProjectAdmin(long userId, long projectId) {
        try {
            return deadline().createGrant(CreateGrantRequest.newBuilder()
                    .setUserId(userId)
                    .setResourceType(ResourceType.PROJECT)
                    .setResourceId(String.valueOf(projectId))
                    .setRole(Role.ROLE_ADMIN)
                    .build()).getCreated();
        } catch (Exception e) {
            log.warn("프로젝트 생성자 ADMIN 부여 실패: user={} project={}", userId, projectId, e);
            return false;
        }
    }

    @Override
    public int revokeProjectGrants(long projectId) {
        try {
            return deadline().revokeGrant(RevokeGrantRequest.newBuilder()
                    .setResourceType(ResourceType.PROJECT)
                    .setResourceId(String.valueOf(projectId))
                    .build()).getRevoked();
        } catch (Exception e) {
            log.warn("프로젝트 grant 회수 실패(고아 grant 잔존): project={}", projectId, e);
            return 0;
        }
    }

    private PermissionServiceGrpc.PermissionServiceBlockingStub deadline() {
        return stub.withDeadlineAfter(2, TimeUnit.SECONDS);
    }

    private static Long parseId(String raw) {
        try { return Long.parseLong(raw); }
        catch (NumberFormatException e) { return null; }
    }

    private static boolean isUnavailable(Throwable e) {
        if (e instanceof StatusRuntimeException status) {
            return status.getStatus().getCode() == Status.Code.UNAVAILABLE
                    || status.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }

    private static Action toProto(AlmAction action) {
        return switch (action) {
            case VIEW -> Action.VIEW;
            case EDIT -> Action.EDIT;
            case ADMIN -> Action.ADMIN;
        };
    }
}

