package com.platform.almbackend.dev.scm;

import com.platform.almbackend.dev.DevCommitService;
import com.platform.almbackend.dev.DevDtos.CommitInput;
import com.platform.almbackend.dev.DevDtos.IngestRequest;
import com.platform.almbackend.dev.DevDtos.IngestResponse;
import com.platform.almbackend.dev.DevDtos.PullRequestLinkResult;
import com.platform.almbackend.dev.webhook.WebhookSecretCipher;
import com.platform.almbackend.domain.CodeRepository;
import com.platform.almbackend.domain.RepositoryScmSync;
import com.platform.almbackend.domain.RepositoryWebhook;
import com.platform.almbackend.repository.CodeRepositoryRepository;
import com.platform.almbackend.repository.RepositoryScmSyncRepository;
import com.platform.common.error.ServiceUnavailableException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 저장소 폴링 동기화 실행(V45). 한 번의 실행 = 기본 브랜치 커밋(증분) → 갱신된 PR/MR(상태·연결) → 열린 PR/MR의 커밋.
 * 수집은 웹훅과 같은 경로({@link DevCommitService#ingest}·{@link DevCommitService#linkPullRequest})라 같은 커밋·PR이 양쪽에서 와도 한 번만 남는다.
 *
 * <ul>
 *   <li><b>중복 실행 방지</b>: 행 임대(lease) — 조건부 UPDATE로 잡은 인스턴스만 돈다. 실행 중 죽으면 임대가 만료({@link #LEASE})된 뒤
 *       다른 인스턴스가 가져간다. 외부 HTTP는 트랜잭션 밖에서 한다(DB 커넥션을 붙잡지 않는다).</li>
 *   <li><b>증분</b>: 커밋은 커밋 시각 상한에서 {@link #COMMIT_OVERLAP} 겹쳐 다시 읽는다(시계 차·늦게 push된 커밋 — 수집 멱등이라 무해).
 *       첫 쪽은 ETag로 If-None-Match — 바뀐 게 없으면 304(GitHub는 레이트 리밋에 세지 않는다).</li>
 *   <li><b>레이트 리밋</b>: 429·Retry-After·남은 수 0이면 그 시각까지 다음 실행을 미룬다. 그 밖의 실패는 주기 × 2^실패 횟수(상한 max-backoff).</li>
 *   <li>실패한 실행은 커서를 움직이지 않는다 — 다음 실행이 같은 구간을 다시 읽는다(이미 넣은 커밋은 DUPLICATE).</li>
 * </ul>
 *
 * <p>주기 실행은 자기 스레드 하나에서 돈다 — 느린 Git 서버가 다른 @Scheduled 작업(자동화·아웃박스)을 붙잡지 않게.
 */
@Service
@Slf4j
public class ScmSyncRunner {
    public static final Duration LEASE = Duration.ofMinutes(15);
    static final Duration COMMIT_OVERLAP = Duration.ofHours(1);
    static final Duration PULL_OVERLAP = Duration.ofMinutes(2);
    private static final int BATCH = 20;

    private final RepositoryScmSyncRepository syncs;
    private final CodeRepositoryRepository codeRepositories;
    private final WebhookSecretCipher cipher;
    private final DevCommitService commits;
    private final ScmHttp http;
    private final GitHubScmProvider gitHub;
    private final GitLabScmProvider gitLab;
    private final ScmSyncSettings settings;
    private final TransactionTemplate tx;
    private final String owner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alm-scm-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    public ScmSyncRunner(RepositoryScmSyncRepository syncs, CodeRepositoryRepository codeRepositories, WebhookSecretCipher cipher,
                         DevCommitService commits, ScmHttp http, GitHubScmProvider gitHub, GitLabScmProvider gitLab,
                         ScmSyncSettings settings, PlatformTransactionManager transactionManager) {
        this.syncs = syncs;
        this.codeRepositories = codeRepositories;
        this.cipher = cipher;
        this.commits = commits;
        this.http = http;
        this.gitHub = gitHub;
        this.gitLab = gitLab;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "alm";
        }
        this.owner = (host.length() > 40 ? host.substring(0, 40) : host) + ":" + UUID.randomUUID().toString().substring(0, 8);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${platform.alm.scm.tick:60s}", initialDelayString = "${platform.alm.scm.initial-delay:45s}")
    public void tick() {
        if (!settings.pollingActive() || !running.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                runDue(Instant.now());
            } catch (RuntimeException e) {
                log.warn("저장소 동기화 주기 실패(다음 주기에 재시도)", e);
            } finally {
                running.set(false);
            }
        });
    }

    /** 때가 된 저장소를 돌린다(이 인스턴스가 임대를 잡은 것만) — 돌린 수. 테스트도 직접 부른다 */
    public int runDue(Instant now) {
        if (!settings.mode().polling()) return 0;
        int ran = 0;
        for (Long id : syncs.findDueIds(now, Limit.of(BATCH))) {
            Integer claimed = tx.execute(s -> syncs.claimDue(id, owner, now, now.plus(LEASE)));
            if (claimed == null || claimed != 1) continue;
            run(id);
            ran++;
        }
        return ran;
    }

    /** "지금 동기화" — 잡지 못하면(실행 중·레이트 리밋 대기) false */
    public boolean runNow(long id) {
        Instant now = Instant.now();
        Integer claimed = tx.execute(s -> syncs.claimNow(id, owner, now, now.plus(LEASE)));
        if (claimed == null || claimed != 1) return false;
        run(id);
        return true;
    }

    private void run(long id) {
        RepositoryScmSync sync = syncs.findById(id).orElse(null);
        if (sync == null) return;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try {
            Outcome outcome = poll(sync, now);
            Instant next = now.plus(settings.pollInterval());
            Instant exhausted = outcome.exhaustedUntil();
            if (exhausted != null && exhausted.isAfter(next)) next = exhausted;
            Instant nextRun = next;
            tx.executeWithoutResult(s -> syncs.findById(id).ifPresent(e -> e.succeeded(now, nextRun, exhausted,
                    outcome.commitsCursor(), outcome.commitsEtag(), outcome.pullsCursor(), outcome.pullsEtag(), clip(outcome.message()))));
            log.info("저장소 동기화: sync={} repo={} {}", id, sync.getRepositoryUrl(), outcome.message());
        } catch (ScmHttp.RateLimitedException e) {
            tx.executeWithoutResult(s -> syncs.findById(id).ifPresent(r -> r.rateLimited(now, e.until(), clip(e.getMessage()))));
            log.warn("저장소 동기화 레이트 리밋: sync={} until={}", id, e.until());
        } catch (ScmHttp.ScmException | IllegalArgumentException | IllegalStateException | ServiceUnavailableException e) {
            failed(id, sync, now, e.getMessage());
            log.warn("저장소 동기화 실패: sync={} repo={} {}", id, sync.getRepositoryUrl(), e.getMessage());
        } catch (RuntimeException e) {
            failed(id, sync, now, "동기화하지 못했습니다 — 서버 로그를 확인하세요");
            log.error("저장소 동기화 오류: sync={} repo={}", id, sync.getRepositoryUrl(), e);
        }
    }

    private void failed(long id, RepositoryScmSync sync, Instant now, String message) {
        int exponent = Math.min(sync.getFailureCount(), 10);
        Duration backoff = settings.pollInterval().multipliedBy(1L << exponent);
        if (backoff.compareTo(settings.maxBackoff()) > 0) backoff = settings.maxBackoff();
        Instant next = now.plus(backoff);
        tx.executeWithoutResult(s -> syncs.findById(id).ifPresent(r -> r.failed(now, next, clip(message))));
    }

    private record Outcome(Instant commitsCursor, String commitsEtag, Instant pullsCursor, String pullsEtag, Instant exhaustedUntil,
                           String message) {}

    private Outcome poll(RepositoryScmSync sync, Instant now) {
        String token = cipher.decrypt(sync.getTokenEnc());
        String branch = codeRepositories.findByProjectIdOrderByPositionAscIdAsc(sync.getProjectId()).stream()
                .filter(r -> r.getUrl().equals(sync.getRepositoryUrl())).map(CodeRepository::getDefaultBranch).findFirst()
                .orElseThrow(() -> new IllegalStateException("프로젝트에 이 저장소가 등록돼 있지 않습니다"));
        ScmProvider provider = RepositoryWebhook.GITLAB.equals(sync.getProvider()) ? gitLab : gitHub;
        ScmHttp.Call call = http.open(sync.getApiBaseUrl(), provider.authHeaders(token));
        ScmProvider.Target target = new ScmProvider.Target(sync.getRepositoryUrl(), sync.getApiBaseUrl(), token, call);
        Instant firstSince = now.minus(settings.initialLookback());

        // 1) 기본 브랜치 커밋
        Instant commitsSince = sync.getCommitsCursor() == null ? firstSince : sync.getCommitsCursor().minus(COMMIT_OVERLAP);
        ScmProvider.CommitBatch cb = provider.commits(target, branch, commitsSince, sync.getCommitsCursor() == null ? null : sync.getCommitsEtag());
        Stats stats = new Stats();
        ingest(sync, branch, cb.commits(), stats);
        Instant commitsCursor = cb.notModified() ? sync.getCommitsCursor() : later(sync.getCommitsCursor(), cb.maxCommittedAt());
        if (commitsCursor == null && !cb.notModified()) commitsCursor = commitsSince.plus(COMMIT_OVERLAP); // 빈 저장소 — 다음엔 이 구간부터
        String commitsEtag = cb.notModified() ? sync.getCommitsEtag() : cb.etag();

        // 2) 갱신된 PR/MR — 상태·연결, 열린 것은 커밋까지
        Instant pullsSince = sync.getPullsCursor() == null ? firstSince : sync.getPullsCursor().minus(PULL_OVERLAP);
        ScmProvider.PullBatch pb = provider.pulls(target, pullsSince, sync.getPullsCursor() == null ? null : sync.getPullsEtag());
        for (ScmProvider.PullItem pull : pb.pulls()) {
            if (pull.url() == null) continue;
            List<String> texts = new ArrayList<>();
            if (pull.title() != null) texts.add(pull.title());
            if (pull.sourceBranch() != null) texts.add(pull.sourceBranch());
            PullRequestLinkResult r = commits.linkPullRequest(sync.getActorId(), pull.url(), pull.title(), texts, pull.state());
            stats.pulls++;
            stats.pullLinks += r.linked().size();
            if ("OPEN".equals(pull.state()) && pull.sourceBranch() != null) {
                ingest(sync, pull.sourceBranch(), provider.pullCommits(target, pull), stats);
            }
        }
        Instant pullsCursor = pb.notModified() ? sync.getPullsCursor() : later(sync.getPullsCursor(), pb.maxUpdatedAt());
        if (pullsCursor == null && !pb.notModified()) pullsCursor = pullsSince.plus(PULL_OVERLAP);
        String pullsEtag = pb.notModified() ? sync.getPullsEtag() : pb.etag();

        String message = stats.commits == 0 && stats.pulls == 0 ? "변경 없음"
                : "커밋 " + stats.commits + "건(새로 저장 " + stats.stored + " · 새 연결 " + stats.links + ") · PR " + stats.pulls
                + "건(새 연결 " + stats.pullLinks + ")";
        if (cb.truncated()) message += " · 한 번에 다 못 읽은 오래된 커밋은 sync-commits 스크립트로 메우세요";
        return new Outcome(commitsCursor, commitsEtag, pullsCursor, pullsEtag, call.exhaustedUntil(), message);
    }

    private static final class Stats {
        int commits, stored, links, pulls, pullLinks;
    }

    /** sha로 겹침을 지우고(같은 요청 안 중복은 INVALID라) 수집 상한 단위로 나눠 넣는다 */
    private void ingest(RepositoryScmSync sync, String branch, List<CommitInput> inputs, Stats stats) {
        Map<String, CommitInput> unique = new LinkedHashMap<>();
        for (CommitInput c : inputs) if (c.sha() != null) unique.putIfAbsent(c.sha().toLowerCase(java.util.Locale.ROOT), c);
        List<CommitInput> all = new ArrayList<>(unique.values());
        for (int from = 0; from < all.size(); from += DevCommitService.MAX_BATCH) {
            List<CommitInput> chunk = all.subList(from, Math.min(all.size(), from + DevCommitService.MAX_BATCH));
            IngestResponse r = commits.ingest(sync.getActorId(), new IngestRequest(sync.getRepositoryUrl(), branch, chunk));
            stats.commits += r.received();
            stats.stored += r.stored();
            stats.links += r.linksCreated();
        }
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static String clip(String message) {
        if (message == null) return null;
        return message.length() <= 300 ? message : message.substring(0, 299) + "…";
    }
}
