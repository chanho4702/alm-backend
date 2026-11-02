package com.platform.almbackend;

import com.platform.almbackend.dev.DevDtos.DevSettingsRequest;
import com.platform.almbackend.dev.DevSettingsService;
import com.platform.common.error.ConflictException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 같은 expectedVersion으로 동시에 PUT — 몇 개가 이기고 몇 개가 409였나(H2·PostgreSQL 테스트 공용) */
public final class DevSettingsRace {
    private DevSettingsRace() {}

    public record Outcome(int succeeded, int conflicted, List<Throwable> unexpected) {}

    public static Outcome run(DevSettingsService service, long projectId, int expectedVersion, int writers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CyclicBarrier start = new CyclicBarrier(writers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String prefix = "w" + i;
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return service.update(1, projectId, new DevSettingsRequest(prefix, expectedVersion));
                }));
            }
            int ok = 0;
            int conflict = 0;
            List<Throwable> unexpected = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    ok++;
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof ConflictException) conflict++;
                    else unexpected.add(e.getCause());
                }
            }
            return new Outcome(ok, conflict, unexpected);
        } finally {
            pool.shutdownNow();
        }
    }
}
