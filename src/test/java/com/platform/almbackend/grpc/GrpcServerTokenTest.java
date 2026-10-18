package com.platform.almbackend.grpc;

import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.common.grpc.GrpcAuthMode;
import com.platform.common.grpc.InternalTokenClientInterceptor;
import com.platform.common.grpc.InternalTokenServerInterceptor;
import com.platform.proto.alm.v1.AlmContentServiceGrpc;
import com.platform.proto.alm.v1.GetIssueContentRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 서비스 간 gRPC 토큰(P-01) — 콘텐츠 서버의 모든 서비스가 토큰 인터셉터를 지나는가 */
class GrpcServerTokenTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void teardown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }

    private AlmContentServiceGrpc.AlmContentServiceBlockingStub contentStub(GrpcAuthMode mode, String clientToken)
            throws IOException {
        var lifecycle = new GrpcServerLifecycle(
                new AlmContentGrpcService(mock(IssueRepository.class), mock(ProjectRepository.class)),
                new InternalTokenServerInterceptor(mode, "s3cret"), 0);
        String name = InProcessServerBuilder.generateName();
        var builder = InProcessServerBuilder.forName(name).directExecutor();
        lifecycle.services().forEach(builder::addService);
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor()
                .intercept(new InternalTokenClientInterceptor(clientToken)).build();
        return AlmContentServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void enforce면_토큰_없는_콘텐츠_호출을_UNAUTHENTICATED로_끊는다() throws IOException {
        var stub = contentStub(GrpcAuthMode.ENFORCE, "");
        assertThatThrownBy(() -> stub.getIssueContent(GetIssueContentRequest.newBuilder().setIssueId(1).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void enforce라도_맞는_토큰이면_서비스까지_닿는다() throws IOException {
        var stub = contentStub(GrpcAuthMode.ENFORCE, "s3cret");
        // 대역 저장소는 비어 있다 — 인증을 지나 서비스가 답한 NOT_FOUND여야 한다
        assertThatThrownBy(() -> stub.getIssueContent(GetIssueContentRequest.newBuilder().setIssueId(1).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }

    @Test
    void monitor는_토큰이_없어도_통과시킨다() throws IOException {
        var stub = contentStub(GrpcAuthMode.MONITOR, "");
        assertThatThrownBy(() -> stub.getIssueContent(GetIssueContentRequest.newBuilder().setIssueId(1).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
    }
}
