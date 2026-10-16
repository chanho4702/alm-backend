package com.platform.almbackend.event;

import com.platform.almbackend.TestConfig;
import com.platform.almbackend.domain.EventOutbox;
import com.platform.almbackend.issue.IssueService;
import com.platform.almbackend.issue.dto.IssueCreateRequest;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.issue.dto.IssueUpdateRequest;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.project.dto.ProjectCreateRequest;
import com.platform.almbackend.repository.EventOutboxRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.almbackend.repository.SprintRepository;
import com.platform.common.error.ConflictException;
import com.platform.proto.events.v1.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이벤트 아웃박스 — 같은 트랜잭션에 적히고(롤백이면 사라짐), 워커가 id 순으로 내보내며,
 * Redis 실패는 지우지 않고 백오프 뒤 같은 eventId로 다시 보낸다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class EventOutboxRelayTest {

    @Autowired EventOutboxRelay relay;
    @Autowired EventOutboxRepository outbox;
    @Autowired TestConfig.RecordingEventPublisher published;
    @Autowired IssueService issueService;
    @Autowired ProjectService projectService;
    @Autowired IssueRepository issues;
    @Autowired ProjectRepository projects;
    @Autowired SprintRepository sprints;
    @Autowired JdbcTemplate jdbc;

    private long projectId;

    @BeforeEach
    void reset() {
        issues.deleteAllInBatch();
        sprints.deleteAllInBatch();
        projects.deleteAllInBatch();
        projectId = projectService.create(1, new ProjectCreateRequest("OBX", "아웃박스", "")).id();
        outbox.deleteAllInBatch();
        published.clear();
    }

    private IssueResponse createIssue(String title) {
        return issueService.create(1, projectId,
                new IssueCreateRequest(title, "", "task", "todo", "medium", null, null, null));
    }

    @Test
    void 커밋된_이벤트를_본문_없는_원래_봉투_그대로_id_순으로_내보낸다() {
        IssueResponse issue = createIssue("첫 이슈");
        issueService.update(1, issue.id(), new IssueUpdateRequest(
                "고친 제목", "", "task", "todo", "medium", null, null, 1, null));

        assertThat(published.events()).isEmpty();
        List<EventOutbox> rows = outbox.findAll(org.springframework.data.domain.Sort.by("id"));
        assertThat(rows).extracting(EventOutbox::getEventType).containsExactly("ISSUE_CREATED", "ISSUE_UPDATED");

        assertThat(relay.publishPending()).isEqualTo(2);
        List<EventEnvelope> events = published.events();
        assertThat(events).extracting(EventEnvelope::getEventId)
                .containsExactly(rows.get(0).getEventId(), rows.get(1).getEventId());
        assertThat(events.get(1).getIssueUpdated().getVersion()).isEqualTo(2);
        assertThat(events.get(1).getSource()).isEqualTo("alm-backend");
        assertThat(relay.pendingCount()).isZero();
        // 다시 돌려도 이미 보낸 것은 다시 보내지 않는다
        assertThat(relay.publishPending()).isZero();
        assertThat(published.events()).hasSize(2);
    }

    @Test
    void 롤백된_변경의_이벤트는_남지_않는다() {
        IssueResponse issue = createIssue("이슈");
        long before = outbox.count();

        assertThatThrownBy(() -> issueService.update(1, issue.id(), new IssueUpdateRequest(
                "낡은 저장", "", "task", "todo", "medium", null, null, 99, null)))
                .isInstanceOf(ConflictException.class);

        assertThat(outbox.count()).isEqualTo(before);
    }

    @Test
    void 발행이_실패하면_지우지_않고_백오프_뒤_같은_이벤트를_다시_보낸다() {
        createIssue("첫 이슈");
        createIssue("둘째 이슈");
        published.setFailing(true);

        assertThat(relay.publishPending()).isZero();
        List<EventOutbox> rows = outbox.findAll(org.springframework.data.domain.Sort.by("id"));
        // 첫 행에서 멈춘다 — 뒤 행은 시도하지 않아 순서가 뒤집히지 않는다
        assertThat(rows.get(0).getAttempts()).isEqualTo(1);
        assertThat(rows.get(0).getLastError()).contains("redis down");
        assertThat(rows.get(0).getNextAttemptAt()).isAfter(rows.get(0).getCreatedAt());
        assertThat(rows.get(1).getAttempts()).isZero();
        assertThat(relay.pendingCount()).isEqualTo(2);

        // Redis가 돌아와도 대기 시간 전에는 보내지 않는다(큐 전체가 함께 물러난다)
        published.setFailing(false);
        assertThat(relay.publishPending()).isZero();

        jdbc.update("update event_outbox set next_attempt_at = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(relay.publishPending()).isEqualTo(2);
        assertThat(published.events()).extracting(EventEnvelope::getEventId)
                .containsExactly(rows.get(0).getEventId(), rows.get(1).getEventId());
        EventOutbox head = outbox.findById(rows.get(0).getId()).orElseThrow();
        assertThat(head.getPublishedAt()).isNotNull();
        assertThat(head.getLastError()).isNull();
    }

    @Test
    void 최대_시도를_넘긴_행은_큐에서_빠지고_뒤_행은_계속_나간다() {
        createIssue("포기될 이슈");
        createIssue("다음 이슈");
        // 최대 시도(기본 20) 직전까지 실패한 것으로 만든다
        long headId = outbox.findAll(org.springframework.data.domain.Sort.by("id")).get(0).getId();
        jdbc.update("update event_outbox set attempts = 19 where id = ?", headId);
        published.setFailing(true);
        relay.publishPending();
        assertThat(outbox.findById(headId).orElseThrow().getAttempts()).isEqualTo(20);

        published.setFailing(false);
        jdbc.update("update event_outbox set next_attempt_at = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(published.events()).singleElement()
                .satisfies(event -> assertThat(event.getIssueCreated().getTitle()).isEqualTo("다음 이슈"));
        // 포기한 행은 지우지 않고 오류와 함께 남긴다(정리 대상도 아니다)
        EventOutbox dead = outbox.findById(headId).orElseThrow();
        assertThat(dead.getPublishedAt()).isNull();
        assertThat(dead.getLastError()).contains("redis down");
    }

    @Test
    void 보존_기간이_지난_발행분만_정리한다() {
        createIssue("오래된 이슈");
        createIssue("미발행 이슈");
        long oldId = outbox.findAll(org.springframework.data.domain.Sort.by("id")).get(0).getId();
        relay.publishPending();
        jdbc.update("update event_outbox set published_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(8))), oldId);
        jdbc.update("update event_outbox set published_at = null where id <> ?", oldId);

        int deleted = relay.purgePublished();
        assertThat(deleted).isEqualTo(1);
        assertThat(outbox.findById(oldId)).isEmpty();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void 백오프는_지수로_늘고_상한에서_멈춘다() {
        assertThat(relay.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.backoff(5)).isEqualTo(Duration.ofSeconds(16));
        assertThat(relay.backoff(20)).isEqualTo(Duration.ofMinutes(5));
        assertThat(relay.backoff(100)).isEqualTo(Duration.ofMinutes(5));
    }


    @Test
    void 다른_워커가_발행_중이면_두_번째_워커는_내보내지_않고_순서를_지킨다() throws Exception {
        createIssue("첫 이슈");
        createIssue("둘째 이슈");
        createIssue("셋째 이슈");
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        published.holdNext(entered, release);

        java.util.concurrent.ExecutorService first = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            // A: 소유권을 잡고 첫 행을 발행하다 붙들린다
            java.util.concurrent.Future<Integer> a = first.submit(relay::publishPending);
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // A가 잠근 배치 밖의 새 행 — 행 단위 SKIP LOCKED만 있으면 B가 이것을 A의 앞 행들보다 먼저 내보낸다
            createIssue("넷째 이슈");

            // B: 소유권을 못 잡으므로 기다리지 않고 아무것도 내보내지 않는다(뒤 행이 앞지르지 않는다)
            long started = System.nanoTime();
            assertThat(relay.publishPending()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(published.events()).isEmpty();

            release.countDown();
            assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3);
        } finally {
            release.countDown();
            first.shutdownNow();
        }
        assertThat(relay.publishPending()).isEqualTo(1);
        List<String> expected = outbox.findAll(org.springframework.data.domain.Sort.by("id")).stream()
                .map(EventOutbox::getEventId).toList();
        assertThat(expected).hasSize(4);
        assertThat(published.events()).extracting(EventEnvelope::getEventId).containsExactlyElementsOf(expected);
        assertThat(relay.pendingCount()).isZero();
    }
}
