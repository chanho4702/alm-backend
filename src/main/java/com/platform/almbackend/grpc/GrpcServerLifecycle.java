package com.platform.almbackend.grpc;

import com.platform.common.grpc.InternalTokenServerInterceptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * search-service용 콘텐츠 gRPC 서버(:9121, 내부망 전용).
 *
 * <p><b>서비스 간 인증(P-01)</b>: 모든 서비스 정의가 common-starter의 토큰 인터셉터를 지난다. 기본 모드는
 * monitor(토큰이 없거나 틀려도 통과 + 분당 한 줄 경고) — 호출자(search-service)가 토큰을 보내는 것을 로그로
 * 확인한 뒤 {@code PLATFORM_GRPC_AUTH_MODE=enforce}로 올린다.
 */
@Component
@ConditionalOnProperty(value = "platform.grpc.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {
    private final AlmContentGrpcService contentService;
    private final InternalTokenServerInterceptor tokenInterceptor;
    private final int port;

    private Server server;

    public GrpcServerLifecycle(AlmContentGrpcService contentService,
                               InternalTokenServerInterceptor platformGrpcServerInterceptor,
                               @Value("${platform.grpc.port:9121}") int port) {
        this.contentService = contentService;
        this.tokenInterceptor = platformGrpcServerInterceptor;
        this.port = port;
    }

    /** 서버에 올릴 서비스 정의 — 전부 토큰 인터셉터를 감싼다. 테스트가 in-process 서버에 같은 구성을 올린다. */
    List<ServerServiceDefinition> services() {
        return List.of(
                ServerInterceptors.intercept(contentService, tokenInterceptor),
                ServerInterceptors.intercept(ProtoReflectionService.newInstance(), tokenInterceptor));
    }

    @Override
    public void start() {
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(port);
            services().forEach(builder::addService);
            server = builder.build().start();
            log.info("ALM gRPC 서버 기동: :{} (토큰 검사 {})", port, tokenInterceptor.mode());
        } catch (IOException e) {
            throw new IllegalStateException("ALM gRPC 서버 기동 실패 :" + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            log.info("ALM gRPC 서버 종료: :{}", port);
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
