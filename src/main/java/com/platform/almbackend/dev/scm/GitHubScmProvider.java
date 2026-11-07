package com.platform.almbackend.dev.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.almbackend.dev.DevDtos.CommitInput;
import com.platform.almbackend.dev.webhook.WebhookPayloads;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub REST v3(github.com·Enterprise Server 같은 API) — 커밋 {@code GET /repos/{o}/{r}/commits?sha=&since=},
 * PR {@code GET /repos/{o}/{r}/pulls?state=all&sort=updated&direction=desc}, PR 커밋 {@code GET …/pulls/{n}/commits}.
 * 다음 쪽은 {@code Link: <…>; rel="next"}. 인증은 {@code Authorization: Bearer <토큰>}(PAT·fine-grained·App 설치 토큰 모두).
 */
@Component
public class GitHubScmProvider implements ScmProvider {
    static final int PAGE = 50;
    /** 같은 시각 묶음을 끝까지 처리할 때의 안전 상한(한 실행) */
    static final int HARD_CAP = 500;
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");
    /** PR 커밋 API 상한(GitHub는 250건까지 준다) */
    private static final int PULL_COMMIT_PAGES = 3;

    private final ScmSyncSettings settings;

    public GitHubScmProvider(ScmSyncSettings settings) {
        this.settings = settings;
    }

    @Override
    public Map<String, String> authHeaders(String token) {
        return Map.of("Authorization", "Bearer " + token, "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28");
    }

    @Override
    public CommitBatch commits(Target target, String branch, Instant since, String etag) {
        URI first = URI.create(repoApi(target) + "/commits?sha=" + ScmEndpoints.encode(branch)
                + "&since=" + ScmEndpoints.encode(since.toString()) + "&per_page=100");
        List<CommitInput> out = new ArrayList<>();
        Instant max = null;
        String firstEtag = null;
        URI next = first;
        int page = 0;
        while (next != null && page < settings.maxPages()) {
            ScmHttp.Response r = target.call().get(next, page == 0 ? etag : null);
            if (page == 0) {
                if (r.notModified()) return new CommitBatch(List.of(), etag, true, false, null);
                firstEtag = r.etag();
            }
            for (JsonNode c : array(r)) {
                JsonNode commit = c.path("commit");
                Instant committed = instant(commit.path("committer").path("date").asText(null));
                if (committed != null && (max == null || committed.isAfter(max))) max = committed;
                out.add(new CommitInput(c.path("sha").asText(null), commit.path("message").asText(null),
                        commit.path("author").path("name").asText(null), commit.path("author").path("email").asText(null),
                        instant(commit.path("author").path("date").asText(null)), branch, c.path("html_url").asText(null)));
            }
            next = nextLink(r);
            page++;
        }
        return new CommitBatch(out, firstEtag, false, next != null, max);
    }

    @Override
    public PullBatch pulls(Target target, PullCursor cursor, String probeEtag) {
        // 1) 변경 탐침 — 가장 최근 갱신 1건. 무엇이든 갱신되면 그 PR이 맨 앞에 와 ETag가 바뀐다
        ScmHttp.Response probe = target.call().get(URI.create(repoApi(target) + "/pulls?state=all&sort=updated&direction=desc&per_page=1"), probeEtag);
        if (probe.notModified()) return new PullBatch(List.of(), probeEtag, true, true, cursor, false);
        String newProbeEtag = probe.etag();

        // 2) 오름차순 — 목록에 시각 필터가 없어 쪽 힌트에서 한 쪽 겹쳐 시작한다. 시작 쪽 첫 항목이 이미 커서 뒤면(그사이 앞쪽 PR이
        //    갱신돼 뒤로 빠지며 당겨졌다) 처리하지 않은 것이 더 앞에 있을 수 있으니 물러서서 다시 본다. 물러서다 예산을 다 쓰면
        //    줄인 쪽을 힌트로 남겨 다음 실행이 이어서 물러선다
        int page = Math.max(1, (cursor.page() == null ? 1 : cursor.page()) - 1);
        int budget = settings.maxPages() * 2;
        PullScan scan = new PullScan(cursor.updatedAt(), settings.maxPullRequests(), HARD_CAP);
        boolean backingOff = page > 1;
        boolean reachedEnd = false;
        int read = 0;
        while (read < budget) {
            ScmHttp.Response r = target.call().get(URI.create(repoApi(target)
                    + "/pulls?state=all&sort=updated&direction=asc&per_page=" + PAGE + "&page=" + page), null);
            read++;
            JsonNode items = array(r);
            // 빈 쪽(힌트가 목록 끝을 넘었다 — PR이 지워졌거나 당겨졌다)도 물러선다: 끝으로 읽으면 앞의 처리하지 않은 것을 놓친다
            if (backingOff && page > 1 && (items.isEmpty() || firstAfter(items, cursor))) {
                page = Math.max(1, page - settings.maxPages());
                continue;
            }
            backingOff = false;
            boolean stop = false;
            for (JsonNode pr : items) {
                long number = pr.path("number").asLong();
                boolean merged = !pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode();
                PullItem item = new PullItem(pr.path("html_url").asText(null), number, "#" + number + " " + pr.path("title").asText(""),
                        pr.path("head").path("ref").asText(null), WebhookPayloads.gitHubState(pr.path("state").asText(null), merged),
                        instant(pr.path("updated_at").asText(null)), String.valueOf(number));
                if (!scan.offer(item)) {
                    stop = true;
                    break;
                }
            }
            if (stop) break;
            if (nextLink(r) == null) {
                reachedEnd = true;
                scan.reachedEnd();
                break;
            }
            page++;
        }
        return finish(scan, cursor, newProbeEtag, reachedEnd, page);
    }

    /** 읽기 결과 → 배치. 막혔으면 묶음 시각을 지나 강제 전진(ScmSyncRunner가 경고를 남긴다) */
    static PullBatch finish(PullScan scan, PullCursor cursor, String probeEtag, boolean reachedEnd, Integer page) {
        Instant next = scan.next();
        boolean forced = false;
        if (!reachedEnd && next.equals(cursor.updatedAt()) && scan.inGroup() && !scan.items().isEmpty()) {
            next = scan.forcedNext();
            forced = true;
        }
        return new PullBatch(scan.items(), probeEtag, false, reachedEnd, new PullCursor(next, page), forced);
    }

    /** 이 쪽의 첫 항목이 이미 커서 뒤인가(= 처리하지 않은 것이 더 앞쪽에 있을 수 있다) */
    private static boolean firstAfter(JsonNode items, PullCursor cursor) {
        JsonNode head = items.get(0);
        Instant updated = instant(head.path("updated_at").asText(null));
        return updated != null && updated.isAfter(cursor.updatedAt());
    }

    @Override
    public List<CommitInput> pullCommits(Target target, PullItem pull) {
        URI next = URI.create(repoApi(target) + "/pulls/" + pull.apiRef() + "/commits?per_page=100");
        List<CommitInput> out = new ArrayList<>();
        int page = 0;
        while (next != null && page < PULL_COMMIT_PAGES) {
            ScmHttp.Response r = target.call().get(next, null);
            for (JsonNode c : array(r)) {
                JsonNode commit = c.path("commit");
                out.add(new CommitInput(c.path("sha").asText(null), commit.path("message").asText(null),
                        commit.path("author").path("name").asText(null), commit.path("author").path("email").asText(null),
                        instant(commit.path("author").path("date").asText(null)), pull.sourceBranch(), c.path("html_url").asText(null)));
            }
            next = nextLink(r);
            page++;
        }
        return out;
    }

    private static String repoApi(Target target) {
        String[] parts = ScmEndpoints.repositoryPath(target.repositoryUrl()).split("/");
        return target.apiBase() + "/repos/" + parts[0] + "/" + parts[1];
    }

    private static URI nextLink(ScmHttp.Response r) {
        String link = r.header("link");
        if (link == null) return null;
        Matcher m = NEXT.matcher(link);
        return m.find() ? URI.create(m.group(1)) : null; // 대상 검사는 다음 get에서(ScmHttp.Call.guard)
    }

    static JsonNode array(ScmHttp.Response r) {
        if (r.body() == null || !r.body().isArray()) throw new ScmHttp.ScmException("Git 서버 응답 형식이 예상과 다릅니다 — API 주소·제공자를 확인하세요");
        return r.body();
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
