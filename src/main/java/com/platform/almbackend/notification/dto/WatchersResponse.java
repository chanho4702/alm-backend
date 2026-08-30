package com.platform.almbackend.notification.dto;

import java.time.Instant;
import java.util.List;

/** 이슈 워처 목록 + 요청자 본인의 관심 여부 */
public record WatchersResponse(boolean watching, List<Watcher> watchers) {
    public record Watcher(long userId, Instant createdAt) {}
}
