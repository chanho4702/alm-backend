package com.platform.almbackend;

import com.platform.almbackend.event.EventPublisher;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.proto.events.v1.EventEnvelope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

@TestConfiguration
public class TestConfig {
    @Bean
    @Primary
    FakePermissionClient fakePermissionClient() {
        return new FakePermissionClient();
    }

    @Bean
    @Primary
    RecordingEventPublisher recordingEventPublisher() {
        return new RecordingEventPublisher();
    }

    public static final class FakePermissionClient implements PermissionClient {
        private boolean allowed = true;
        private long grantedProjectId;

        public void setAllowed(boolean allowed) { this.allowed = allowed; }
        public long grantedProjectId() { return grantedProjectId; }

        @Override public boolean isAllowed(long userId, long projectId, AlmAction action) { return allowed; }
        @Override public AccessScope accessibleProjects(long userId) { return AccessScope.global(); }
        @Override public boolean grantProjectAdmin(long userId, long projectId) {
            grantedProjectId = projectId;
            return true;
        }
        @Override public int revokeProjectGrants(long projectId) { return 1; }
    }

    public static final class RecordingEventPublisher implements EventPublisher {
        private final List<EventEnvelope> events = new ArrayList<>();
        @Override public void publish(EventEnvelope event) { events.add(event); }
        public List<EventEnvelope> events() { return List.copyOf(events); }
        public void clear() { events.clear(); }
    }
}

