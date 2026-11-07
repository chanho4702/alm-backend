package com.platform.almbackend.dev.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.almbackend.dev.DevDtos.CommitInput;
import com.platform.almbackend.dev.webhook.WebhookPayloads;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.platform.almbackend.dev.scm.GitHubScmProvider.array;
import static com.platform.almbackend.dev.scm.GitHubScmProvider.instant;

/**
 * GitLab REST v4(gitlab.com·self-managed 같은 API) — 커밋 {@code GET /projects/{경로}/repository/commits?ref_name=&since=},
 * MR {@code GET /projects/{경로}/merge_requests?state=all&order_by=updated_at&updated_after=}, MR 커밋 {@code …/merge_requests/{iid}/commits}.
 * 프로젝트는 URL 인코딩한 경로(하위 그룹 포함)로 가리킨다. 다음 쪽은 {@code X-Next-Page}. 인증은 {@code PRIVATE-TOKEN}(개인·프로젝트·그룹 토큰).
 */
@Component
public class GitLabScmProvider implements ScmProvider {
    private static final int PULL_COMMIT_PAGES = 3;

    private final ScmSyncSettings settings;

    public GitLabScmProvider(ScmSyncSettings settings) {
        this.settings = settings;
    }

    @Override
    public Map<String, String> authHeaders(String token) {
        return Map.of("PRIVATE-TOKEN", token, "Accept", "application/json");
    }

    @Override
    public CommitBatch commits(Target target, String branch, Instant since, String etag) {
        String base = projectApi(target) + "/repository/commits?ref_name=" + ScmEndpoints.encode(branch)
                + "&since=" + ScmEndpoints.encode(since.toString()) + "&per_page=100";
        List<CommitInput> out = new ArrayList<>();
        Instant max = null;
        String firstEtag = null;
        String nextPage = "1";
        int page = 0;
        while (nextPage != null && page < settings.maxPages()) {
            ScmHttp.Response r = target.call().get(URI.create(base + "&page=" + nextPage), page == 0 ? etag : null);
            if (page == 0) {
                if (r.notModified()) return new CommitBatch(List.of(), etag, true, false, null);
                firstEtag = r.etag();
            }
            for (JsonNode c : array(r)) {
                Instant committed = instant(c.path("committed_date").asText(null));
                if (committed != null && (max == null || committed.isAfter(max))) max = committed;
                out.add(commit(target, c, branch));
            }
            nextPage = next(r);
            page++;
        }
        return new CommitBatch(out, firstEtag, false, nextPage != null, max);
    }

    @Override
    public PullBatch pulls(Target target, Instant since, Instant skipNewerThan, String etag) {
        String base = projectApi(target) + "/merge_requests?state=all&order_by=updated_at&sort=desc&per_page=50&updated_after="
                + ScmEndpoints.encode(since.toString());
        List<PullItem> out = new ArrayList<>();
        Instant max = null;
        Instant oldest = null;
        String firstEtag = null;
        String nextPage = "1";
        int page = 0;
        boolean truncated = false;
        while (nextPage != null && page < settings.maxPages() && !truncated) {
            ScmHttp.Response r = target.call().get(URI.create(base + "&page=" + nextPage), page == 0 ? etag : null);
            if (page == 0) {
                if (r.notModified()) return new PullBatch(List.of(), etag, true, null, false, null);
                firstEtag = r.etag();
            }
            for (JsonNode mr : array(r)) {
                Instant updated = instant(mr.path("updated_at").asText(null));
                if (updated != null && skipNewerThan != null && updated.isAfter(skipNewerThan)) continue; // 창 이어 읽기
                if (out.size() >= settings.maxPullRequests()) {
                    truncated = true;
                    break;
                }
                if (updated != null && (max == null || updated.isAfter(max))) max = updated;
                if (updated != null) oldest = updated;
                long iid = mr.path("iid").asLong();
                out.add(new PullItem(mr.path("web_url").asText(null), String.valueOf(iid), "!" + iid + " " + mr.path("title").asText(""),
                        mr.path("source_branch").asText(null), WebhookPayloads.gitLabState(mr.path("state").asText(null)), updated,
                        String.valueOf(iid)));
            }
            nextPage = next(r);
            page++;
        }
        if (!truncated && nextPage != null) truncated = true; // 쪽 상한
        return new PullBatch(out, firstEtag, false, max, truncated, oldest);
    }

    @Override
    public List<CommitInput> pullCommits(Target target, PullItem pull) {
        String base = projectApi(target) + "/merge_requests/" + pull.apiRef() + "/commits?per_page=100";
        List<CommitInput> out = new ArrayList<>();
        String nextPage = "1";
        int page = 0;
        while (nextPage != null && page < PULL_COMMIT_PAGES) {
            ScmHttp.Response r = target.call().get(URI.create(base + "&page=" + nextPage), null);
            for (JsonNode c : array(r)) out.add(commit(target, c, pull.sourceBranch()));
            nextPage = next(r);
            page++;
        }
        return out;
    }

    private static CommitInput commit(Target target, JsonNode c, String branch) {
        String sha = c.path("id").asText(null);
        String url = c.path("web_url").asText(null);
        if (url == null && sha != null) url = target.repositoryUrl() + "/-/commit/" + sha;
        return new CommitInput(sha, c.path("message").asText(null), c.path("author_name").asText(null),
                c.path("author_email").asText(null), instant(c.path("authored_date").asText(null)), branch, url);
    }

    private static String projectApi(Target target) {
        return target.apiBase() + "/projects/" + ScmEndpoints.encode(ScmEndpoints.repositoryPath(target.repositoryUrl()));
    }

    /** X-Next-Page — 숫자일 때만(다음 쪽 URL은 우리가 만든다: 응답이 다른 호스트를 가리키게 할 수 없다) */
    private static String next(ScmHttp.Response r) {
        String value = r.header("x-next-page");
        return value != null && value.trim().matches("\\d{1,6}") ? value.trim() : null;
    }
}
