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
        if (probe.notModified()) return new PullBatch(List.of(), probeEtag, true, true, cursor);
        String newProbeEtag = probe.etag();

        // 2) 오름차순 키셋 — 목록에 시각 필터가 없어 쪽 힌트에서 한 쪽 겹쳐 시작한다. 시작 쪽 첫 항목이 이미 커서 뒤면
        //    (그사이 앞쪽 PR이 갱신돼 뒤로 빠지며 당겨졌다) 처리하지 않은 것이 더 앞에 있을 수 있으니 물러서서 다시 본다
        int start = Math.max(1, (cursor.page() == null ? 1 : cursor.page()) - 1);
        int budget = settings.maxPages() * 2; // 물러서기까지 포함한 이번 실행의 쪽 상한
        List<PullItem> out = new ArrayList<>();
        PullCursor at = cursor;
        int page = start;
        boolean caughtUp = false;
        boolean first = true;
        int read = 0;
        while (read < budget) {
            ScmHttp.Response r = target.call().get(URI.create(repoApi(target)
                    + "/pulls?state=all&sort=updated&direction=asc&per_page=" + PAGE + "&page=" + page), null);
            read++;
            JsonNode items = array(r);
            if (first && page > 1 && items.size() > 0 && firstAfter(items, cursor)) {
                page = Math.max(1, page - settings.maxPages());
                first = page > 1; // 1쪽까지 물러섰으면 더 볼 것 없다
                continue;
            }
            first = false;
            boolean full = false;
            for (JsonNode pr : items) {
                Instant updated = instant(pr.path("updated_at").asText(null));
                long number = pr.path("number").asLong();
                if (updated == null || !cursor.before(updated, number)) continue; // 이미 처리했다(또는 첫 동기화 범위 밖)
                if (out.size() >= settings.maxPullRequests()) {
                    full = true;
                    break;
                }
                boolean merged = !pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode();
                out.add(new PullItem(pr.path("html_url").asText(null), number, "#" + number + " " + pr.path("title").asText(""),
                        pr.path("head").path("ref").asText(null), WebhookPayloads.gitHubState(pr.path("state").asText(null), merged),
                        updated, String.valueOf(number)));
                at = new PullCursor(updated, number, page);
            }
            if (full) break;
            if (nextLink(r) == null) {
                caughtUp = true;
                at = new PullCursor(at.updatedAt(), at.number(), page);
                break;
            }
            if (read >= settings.maxPages() && !out.isEmpty()) {
                at = new PullCursor(at.updatedAt(), at.number(), page + 1);
                break;
            }
            page++;
            at = new PullCursor(at.updatedAt(), at.number(), page);
        }
        return new PullBatch(out, newProbeEtag, false, caughtUp, at);
    }

    /** 이 쪽의 첫 항목이 이미 커서 뒤인가(= 처리하지 않은 것이 더 앞쪽에 있을 수 있다) */
    private static boolean firstAfter(JsonNode items, PullCursor cursor) {
        JsonNode head = items.get(0);
        Instant updated = instant(head.path("updated_at").asText(null));
        return updated != null && cursor.before(updated, head.path("number").asLong());
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
