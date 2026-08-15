package com.platform.almbackend.event;

import com.platform.proto.events.v1.EventEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;

@Component
@Slf4j
@ConditionalOnProperty(value = "platform.events.enabled", havingValue = "true", matchIfMissing = true)
public class RedisStreamEventPublisher implements EventPublisher {
    private static final long MAXLEN = 100_000;
    private final StringRedisTemplate redis;
    private final String stream;

    public RedisStreamEventPublisher(
            StringRedisTemplate redis,
            @Value("${platform.events.stream:platform:events:v1}") String stream) {
        this.redis = redis;
        this.stream = stream;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verifyStreamSupport() {
        try {
            Properties info = redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info("server"));
            String version = info == null ? null : info.getProperty("redis_version");
            if (version == null) {
                log.warn("Redis 버전을 확인하지 못했습니다 — 스트림 지원 여부 미확인");
                return;
            }
            if (Integer.parseInt(version.split("\\.")[0]) < 5) {
                log.error("연결된 Redis {}는 스트림(XADD)을 지원하지 않습니다 — ALM 색인 이벤트가 유실됩니다", version);
            } else {
                log.info("ALM 이벤트 스트림 대상 Redis {} — 스트림 지원 확인", version);
            }
        } catch (Exception e) {
            log.warn("Redis 스트림 지원 확인 실패(발행 시 재확인됨)", e);
        }
    }

    @Override
    public void publish(EventEnvelope event) {
        byte[] streamKey = stream.getBytes(StandardCharsets.UTF_8);
        Map<byte[], byte[]> fields = Map.of(
                "payload".getBytes(StandardCharsets.UTF_8), event.toByteArray());
        redis.execute((RedisCallback<RecordId>) (RedisConnection connection) ->
                connection.streamCommands().xAdd(MapRecord.create(streamKey, fields),
                        XAddOptions.maxlen(MAXLEN).approximateTrimming(true)));
    }
}

