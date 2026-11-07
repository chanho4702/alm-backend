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
    public PullBatch pulls(Target target, Instant since, Instant skipNewerThan, String etag) {
        URI next = URI.create(repoApi(target) + "/pulls?state=all&sort=updated&direction=desc&per_page=50");
        List<PullItem> out = new ArrayList<>();
        Instant max = null;
        Instant oldest = null;
        String firstEtag = null;
        int page = 0;
        boolean done = false;
        boolean truncated = false;
        while (next != null && page < settings.maxPages() && !done) {
            ScmHttp.Response r = target.call().get(next, page == 0 ? etag : null);
            if (page == 0) {
                if (r.notModified()) return new PullBatch(List.of(), etag, true, null, false, null);
                firstEtag = r.etag();
            }
            for (JsonNode pr : array(r)) {
                Instant updated = instant(pr.path("updated_at").asText(null));
                if (updated == null || updated.isBefore(since)) {
                    done = true; // 갱신 시각 내림차순 — 커서보다 오래된 것부터는 볼 필요 없다
                    break;
                }
                if (skipNewerThan != null && updated.isAfter(skipNewerThan)) continue; // 창 이어 읽기 — 이미 처리했다
                if (out.size() >= settings.maxPullRequests()) {
                    done = true;
                    truncated = true; // 남은 것은 다음 실행이 이어 읽는다
                    break;
                }
                if (max == null || updated.isAfter(max)) max = updated;
                oldest = updated;
                boolean merged = !pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode();
                out.add(new PullItem(pr.path("html_url").asText(null), String.valueOf(pr.path("number").asLong()),
                        "#" + pr.path("number").asLong() + " " + pr.path("title").asText(""),
                        pr.path("head").path("ref").asText(null),
                        WebhookPayloads.gitHubState(pr.path("state").asText(null), merged), updated,
                        String.valueOf(pr.path("number").asLong())));
            }
            next = done ? null : nextLink(r);
            page++;
        }
        if (next != null) truncated = true; // 쪽 상한
        return new PullBatch(out, firstEtag, false, max, truncated, oldest);
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
