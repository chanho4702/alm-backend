package com.platform.almbackend.directory;

import com.platform.proto.org.v1.GetMembersRequest;
import com.platform.proto.org.v1.GetMembersResponse;
import com.platform.proto.org.v1.MemberInfo;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code GetMembers}로 id → 이름·이메일을 읽는다. 없는 id는 응답에서 빠지므로 개수·순서를 요청과 맞추지 않는다.
 * 한 요청의 id 상한이 200이라 그 단위로 끊어 보낸다.
 *
 * <p>여기서는 던지지 않는다 — 예외를 그대로 올리면 알림 메일 한 통 때문에 발송 루프가 통째로 멈춘다.
 * 대신 {@link Outcome}으로 왜 비었는지 알리고, 그걸 403으로 볼지 503으로 볼지는 호출측이 정한다
 * (알림은 폴백, 계정 상태 게이트는 503).
 */
@Slf4j
public class GrpcMemberDirectory implements MemberDirectory {

    /** proto가 정한 한 요청의 id 상한 — 넘기면 INVALID_ARGUMENT다 */
    private static final int MAX_IDS = 200;

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    private final long deadlineSeconds;

    public GrpcMemberDirectory(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this(stub, 2);
    }

    public GrpcMemberDirectory(PermissionServiceGrpc.PermissionServiceBlockingStub stub, long deadlineSeconds) {
        this.stub = stub;
        this.deadlineSeconds = deadlineSeconds;
    }

    @Override
    public Lookup lookup(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Lookup.ok(Map.of());
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, DirectoryMember> found = new LinkedHashMap<>();
        Outcome outcome = Outcome.OK;
        for (int from = 0; from < unique.size(); from += MAX_IDS) {
            List<Long> chunk = unique.subList(from, Math.min(from + MAX_IDS, unique.size()));
            try {
                GetMembersResponse response = stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS)
                        .getMembers(GetMembersRequest.newBuilder().addAllIds(chunk).build());
                for (MemberInfo info : response.getMembersList()) {
                    found.put(info.getId(), toMember(info));
                }
            } catch (Exception e) {
                // 가용성 장애만 따로 센다 — 호출측이 "못 물어봤다"와 "물어봤는데 없더라"를 갈라야 한다.
                // 여러 묶음 중 하나만 실패해도 결과는 실패로 표시한다(부분 성공을 성공으로 읽으면
                // 빠진 사람이 "없는 사람"이 된다).
                if (isUnavailable(e)) {
                    log.warn("사용자 디렉터리 불능: users={}", chunk, e);
                    outcome = Outcome.UNAVAILABLE;
                } else {
                    log.warn("사용자 디렉터리 조회 실패: users={}", chunk, e);
                    if (outcome == Outcome.OK) outcome = Outcome.FAILED;
                }
            }
        }
        return new Lookup(outcome, found);
    }

    private static boolean isUnavailable(Throwable e) {
        if (e instanceof StatusRuntimeException status) {
            return status.getStatus().getCode() == Status.Code.UNAVAILABLE
                    || status.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED;
        }
        return false;
    }

    private static DirectoryMember toMember(MemberInfo info) {
        return new DirectoryMember(
                info.getId(), info.getDisplayName(), info.getEmail(), info.getStatus(), info.getKind());
    }
}
