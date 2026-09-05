package com.platform.almbackend.permission;

import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.org.v1.CheckPermissionRequest;
import com.platform.proto.org.v1.CheckPermissionResponse;
import com.platform.proto.org.v1.PermissionServiceGrpc;
import com.platform.proto.org.v1.ResourceType;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * gRPC 클라이언트의 분기 — 캐시 키와 장애 판별. 컨트롤러 테스트의 페이크로는 볼 수 없는 자리라
 * 진짜 스텁을 in-process 서버에 붙여 확인한다.
 */
class GrpcPermissionClientTest {

    /** 요청을 그대로 기록하고, 전역이면 거부·프로젝트면 허용으로 답하는 org 대역 */
    private static final class FakeOrg extends PermissionServiceGrpc.PermissionServiceImplBase {
        private final List<CheckPermissionRequest> seen = new ArrayList<>();
        private Status failWith;

        @Override
        public void checkPermission(CheckPermissionRequest request,
                                    StreamObserver<CheckPermissionResponse> observer) {
            seen.add(request);
            if (failWith != null) {
                observer.onError(failWith.asRuntimeException());
                return;
            }
            boolean global = request.getResourceType() == ResourceType.GLOBAL;
            observer.onNext(CheckPermissionResponse.newBuilder()
                    .setAllowed(!global)
                    .setDeniedReason(global ? "NO_GRANT" : "")
                    .build());
            observer.onCompleted();
        }
    }

    private FakeOrg org;
    private Server server;
    private ManagedChannel channel;
    private GrpcPermissionClient client;

    @BeforeEach
    void setup() throws IOException {
        org = new FakeOrg();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(org).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        client = new GrpcPermissionClient(PermissionServiceGrpc.newBlockingStub(channel));
    }

    @AfterEach
    void teardown() throws InterruptedException {
        channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    /**
     * 같은 사용자·같은 action이라도 전역 판정과 프로젝트 판정은 다른 질문이다. 캐시 키가 리소스를
     * 구분하지 않으면 프로젝트 ADMIN 하나로 전역 관리자가 되거나 그 반대가 된다.
     */
    @Test
    void 전역_판정과_프로젝트_판정은_캐시가_섞이지_않는다() {
        assertThat(client.check(1, 42, AlmAction.ADMIN).allowed()).isTrue();
        assertThat(client.checkGlobal(1, AlmAction.ADMIN).allowed()).isFalse();

        assertThat(org.seen).hasSize(2);
        assertThat(org.seen.get(0).getResourceType()).isEqualTo(ResourceType.PROJECT);
        assertThat(org.seen.get(0).getResourceId()).isEqualTo("42");
        assertThat(org.seen.get(1).getResourceType()).isEqualTo(ResourceType.GLOBAL);
        assertThat(org.seen.get(1).getResourceId()).isEmpty();
    }

    /** 프로젝트가 다르면 다른 질문이다 — 하나로 답하면 남의 프로젝트가 열린다 */
    @Test
    void 프로젝트가_다르면_다시_묻는다() {
        client.check(1, 42, AlmAction.VIEW);
        client.check(1, 43, AlmAction.VIEW);

        assertThat(org.seen).hasSize(2);
        assertThat(org.seen).extracting(CheckPermissionRequest::getResourceId)
                .containsExactly("42", "43");
    }

    /** 같은 질문은 캐시가 답한다 — 매 요청 왕복하지 않는 것이 캐시의 목적이다 */
    @Test
    void 같은_질문은_한_번만_묻는다() {
        client.checkGlobal(1, AlmAction.ADMIN);
        client.checkGlobal(1, AlmAction.ADMIN);

        assertThat(org.seen).hasSize(1);
    }

    @Test
    void 거부_사유를_그대로_싣는다() {
        assertThat(client.checkGlobal(1, AlmAction.ADMIN).deniedReason()).isEqualTo("NO_GRANT");
    }

    @Test
    void UNAVAILABLE은_503이고_캐시되지_않는다() {
        org.failWith = Status.UNAVAILABLE;

        assertThatThrownBy(() -> client.checkGlobal(1, AlmAction.ADMIN))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("권한 서비스에 연결할 수 없습니다");

        org.failWith = null;
        assertThat(client.checkGlobal(1, AlmAction.ADMIN).allowed()).isFalse();
        assertThat(org.seen).hasSize(2); // 장애 응답을 캐시했다면 두 번째 호출이 없다
    }

    /** 가용성 장애가 아닌 오류는 fail-closed — 권한을 열어 주지 않는다 */
    @Test
    void 다른_gRPC_오류는_거부로_닫는다() {
        org.failWith = Status.INTERNAL;

        PermissionDecision decision = client.check(1, 42, AlmAction.EDIT);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.accountMessage()).isNull();
    }
}
