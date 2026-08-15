package com.platform.almbackend.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(value = "platform.grpc.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class GrpcServerLifecycle implements SmartLifecycle {
    private final AlmContentGrpcService contentService;

    @Value("${platform.grpc.port:9121}")
    private int port;

    private Server server;

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(contentService)
                    .addService(ProtoReflectionService.newInstance())
                    .build()
                    .start();
            log.info("ALM gRPC 서버 기동: :{}", port);
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

