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
    /** GLOBAL이면 resourceId는 빈 문자열이다(proto 계약) */
    private record CacheKey(long userId, ResourceType type, String resourceId, AlmAction action) {}

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;
    // 판정을 30초 캐시한다 — 매 요청 gRPC 왕복을 피하는 값이다. 대가는 반영 지연이고, grant 회수만이
    // 아니라 **계정 상태 전이도 그만큼 늦는다**: 방금 정지·비활성된 사람이 최대 30초 더 하던 일을
    // 이어갈 수 있다(그 사이 새 요청은 캐시된 allowed를 본다). 즉시 차단이 필요해지면 캐시를 줄이거나
    // org가 무효화를 알리는 경로를 먼저 만든다 — 값만 늘리는 결정은 이 지연을 키운다.
    private final Cache<CacheKey, PermissionDecision> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    private final long deadlineSeconds;

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this(stub, 2);
    }

    public GrpcPermissionClient(PermissionServiceGrpc.PermissionServiceBlockingStub stub, long deadlineSeconds) {
        this.stub = stub;
        this.deadlineSeconds = deadlineSeconds;
    }

    @Override
    public PermissionDecision check(long userId, long projectId, AlmAction action) {
        return decide(new CacheKey(userId, ResourceType.PROJECT, String.valueOf(projectId), action));
    }

    @Override
    public PermissionDecision checkGlobal(long userId, AlmAction action) {
        return decide(new CacheKey(userId, ResourceType.GLOBAL, "", action));
    }

    private PermissionDecision decide(CacheKey key) {
        return cache.get(key, k -> {
            try {
                CheckPermissionResponse response = deadline().checkPermission(CheckPermissionRequest.newBuilder()
                        .setUserId(k.userId())
                        .setResourceType(k.type())
                        .setResourceId(k.resourceId())
                        .setAction(toProto(k.action()))
                        .build());
                return response.getAllowed()
                        ? PermissionDecision.allow()
                        : PermissionDecision.deny(response.getDeniedReason());
            } catch (Exception e) {
                // 가용성 장애만 503으로 올린다 — 나머지는 fail-closed다. 둘을 뭉뚱그리면
                // org가 죽은 동안 사용자에게 "당신은 권한이 없다"고 거짓말하거나(전자),
                // 진짜 거부를 열어 준다(후자).
                if (isUnavailable(e)) {
                    log.error("권한 서비스 불가 — 503 전파: user={} resource={}/{} action={}",
                            k.userId(), k.type(), k.resourceId(), k.action(), e);
                    throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다", e);
                }
                log.warn("권한 조회 실패 — fail-closed: user={} resource={}/{} action={}",
                        k.userId(), k.type(), k.resourceId(), k.action(), e);
                return PermissionDecision.deny("");
            }
        });
    }

    /**
     * 볼 수 있는 프로젝트 범위. 실패하면 <b>빈 범위</b>다(fail-closed) — 목록·검색이 "권한 없음"이 아니라
     * "결과 없음"으로 보이므로 조용하지만, 남의 프로젝트를 흘리는 것보다는 낫다. 그래서 warn으로 남긴다:
     * 사용자가 "내 프로젝트가 사라졌다"고 할 때 이 로그가 유일한 단서다. 가용성 장애는 여기서도 503이다.
     */
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
                log.error("권한 서비스 불가 — 503 전파: user={} (grant 목록)", userId, e);
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
        return stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS);
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
