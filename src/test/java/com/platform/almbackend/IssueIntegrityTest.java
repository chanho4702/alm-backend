package com.platform.almbackend;

import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.issue.IssueService;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueImportRequest;
import com.platform.almbackend.issue.dto.IssueImportResponse;
import com.platform.almbackend.issue.dto.IssueMoveRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.notification.NotificationService;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.project.dto.ProjectCreateRequest;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWatcherRepository;
import com.platform.almbackend.repository.NotificationRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.common.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 동시 편집 정합성·가져오기 부분 성공·보드 이동 알림 — 감사 하네스(AlmAudit)가 H2에서 재현한 세 결함의 회귀.
 *
 * <p>동시성 테스트는 HTTP 부하가 아니라 트랜잭션 끼어들기를 그대로 재현한다: B 트랜잭션이 엔티티를 먼저
 * 읽어 1차 캐시에 올려 둔 채로, 다른 스레드의 A 트랜잭션이 커밋하고, 그 뒤에 B가 같은 서비스 경로를 탄다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class IssueIntegrityTest {

    @Autowired IssueService service;
    @Autowired com.platform.almbackend.issue.IssueImportService imports;
    @Autowired com.platform.almbackend.issue.IssueRankService ranking;
    @Autowired ProjectService projectService;
    @Autowired NotificationService notificationService;
    @Autowired IssueRepository issues;
    @Autowired ProjectRepository projects;
    @Autowired SprintRepository sprints;
    @Autowired IssueWatcherRepository watchers;
    @Autowired NotificationRepository notifications;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TestConfig.FakePermissionClient permissions;
    @Autowired com.platform.almbackend.sprint.SprintService sprintService;

    private long projectId;
    private ExecutorService other;

    @BeforeEach
    void reset() {
        notifications.deleteAllInBatch();
        watchers.deleteAllInBatch();
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        permissions.reset();
        projectId = projectService.create(1, new ProjectCreateRequest("INT", "정합성", "")).id();
        other = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void shutdown() {
        other.shutdownNow();
    }

    private static IssueCreateRequest create(String title) {
        return new IssueCreateRequest(title, "", "task", "todo", "medium", null, null, null);
    }

    private static IssueUpdateRequest update(String title, int expectedVersion) {
        return new IssueUpdateRequest(title, "", "task", "todo", "medium", null, null, expectedVersion, null);
    }

    private <T> T onOtherThread(java.util.concurrent.Callable<T> work) {
        try {
            return other.submit(work).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 먼저_읽어_둔_낡은_버전으로는_동시_저장이_409다() {
        IssueResponse issue = service.create(1, projectId, create("원본"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // B: v1을 읽어 영속성 컨텍스트에 올려 둔다
            assertThat(issues.findById(issue.id()).orElseThrow().getVersion()).isEqualTo(1);
            // A: 다른 트랜잭션이 v1 → v2로 먼저 커밋한다
            IssueResponse first = onOtherThread(() -> service.update(2, issue.id(), update("먼저 저장", 1)));
            assertThat(first.version()).isEqualTo(2);
            // B: 편집을 시작할 때의 v1로 저장 — 캐시된 v1이 아니라 잠근 뒤 다시 읽은 v2와 비교해야 한다
            service.update(1, issue.id(), update("나중 저장", 1));
        })).isInstanceOf(ConflictException.class)
                .hasMessageContaining("현재 2, 요청 1");

        IssueResponse stored = service.get(1, issue.id());
        assertThat(stored.title()).isEqualTo("먼저 저장");
        assertThat(stored.version()).isEqualTo(2);
    }

    @Test
    void 먼저_읽어_둔_프로젝트가_있어도_이슈_번호가_겹치지_않는다() {
        service.create(1, projectId, create("첫 이슈"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        IssueResponse mine = tx.execute(status -> {
            // 권한 확인처럼 같은 트랜잭션이 프로젝트를 먼저 읽어 카운터(1)를 캐시해 둔다
            assertThat(projects.findById(projectId).orElseThrow().getLastIssueNumber()).isEqualTo(1L);
            IssueResponse theirs = onOtherThread(() -> service.create(2, projectId, create("다른 사람 이슈")));
            assertThat(theirs.key()).isEqualTo("INT-2");
            return service.create(1, projectId, create("내 이슈"));
        });

        assertThat(mine.key()).isEqualTo("INT-3");
        assertThat(projects.findById(projectId).orElseThrow().getLastIssueNumber()).isEqualTo(3L);
    }

    @Test
    void 가져오기는_다른_빈이_거부한_행만_실패시키고_나머지는_남긴다() {
        long before = issues.count();
        IssueImportResponse result = imports.importIssues(1, projectId, new IssueImportRequest(List.of(
                new IssueImportRequest.Item(null, "정상 1", "", "task", "todo", "medium", null, null),
                new IssueImportRequest.Item(null, "없는 타입", "", "does-not-exist", "todo", "medium", null, null),
                new IssueImportRequest.Item(null, "정상 2", "", "task", "todo", "medium", null, null))));

        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).hasSize(1);
        assertThat(result.failed().get(0).row()).isEqualTo(2);
        assertThat(result.failed().get(0).title()).isEqualTo("없는 타입");
        assertThat(issues.count()).isEqualTo(before + 2);
        // 실패한 행은 번호를 소비하지 않는다(행 트랜잭션과 함께 롤백)
        assertThat(service.list(1, projectId)).extracting(IssueResponse::key)
                .containsExactlyInAnyOrder("INT-1", "INT-2");
    }

    @Test
    void 가져오기_키_중복은_보관된_이슈까지_본다() {
        IssueResponse archived = service.create(1, projectId, create("보관될 이슈"));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status ->
                issues.findById(archived.id()).orElseThrow().archive(1, java.time.Instant.now()));

        IssueImportResponse result = imports.importIssues(1, projectId, new IssueImportRequest(List.of(
                new IssueImportRequest.Item("INT-1", "키 충돌", "", "task", "todo", "medium", null, null),
                new IssueImportRequest.Item("INT-7", "키 보존", "", "task", "todo", "medium", null, null))));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).singleElement()
                .satisfies(failure -> assertThat(failure.reason()).isEqualTo("이미 있는 키입니다: INT-1"));
        assertThat(projects.findById(projectId).orElseThrow().getLastIssueNumber()).isEqualTo(7L);
    }

    @Test
    void 보드에서_상태를_바꾸면_워처에게_한_번_알리고_같은_컬럼_재정렬은_알리지_않는다() {
        IssueResponse issue = service.create(1, projectId, create("이동할 이슈"));
        IssueResponse neighbour = service.create(1, projectId, create("옆 이슈"));
        notificationService.watch(2, issue.id());
        ranking.move(1, neighbour.id(), new IssueMoveRequest("done", null));
        notifications.deleteAllInBatch();

        ranking.move(1, issue.id(), new IssueMoveRequest("done", null));
        List<Notification> afterMove = notifications.findAll().stream()
                .filter(n -> n.getUserId() == 2L).toList();
        assertThat(afterMove).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(Notification.Type.STATUS_CHANGED);
            assertThat(n.getIssueId()).isEqualTo(issue.id());
            assertThat(n.getDetail()).isEqualTo("done");
        });

        // 같은 컬럼(done) 안에서 옆 이슈 앞으로 — 상태는 그대로다
        ranking.move(1, issue.id(), new IssueMoveRequest("done", neighbour.id()));
        assertThat(notifications.count()).isEqualTo(afterMove.size());
    }

    @Test
    void 먼저_읽어_둔_진행_중_스프린트를_두_번_완료하지_않는다() {
        long sprintId = sprintService.create(1, projectId,
                new com.platform.almbackend.sprint.dto.SprintCreateRequest("스프린트 1")).id();
        sprintService.start(1, sprintId);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // B: 진행 중(ACTIVE)인 스프린트를 읽어 둔다
            sprints.findById(sprintId).orElseThrow();
            // A: 다른 트랜잭션이 먼저 완료한다
            onOtherThread(() -> sprintService.complete(2, sprintId, null));
            // B: 잠근 뒤 다시 읽은 상태(CLOSED)로 판정해야 한다
            sprintService.complete(1, sprintId, null);
        })).isInstanceOf(ConflictException.class)
                .hasMessageContaining("진행 중인 스프린트만");
    }
}
