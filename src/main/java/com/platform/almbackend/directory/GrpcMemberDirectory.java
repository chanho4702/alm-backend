package com.platform.almbackend.directory;

import com.platform.proto.org.v1.GetMembersRequest;
import com.platform.proto.org.v1.GetMembersResponse;
import com.platform.proto.org.v1.MemberInfo;
import com.platform.proto.org.v1.PermissionServiceGrpc;
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
 * <p>실패는 삼키고 빈 결과를 준다 — 여기서 503을 올리면 알림 메일 한 통 때문에 발송 루프가 통째로 멈춘다.
 * 권한 판정({@link com.platform.almbackend.permission.GrpcPermissionClient})의 fail-closed와는 성격이
 * 다른 경로다: 알림 주소를 못 읽는 것은 인가 결정이 아니다.
 */
@Slf4j
public class GrpcMemberDirectory implements MemberDirectory {

    /** proto가 정한 한 요청의 id 상한 — 넘기면 INVALID_ARGUMENT다 */
    private static final int MAX_IDS = 200;

    private final PermissionServiceGrpc.PermissionServiceBlockingStub stub;

    public GrpcMemberDirectory(PermissionServiceGrpc.PermissionServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    public Map<Long, DirectoryMember> members(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        List<Long> unique = new ArrayList<>(new LinkedHashSet<>(ids));
        Map<Long, DirectoryMember> found = new LinkedHashMap<>();
        for (int from = 0; from < unique.size(); from += MAX_IDS) {
            List<Long> chunk = unique.subList(from, Math.min(from + MAX_IDS, unique.size()));
            try {
                GetMembersResponse response = stub.withDeadlineAfter(2, TimeUnit.SECONDS)
                        .getMembers(GetMembersRequest.newBuilder().addAllIds(chunk).build());
                for (MemberInfo info : response.getMembersList()) {
                    found.put(info.getId(), toMember(info));
                }
            } catch (Exception e) {
                log.warn("사용자 디렉터리 조회 실패 — 스냅샷으로 폴백: users={}", chunk, e);
            }
        }
        return found;
    }

    private static DirectoryMember toMember(MemberInfo info) {
        return new DirectoryMember(
                info.getId(), info.getDisplayName(), info.getEmail(), info.getStatus(), info.getKind());
    }
}
