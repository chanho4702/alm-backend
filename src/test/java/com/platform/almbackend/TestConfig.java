package com.platform.almbackend;

import com.platform.almbackend.directory.DirectoryMember;
import com.platform.almbackend.directory.MemberDirectory;
import com.platform.almbackend.directory.MemberDirectory.Outcome;
import com.platform.almbackend.event.EventPublisher;
import com.platform.almbackend.permission.AccessScope;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.permission.PermissionClient;
import com.platform.almbackend.permission.PermissionDecision;
import com.platform.common.error.ServiceUnavailableException;
import com.platform.proto.events.v1.EventEnvelope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    FakeMemberDirectory fakeMemberDirectory() {
        return new FakeMemberDirectory();
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

    /**
     * org-service gRPC 대역. 프로젝트 판정과 전역 관리자 판정을 따로 둔다 — 전역 관리자는 이제 JWT 역할이
     * 아니라 org의 GLOBAL/ADMIN grant다(2026-09-05). {@code unavailable}은 org 불능을 흉내 내
     * 503 전파를 검증한다.
     */
    public static final class FakePermissionClient implements PermissionClient {
        private boolean allowed = true;
        private String deniedReason = "NO_GRANT";
        private final Set<Long> globalAdmins = new HashSet<>();
        private boolean unavailable;
        private long grantedProjectId;
        /** 볼 수 있는 프로젝트 — 기본은 전역이라 기존 테스트는 그대로다 */
        private AccessScope scope = AccessScope.global();

        public void setAllowed(boolean allowed) { this.allowed = allowed; }
        /** 거부 사유 — 계정 상태(PENDING/SUSPENDED/DEACTIVATED)면 문구가 달라진다 */
        public void setDeniedReason(String reason) { this.deniedReason = reason; }
        /** 전역 관리자 목록을 이 id들로 바꾼다(비우면 아무도 관리자가 아니다) */
        public void setGlobalAdmins(long... ids) {
            globalAdmins.clear();
            for (long id : ids) globalAdmins.add(id);
        }
        /** AQL·검색의 접근 범위를 좁힌다(전역이 아닌 사용자를 흉내 낸다) */
        public void setAccessScope(AccessScope scope) { this.scope = scope; }
        /** org-service 불능 — 모든 판정이 503으로 올라간다 */
        public void setUnavailable(boolean unavailable) { this.unavailable = unavailable; }
        public long grantedProjectId() { return grantedProjectId; }

        public void reset() {
            allowed = true;
            deniedReason = "NO_GRANT";
            globalAdmins.clear();
            unavailable = false;
            scope = AccessScope.global();
        }

        @Override public PermissionDecision check(long userId, long projectId, AlmAction action) {
            failIfUnavailable();
            return allowed ? PermissionDecision.allow() : PermissionDecision.deny(deniedReason);
        }

        @Override public PermissionDecision checkGlobal(long userId, AlmAction action) {
            failIfUnavailable();
            return globalAdmins.contains(userId)
                    ? PermissionDecision.allow()
                    : PermissionDecision.deny(deniedReason);
        }

        @Override public AccessScope accessibleProjects(long userId) { return scope; }
        @Override public boolean grantProjectAdmin(long userId, long projectId) {
            grantedProjectId = projectId;
            return true;
        }
        @Override public int revokeProjectGrants(long projectId) { return 1; }

        private void failIfUnavailable() {
            if (unavailable) throw new ServiceUnavailableException("권한 서비스에 연결할 수 없습니다");
        }
    }

    /**
     * org 사용자 디렉터리 대역. 기본은 비어 있다 — 그래야 디렉터리를 모르는 기존 테스트가
     * 개인 설정 스냅샷 폴백을 그대로 검증한다.
     *
     * <p>호출을 기록한다: 조회한 id 묶음과 <b>그때 트랜잭션이 열려 있었는지</b>. 디렉터리 조회는 커밋 뒤에
     * 일어나야 하고(쓰기 트랜잭션이 남의 서비스를 기다리면 안 된다), 수신자가 여럿이면 한 번이어야 한다.
     */
    public static final class FakeMemberDirectory implements MemberDirectory {
        /** 한 번의 조회 — 물어본 id들과 그때 트랜잭션이 열려 있었는지 */
        public record Call(List<Long> ids, boolean inTransaction) {}

        private final Map<Long, DirectoryMember> members = new HashMap<>();
        private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        private boolean unavailable;
        private boolean failed;

        public void put(long id, String displayName, String email, String status) {
            members.put(id, new DirectoryMember(id, displayName, email, status, "HUMAN"));
        }
        /** org 불능 — 알림은 스냅샷으로 폴백하고, 계정 상태 게이트는 503을 낸다 */
        public void setUnavailable(boolean unavailable) { this.unavailable = unavailable; }
        /** 가용성 장애가 아닌 조회 실패 — 게이트는 통과시켜야 한다(org 버그를 계정 정지로 말하지 않는다) */
        public void setFailed(boolean failed) { this.failed = failed; }
        public List<Call> calls() { return List.copyOf(calls); }
        public void reset() {
            members.clear();
            calls.clear();
            unavailable = false;
            failed = false;
        }

        @Override public Map<String, DirectoryMember> lookupByEmail(Collection<String> queries) {
            if (unavailable || failed) return Map.of();
            Map<String, DirectoryMember> found = new HashMap<>();
            for (String query : queries) {
                for (DirectoryMember member : members.values()) {
                    String email = member.email();
                    int at = email.indexOf('@');
                    String local = at > 0 ? email.substring(0, at) : email;
                    if (email.equalsIgnoreCase(query) || (!local.isEmpty() && local.equalsIgnoreCase(query))) {
                        found.put(query.toLowerCase(java.util.Locale.ROOT), member);
                    }
                }
            }
            return found;
        }

        @Override public Lookup lookup(Collection<Long> ids) {
            calls.add(new Call(List.copyOf(ids),
                    TransactionSynchronizationManager.isActualTransactionActive()));
            if (unavailable) return new Lookup(Outcome.UNAVAILABLE, Map.of());
            if (failed) return new Lookup(Outcome.FAILED, Map.of());
            Map<Long, DirectoryMember> found = new HashMap<>();
            for (Long id : ids) {
                DirectoryMember member = members.get(id);
                if (member != null) found.put(id, member);
            }
            return Lookup.ok(found);
        }
    }

    public static final class RecordingEventPublisher implements EventPublisher {
        private final List<EventEnvelope> events = new ArrayList<>();
        @Override public void publish(EventEnvelope event) { events.add(event); }
        public List<EventEnvelope> events() { return List.copyOf(events); }
        public void clear() { events.clear(); }
    }
}

