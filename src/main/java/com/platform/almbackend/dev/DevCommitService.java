package com.platform.almbackend.dev;

import com.platform.almbackend.collab.IssueActivityService;
import com.platform.almbackend.dev.DevDtos.CommitInput;
import com.platform.almbackend.dev.DevDtos.CommitPage;
import com.platform.almbackend.dev.DevDtos.CommitResult;
import com.platform.almbackend.dev.DevDtos.CommitView;
import com.platform.almbackend.dev.DevDtos.DevelopmentResponse;
import com.platform.almbackend.dev.DevDtos.DevelopmentSummary;
import com.platform.almbackend.dev.DevDtos.IngestRequest;
import com.platform.almbackend.dev.DevDtos.IngestResponse;
import com.platform.almbackend.dev.DevDtos.IssueRef;
import com.platform.almbackend.dev.DevDtos.PullRequestLinkResult;
import com.platform.almbackend.dev.DevDtos.PullRequestView;
import com.platform.almbackend.dev.DevDtos.SkippedKey;
import com.platform.almbackend.domain.CodeRepository;
import com.platform.almbackend.domain.DevCommit;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueCommit;
import com.platform.almbackend.domain.IssueWebLink;
import com.platform.almbackend.domain.Project;
import com.platform.almbackend.event.domain.IssueDomainEventPublisher;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.CodeRepositoryRepository;
import com.platform.almbackend.repository.DevCommitRepository;
import com.platform.almbackend.repository.IssueCommitRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWebLinkRepository;
import com.platform.almbackend.repository.ProjectRepository;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 커밋 수집·연결과 개발 정보 조회(V41, 지라 Development 패널).
 *
 * <p><b>수집 권한</b>: 연결은 <b>대상 이슈 프로젝트의 EDIT</b>로 키마다 판정한다(프로젝트별 한 번). 권한이 없거나 보관된
 * 프로젝트의 키는 건너뛰고 결과에 이유를 싣는다 — 한 커밋이 여러 제품 이슈를 가리켜도 되는 것만 잇는다. 커밋 행 자체는
 * 이어진 이슈가 하나라도 있거나, 그 저장소를 등록한 프로젝트 중 하나에 EDIT가 있을 때만 저장한다(아무 데도 속하지 않는
 * 커밋을 쌓지 않는다). org 불능(503)은 삼키지 않는다 — 요청 전체가 503이고 아무것도 남지 않는다(한 트랜잭션).
 *
 * <p><b>멱등</b>: 커밋 (저장소, sha)·연결 (이슈, 커밋) 둘 다 ON CONFLICT DO NOTHING이다. 같은 묶음을 다시 보내면
 * 전부 DUPLICATE·alreadyLinked이고 활동·이벤트도 다시 나가지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DevCommitService {
    public static final int MAX_BATCH = 500;
    public static final int MAX_ISSUE_COMMITS = 200;
    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 100;
    private static final int MAX_TITLE = 300;
    private static final int MAX_BODY = 2000;
    private static final int MAX_BODY_LINES = 20;
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$|^[0-9a-f]{64}$");
    private static final Pattern URL_SHA = Pattern.compile("/commits?/([0-9a-fA-F]{7,64})(?:[/?#].*)?$");
    /** 첫 쪽 커서 — null 바인딩을 피하는 먼 미래 */
    private static final Instant FAR_FUTURE = Instant.parse("9999-12-31T00:00:00Z");

    public static final String STORED = "STORED";
    public static final String DUPLICATE = "DUPLICATE";
    public static final String SKIPPED = "SKIPPED";
    public static final String INVALID = "INVALID";

    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final IssueRepository issues;
    private final CodeRepositoryRepository codeRepositories;
    private final DevCommitRepository commits;
    private final IssueCommitRepository issueCommits;
    private final IssueWebLinkRepository webLinks;
    private final IssueActivityService activityLog;
    private final IssueDomainEventPublisher domainEvents;

    // ── 수집 ──

    public IngestResponse ingest(long userId, IngestRequest request) {
        if (request == null) throw new IllegalArgumentException("요청 본문이 필요합니다");
        String repoUrl = RepositoryUrls.normalize(request.repository());
        List<CommitInput> inputs = request.commits() == null ? List.of() : request.commits();
        if (inputs.isEmpty()) throw new IllegalArgumentException("커밋을 하나 이상 보내세요");
        if (inputs.size() > MAX_BATCH) throw new IllegalArgumentException("커밋은 한 번에 " + MAX_BATCH + "건까지 보낼 수 있습니다");
        String defaultBranch = trimTo(request.branch(), 200);

        Set<String> projectKeys = projects.findAll().stream().map(Project::getKey).collect(Collectors.toSet());
        Map<Long, String> denial = new HashMap<>(); // 프로젝트 id → 거부 이유(허용이면 "")
        List<CodeRepository> registrations = codeRepositories.findByUrl(repoUrl);
        String repoName = registrations.isEmpty() ? RepositoryUrls.defaultName(repoUrl) : registrations.getFirst().getName();
        boolean repoOwned = registrations.stream().anyMatch(r -> denial(userId, r.getProjectId(), denial) == null);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Map<Long, Issue> touchedIssues = new LinkedHashMap<>();
        Map<Long, List<String>> newByIssue = new LinkedHashMap<>();
        List<CommitResult> results = new ArrayList<>();
        Set<String> seenInBatch = new HashSet<>();
        Map<String, Optional<Issue>> issueByKey = new HashMap<>();
        int stored = 0, duplicates = 0, skipped = 0, invalid = 0, linksCreated = 0;

        for (CommitInput input : inputs) {
            String rawSha = input == null ? null : input.sha();
            String sha = rawSha == null ? "" : rawSha.trim().toLowerCase(Locale.ROOT);
            String problem = validate(input, sha);
            if (problem == null && !seenInBatch.add(sha)) problem = "같은 요청 안에 같은 sha가 두 번 있습니다";
            if (problem != null) {
                invalid++;
                results.add(new CommitResult(rawSha, shortSha(sha), INVALID, problem, null, List.of(), List.of(), List.of()));
                continue;
            }
            String commitUrl;
            try {
                commitUrl = input.url() == null || input.url().isBlank() ? repoUrl + "/commit/" + sha : RepositoryUrls.commitUrl(input.url());
            } catch (IllegalArgumentException e) {
                invalid++;
                results.add(new CommitResult(rawSha, shortSha(sha), INVALID, e.getMessage(), null, List.of(), List.of(), List.of()));
                continue;
            }

            // 키 → 이슈(권한 판정은 프로젝트마다 한 번)
            List<Issue> targets = new ArrayList<>();
            List<SkippedKey> skippedKeys = new ArrayList<>();
            for (String key : IssueKeyParser.parse(input.message(), projectKeys)) {
                Optional<Issue> issue = issueByKey.computeIfAbsent(key, issues::findByKey); // 묶음 안 같은 키는 한 번만 읽는다
                if (issue.isEmpty()) {
                    skippedKeys.add(new SkippedKey(key, "ISSUE_NOT_FOUND"));
                    continue;
                }
                String reason = denial(userId, issue.get().getProjectId(), denial);
                if (reason != null) {
                    skippedKeys.add(new SkippedKey(key, reason));
                    continue;
                }
                targets.add(issue.get());
            }

            Optional<DevCommit> existing = commits.findByRepositoryUrlAndSha(repoUrl, sha);
            boolean created = false;
            if (existing.isEmpty()) {
                if (targets.isEmpty() && !repoOwned) {
                    skipped++;
                    results.add(new CommitResult(rawSha, shortSha(sha), SKIPPED, "NO_LINKABLE_ISSUE", null,
                            List.of(), List.of(), skippedKeys));
                    continue;
                }
                String[] titleBody = splitMessage(input.message());
                String branch = input.branch() == null || input.branch().isBlank() ? defaultBranch : trimTo(input.branch(), 200);
                created = commits.insertIfAbsent(repoUrl, repoName, sha, shortSha(sha), titleBody[0], titleBody[1],
                        trimTo(input.authorName(), 200), trimTo(input.authorEmail(), 320),
                        input.authoredAt().truncatedTo(ChronoUnit.MICROS), branch, commitUrl, userId, now) == 1;
                existing = commits.findByRepositoryUrlAndSha(repoUrl, sha);
            }
            DevCommit commit = existing.orElseThrow(() -> new IllegalStateException("커밋 저장 직후 조회에 실패했습니다"));
            if (created) stored++; else duplicates++;

            List<IssueRef> linked = new ArrayList<>();
            List<IssueRef> already = new ArrayList<>();
            for (Issue target : targets) {
                IssueRef ref = new IssueRef(target.getId(), target.getKey());
                if (issueCommits.insertIfAbsent(target.getId(), commit.getId(), userId, now) == 1) {
                    linked.add(ref);
                    linksCreated++;
                    touchedIssues.putIfAbsent(target.getId(), target);
                    newByIssue.computeIfAbsent(target.getId(), id -> new ArrayList<>()).add(commit.getShortSha());
                } else {
                    already.add(ref);
                }
            }
            results.add(new CommitResult(rawSha, commit.getShortSha(), created ? STORED : DUPLICATE, null, commit.getId(),
                    linked, already, skippedKeys));
        }

        // 새 연결이 생긴 이슈마다 활동 한 줄 + 도메인 이벤트 하나(자동화 COMMIT_LINKED·실시간 development)
        for (Map.Entry<Long, List<String>> entry : newByIssue.entrySet()) {
            Issue issue = touchedIssues.get(entry.getKey());
            List<String> shas = entry.getValue();
            String detail = "커밋 " + shas.size() + "건 연결: " + String.join(", ", shas.subList(0, Math.min(10, shas.size())))
                    + (shas.size() > 10 ? " 외 " + (shas.size() - 10) + "건" : "");
            activityLog.record(issue.getId(), userId, "commit_linked", detail, now);
            domainEvents.commitsLinked(userId, issue, shas.size());
        }
        return new IngestResponse(repoUrl, inputs.size(), stored, duplicates, skipped, invalid, linksCreated, results);
    }

    /**
     * PR을 제목·브랜치 이름 안 이슈 키의 이슈에 웹 링크(kind=PR)로 잇는다(저장소 웹훅, V44). 권한·멱등 규칙은 커밋 수집과 같다 —
     * 키마다 대상 프로젝트 EDIT, 같은 (이슈, URL)은 다시 만들지 않는다(재전송·PR 수정 이벤트가 여러 번 와도 한 줄).
     */
    public PullRequestLinkResult linkPullRequest(long userId, String url, String title, List<String> keyTexts) {
        return linkPullRequest(userId, url, title, keyTexts, null);
    }

    /** {@code state}(OPEN·MERGED·CLOSED, null이면 모름)는 이번에 이었거나 이미 이어져 있던(권한 있는) 이슈의 PR 링크에 적는다 */
    public PullRequestLinkResult linkPullRequest(long userId, String url, String title, List<String> keyTexts, String state) {
        String prUrl = RepositoryUrls.webUrl(url, "PR");
        String prTitle = trimTo(title, 200);
        Set<String> projectKeys = projects.findAll().stream().map(Project::getKey).collect(Collectors.toSet());
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (String text : keyTexts == null ? List.<String>of() : keyTexts) {
            for (String key : IssueKeyParser.parse(text, projectKeys)) {
                if (keys.size() < IssueKeyParser.MAX_KEYS) keys.add(key);
            }
        }
        Map<Long, String> denial = new HashMap<>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<IssueRef> linked = new ArrayList<>();
        List<IssueRef> already = new ArrayList<>();
        List<SkippedKey> skipped = new ArrayList<>();
        for (String key : keys) {
            Optional<Issue> issue = issues.findByKey(key);
            if (issue.isEmpty()) {
                skipped.add(new SkippedKey(key, "ISSUE_NOT_FOUND"));
                continue;
            }
            String reason = denial(userId, issue.get().getProjectId(), denial);
            if (reason != null) {
                skipped.add(new SkippedKey(key, reason));
                continue;
            }
            IssueRef ref = new IssueRef(issue.get().getId(), issue.get().getKey());
            if (webLinks.insertIfAbsent(issue.get().getId(), prUrl, prTitle, "PR", userId, now) == 1) {
                linked.add(ref);
                String detail = "PR: " + (prTitle != null ? prTitle : prUrl);
                activityLog.record(issue.get().getId(), userId, "web_link_added", detail.length() > 500 ? detail.substring(0, 500) : detail, now);
            } else {
                already.add(ref);
            }
        }
        if (state != null) {
            // 상태는 이번 키와 무관하게 이 URL로 이미 이어진 PR 링크 전부에 — 제목·브랜치에서 키를 지운 뒤 병합·닫혀도 OPEN에 고착되지 않게.
            // 권한은 그대로: 처리 주체가 그 이슈 프로젝트에 EDIT가 있는 링크만 고친다
            Set<Long> ids = new java.util.LinkedHashSet<>();
            linked.forEach(r -> ids.add(r.issueId()));
            already.forEach(r -> ids.add(r.issueId()));
            for (IssueWebLink existing : webLinks.findByUrlAndKind(prUrl, "PR")) {
                if (ids.contains(existing.getIssueId())) continue;
                Optional<Issue> owner = issues.findById(existing.getIssueId());
                if (owner.isPresent() && denial(userId, owner.get().getProjectId(), denial) == null) ids.add(existing.getIssueId());
            }
            if (!ids.isEmpty()) webLinks.updatePullRequestState(prUrl, ids, state);
        }
        return new PullRequestLinkResult(prUrl, linked, already, skipped);
    }

    /** 프로젝트에 이을 수 있나 — null이면 된다. 결과는 요청 동안 캐시. org 불능은 그대로 올린다(503) */
    private String denial(long userId, long projectId, Map<Long, String> cache) {
        String cached = cache.get(projectId);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String reason;
        if (!projectService.hasGrant(userId, projectId, AlmAction.EDIT)) {
            reason = "NO_PERMISSION";
        } else {
            Optional<Project> project = projects.findById(projectId);
            reason = project.isEmpty() ? "ISSUE_NOT_FOUND" : project.get().isArchived() ? "PROJECT_ARCHIVED" : null;
        }
        cache.put(projectId, reason == null ? "" : reason);
        return reason;
    }

    private static String validate(CommitInput input, String sha) {
        if (input == null) return "커밋 항목이 비었습니다";
        if (!SHA.matcher(sha).matches()) return "sha는 16진 40자(또는 64자) 전체 값이어야 합니다";
        if (input.message() == null || input.message().isBlank()) return "커밋 메시지가 비었습니다";
        if (input.authoredAt() == null) return "작성 시각(authoredAt)이 필요합니다";
        return null;
    }

    /** [제목(첫 줄, 300자), 본문 앞부분(빈 줄 건너뛰고 20줄·2000자, 없으면 null)] */
    static String[] splitMessage(String message) {
        String[] lines = message.replace("\r\n", "\n").replace('\r', '\n').strip().split("\n", -1);
        String title = lines[0].strip();
        if (title.length() > MAX_TITLE) title = title.substring(0, MAX_TITLE - 1) + "…";
        StringBuilder body = new StringBuilder();
        int taken = 0;
        for (int i = 1; i < lines.length && taken < MAX_BODY_LINES; i++) {
            if (body.isEmpty() && lines[i].isBlank()) continue;
            if (!body.isEmpty()) body.append('\n');
            body.append(lines[i].stripTrailing());
            taken++;
        }
        String excerpt = body.toString().strip();
        if (excerpt.length() > MAX_BODY) excerpt = excerpt.substring(0, MAX_BODY - 1) + "…";
        return new String[] {title, excerpt.isEmpty() ? null : excerpt};
    }

    private static String shortSha(String sha) {
        return sha == null ? null : sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    private static String trimTo(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    // ── 조회 ──

    /** 이슈 개발 정보 — 수집 커밋 + 옛 웹 링크(kind=COMMIT, 같은 URL의 수집 커밋이 없을 때) + PR(웹 링크 kind=PR) */
    @Transactional(readOnly = true)
    public DevelopmentResponse development(long userId, long issueId) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        projectService.require(userId, issue.getProjectId(), AlmAction.VIEW);
        List<DevCommit> linked = commits.findLinkedToIssue(issueId, Limit.of(MAX_ISSUE_COMMITS));
        long linkedTotal = commits.countLinkedToIssue(issueId);
        List<CommitView> views = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (DevCommit c : linked) {
            views.add(view(c, null));
            urls.add(c.getUrl());
        }
        List<PullRequestView> pulls = new ArrayList<>();
        int legacy = 0;
        for (IssueWebLink w : webLinks.findByIssueIdOrderByIdDesc(issueId)) {
            if ("PR".equals(w.getKind())) {
                pulls.add(new PullRequestView(w.getId(), w.getUrl(), w.getTitle(), w.getCreatedBy(), w.getCreatedAt(), w.getState()));
            } else if ("COMMIT".equals(w.getKind()) && !urls.contains(w.getUrl())) {
                legacy++;
                if (views.size() < MAX_ISSUE_COMMITS) views.add(legacyView(w));
            }
        }
        // 옛 웹 링크는 작성 시각이 없다 — 연결 시각 순으로 수집 커밋 뒤에 둔다(최신 순 정렬은 수집 커밋끼리)
        Set<String> repositories = linked.stream().map(DevCommit::getRepositoryUrl).collect(Collectors.toSet());
        Instant last = linked.stream().map(DevCommit::getAuthoredAt).max(Comparator.naturalOrder()).orElse(null);
        DevelopmentSummary summary = new DevelopmentSummary((int) linkedTotal + legacy, repositories.size(), pulls.size(), last);
        boolean truncated = linkedTotal + legacy > views.size();
        return new DevelopmentResponse(issue.getId(), issue.getKey(), summary, views, truncated, pulls);
    }

    /** 프로젝트 최근 커밋 — 등록 저장소의 커밋 ∪ 프로젝트 이슈에 이어진 커밋, 최신 순 커서 */
    @Transactional(readOnly = true)
    public CommitPage projectCommits(long userId, long projectId, String cursor, Integer limit) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        int size = limit == null ? DEFAULT_PAGE : Math.max(1, Math.min(MAX_PAGE, limit));
        Instant cursorAt = FAR_FUTURE;
        long cursorId = Long.MAX_VALUE;
        if (cursor != null && !cursor.isBlank()) {
            Object[] decoded = decodeCursor(cursor);
            cursorAt = (Instant) decoded[0];
            cursorId = (Long) decoded[1];
        }
        List<DevCommit> rows = commits.findProjectRecent(projectId, cursorAt, cursorId, size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);

        // 커밋마다 이어진 이슈 — 볼 수 있는 프로젝트 것만(프로젝트마다 한 번 판정)
        Map<Long, List<IssueRef>> issuesByCommit = new HashMap<>();
        if (!rows.isEmpty()) {
            List<IssueCommit> links = issueCommits.findByCommitIdIn(rows.stream().map(DevCommit::getId).toList());
            Map<Long, Issue> linkedIssues = new HashMap<>();
            for (Issue i : issues.findAllById(links.stream().map(IssueCommit::getIssueId).distinct().toList())) linkedIssues.put(i.getId(), i);
            Map<Long, Boolean> visible = new HashMap<>();
            visible.put(projectId, true);
            for (IssueCommit link : links) {
                Issue i = linkedIssues.get(link.getIssueId());
                if (i == null) continue;
                if (!visible.computeIfAbsent(i.getProjectId(), pid -> projectService.canView(userId, pid))) continue;
                issuesByCommit.computeIfAbsent(link.getCommitId(), k -> new ArrayList<>()).add(new IssueRef(i.getId(), i.getKey()));
            }
            issuesByCommit.values().forEach(list -> list.sort(Comparator.comparing(IssueRef::issueKey)));
        }
        List<CommitView> items = rows.stream().map(c -> view(c, issuesByCommit.getOrDefault(c.getId(), List.of()))).toList();
        String next = more ? encodeCursor(rows.getLast()) : null;
        return new CommitPage(items, next);
    }

    private static CommitView view(DevCommit c, List<IssueRef> issueRefs) {
        return new CommitView(c.getId(), "COMMIT", c.getRepositoryName(), c.getRepositoryUrl(), c.getSha(), c.getShortSha(),
                c.getTitle(), c.getBodyExcerpt(), c.getAuthorName(), c.getAuthorEmail(), c.getAuthoredAt(), c.getBranch(),
                c.getUrl(), issueRefs);
    }

    private static CommitView legacyView(IssueWebLink w) {
        Matcher m = URL_SHA.matcher(w.getUrl());
        String sha = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
        return new CommitView(null, "WEB_LINK", null, null, sha, shortSha(sha), w.getTitle() == null ? w.getUrl() : w.getTitle(),
                null, null, null, null, null, w.getUrl(), null);
    }

    static String encodeCursor(DevCommit last) {
        String raw = last.getAuthoredAt().toString() + "|" + last.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Object[] decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            int bar = raw.indexOf('|');
            if (bar <= 0) throw new IllegalArgumentException();
            return new Object[] {Instant.parse(raw.substring(0, bar)), Long.parseLong(raw.substring(bar + 1))};
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("커서 형식이 올바르지 않습니다");
        }
    }
}
