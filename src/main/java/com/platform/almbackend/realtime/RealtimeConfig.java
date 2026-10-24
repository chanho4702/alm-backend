package com.platform.almbackend.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.project.ProjectService;
import com.platform.common.error.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;

/**
 * 프로젝트 실시간 스트림 배선. 팬아웃은 {@code platform.alm.realtime.fanout}: {@code redis}(기본 — 노드 여럿, Redis Streams
 * {@code alm:realtime:v1}) · {@code local}(한 JVM — 테스트·단일 노드).
 */
@Configuration
public class RealtimeConfig {

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(name = "platform.alm.realtime.fanout", havingValue = "redis", matchIfMissing = true)
    RedisRealtimeBus redisRealtimeBus(StringRedisTemplate redis, ObjectMapper json,
                                      @Value("${platform.alm.realtime.stream:alm:realtime:v1}") String key,
                                      @Value("${platform.alm.realtime.max-len:10000}") long maxLen,
                                      @Value("${platform.alm.realtime.read-block:2s}") Duration block,
                                      @Value("${platform.alm.realtime.publish-queue:1000}") int queue) {
        return new RedisRealtimeBus(redis, json, key, maxLen, block, queue);
    }

    @Bean
    @ConditionalOnProperty(name = "platform.alm.realtime.fanout", havingValue = "local")
    LocalRealtimeBus localRealtimeBus(@Value("${platform.alm.realtime.max-len:10000}") int capacity) {
        return new LocalRealtimeBus(capacity, Clock.systemUTC());
    }

    @Bean
    RealtimeSignalListener realtimeSignalListener(RealtimeBus bus) {
        return new RealtimeSignalListener(bus);
    }

    /** 권한 재확인 — org 불능은 판정 보류(UNKNOWN), 그 밖의 실패는 canView가 이미 fail-closed(false)로 돌려준다 */
    @Bean
    ProjectStreamHub.ProjectAccess projectStreamAccess(ProjectService projects) {
        return (userId, projectId) -> {
            try {
                return projects.canView(userId, projectId) ? ProjectStreamHub.Access.ALLOWED : ProjectStreamHub.Access.DENIED;
            } catch (ServiceUnavailableException e) {
                return ProjectStreamHub.Access.UNKNOWN;
            }
        };
    }

    @Bean(destroyMethod = "close")
    HubRuntime projectStreamRuntime(RealtimeBus bus, ProjectStreamHub.ProjectAccess access, ObjectMapper json,
                                @Value("${platform.alm.realtime.per-user-cap:5}") int perUser,
                                @Value("${platform.alm.realtime.global-cap:500}") int global,
                                @Value("${platform.alm.realtime.queue-capacity:100}") int queue,
                                @Value("${platform.alm.realtime.replay-limit:1000}") int replay,
                                @Value("${platform.alm.realtime.heartbeat:15s}") Duration heartbeat,
                                @Value("${platform.alm.realtime.timeout:30m}") Duration timeout,
                                @Value("${platform.alm.realtime.write-stall:20s}") Duration stall,
                                @Value("${platform.alm.realtime.recheck-interval:5m}") Duration recheck) {
        ExecutorService sender = Executors.newVirtualThreadPerTaskExecutor();
        ProjectStreamHub hub = new ProjectStreamHub(bus, access, sender, Clock.systemUTC(), SseEmitter::new,
                new ProjectStreamHub.Settings(perUser, global, queue, replay, heartbeat, timeout, stall, recheck), json);
        // 공유 스케줄러 빈을 만들지 않는다 — @Scheduled 인프라가 이 스케줄러를 기본값으로 집어 가지 않게
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("alm-stream-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        ScheduledFuture<?> tick = scheduler.scheduleAtFixedRate(hub::tick, heartbeat);
        return new HubRuntime(hub, scheduler, tick, sender);
    }

    @Bean
    ProjectStreamHub projectStreamHubBean(HubRuntime runtime) {
        return runtime.hub();
    }

    /** 허브 + 그 스레드들 — 종료 때 구독을 닫고(클라이언트는 다른 노드로 재연결) 스레드를 내린다 */
    record HubRuntime(ProjectStreamHub hub, ThreadPoolTaskScheduler scheduler, ScheduledFuture<?> tick,
                      ExecutorService sender) implements AutoCloseable {
        @Override
        public void close() {
            tick.cancel(false);
            hub.shutdown();
            scheduler.shutdown();
            sender.shutdown();
        }
    }
}
