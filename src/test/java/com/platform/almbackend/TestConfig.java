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
    SettingsSeeder settingsSeeder(
            com.platform.almbackend.settings.SettingsBootstrap bootstrap,
            com.platform.almbackend.repository.SettingsSchemeRepository schemes,
            com.platform.almbackend.repository.StatusDefRepository statuses,
            com.platform.almbackend.repository.StatusCategoryRepository categories,
            com.platform.almbackend.repository.IssueTypeDefRepository types,
            com.platform.almbackend.repository.ProjectSettingsRepository projectSettings) {
        return new SettingsSeeder(bootstrap, schemes, statuses, categories, types, projectSettings);
    }

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

    /** 테스트마다 레지스트리·스킴을 기본값으로 되돌린다 — H2 create-drop에는 V11 시드가 없다 */
    public static final class SettingsSeeder {
        private final com.platform.almbackend.settings.SettingsBootstrap bootstrap;
        private final com.platform.almbackend.repository.SettingsSchemeRepository schemes;
        private final com.platform.almbackend.repository.StatusDefRepository statuses;
        private final com.platform.almbackend.repository.StatusCategoryRepository categories;
        private final com.platform.almbackend.repository.IssueTypeDefRepository types;
        private final com.platform.almbackend.repository.ProjectSettingsRepository projectSettings;

        SettingsSeeder(com.platform.almbackend.settings.SettingsBootstrap bootstrap,
                       com.platform.almbackend.repository.SettingsSchemeRepository schemes,
                       com.platform.almbackend.repository.StatusDefRepository statuses,
                       com.platform.almbackend.repository.StatusCategoryRepository categories,
                       com.platform.almbackend.repository.IssueTypeDefRepository types,
                       com.platform.almbackend.repository.ProjectSettingsRepository projectSettings) {
            this.bootstrap = bootstrap;
            this.schemes = schemes;
            this.statuses = statuses;
            this.categories = categories;
            this.types = types;
            this.projectSettings = projectSettings;
        }

        public void resetToDefaults() {
            projectSettings.deleteAllInBatch();
            schemes.deleteAllInBatch();
            statuses.deleteAllInBatch();
            types.deleteAllInBatch();
            categories.deleteAllInBatch();
            bootstrap.ensureDefaults();
        }
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

