package com.platform.almbackend.dev.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.platform.almbackend.dev.DevDtos.CommitInput;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub·GitLab 웹훅 본문 → 공통 이벤트. 필요한 필드만 읽고 나머지는 보지 않는다(호스팅이 필드를 늘려도 깨지지 않게).
 *
 * <ul>
 *   <li>push(GitHub {@code push} · GitLab {@code Push Hook}): {@code refs/heads/*}의 커밋 목록. 브랜치 삭제·태그 push는 커밋이 없어
 *       무시한다. GitHub는 한 push에 커밋을 최대 2048건, GitLab은 20건만 싣는다 — 넘친 커밋은 sync-commits 스크립트가 메운다.</li>
 *   <li>PR(GitHub {@code pull_request} · GitLab {@code Merge Request Hook}): 제목과 원본 브랜치 이름에서 이슈 키를 찾는다
 *       (지라와 같은 규칙 — 본문은 보지 않는다: "관련 PLAT-3" 같은 언급까지 잇지 않게).</li>
 *   <li>ping(GitHub 웹훅 등록 직후): 저장소 대조만 한다.</li>
 * </ul>
 */
public final class WebhookPayloads {

    public enum Kind { PUSH, PULL_REQUEST, PING, IGNORED }

    public record PullRequest(String url, String title, List<String> keyTexts) {}

    /**
     * @param event         호스팅이 보낸 이벤트 이름(기록용, 40자)
     * @param repositoryUrl 본문의 저장소 웹 URL(정규화 전, 없을 수 있다)
     * @param ignoredReason IGNORED일 때 이유(화면에 그대로 뜬다)
     */
    public record Parsed(Kind kind, String event, String repositoryUrl, String branch, List<CommitInput> commits,
                         PullRequest pullRequest, String ignoredReason) {
        static Parsed ignored(String event, String repositoryUrl, String reason) {
            return new Parsed(Kind.IGNORED, event, repositoryUrl, null, List.of(), null, reason);
        }
    }

    private static final String HEADS = "refs/heads/";

    private WebhookPayloads() {}

    public static Parsed gitHub(String event, JsonNode root) {
        String name = event == null || event.isBlank() ? "unknown" : clip(event.trim(), 40);
        String repo = text(root.path("repository"), "html_url");
        return switch (name) {
            case "ping" -> new Parsed(Kind.PING, name, repo, null, List.of(), null, null);
            case "push" -> push(name, repo, root, root.path("deleted").asBoolean(false));
            case "pull_request" -> {
                JsonNode pr = root.path("pull_request");
                String url = text(pr, "html_url");
                if (url == null) yield Parsed.ignored(name, repo, "PR 주소가 없습니다");
                String number = pr.path("number").isNumber() ? "#" + pr.path("number").asLong() + " " : "";
                String title = text(pr, "title");
                String head = text(pr.path("head"), "ref");
                yield new Parsed(Kind.PULL_REQUEST, name, repo, head, List.of(),
                        new PullRequest(url, number + (title == null ? "" : title), texts(title, head)), null);
            }
            default -> Parsed.ignored(name, repo, "처리하지 않는 이벤트입니다(" + name + ")");
        };
    }

    public static Parsed gitLab(String event, JsonNode root) {
        String name = event == null || event.isBlank() ? "unknown" : clip(event.trim(), 40);
        String repo = text(root.path("project"), "web_url");
        return switch (name) {
            case "Push Hook" -> push(name, repo, root, isZeroSha(text(root, "after")));
            case "Merge Request Hook" -> {
                JsonNode mr = root.path("object_attributes");
                String url = text(mr, "url");
                if (url == null) yield Parsed.ignored(name, repo, "MR 주소가 없습니다");
                String number = mr.path("iid").isNumber() ? "!" + mr.path("iid").asLong() + " " : "";
                String title = text(mr, "title");
                String source = text(mr, "source_branch");
                yield new Parsed(Kind.PULL_REQUEST, name, repo, source, List.of(),
                        new PullRequest(url, number + (title == null ? "" : title), texts(title, source)), null);
            }
            default -> Parsed.ignored(name, repo, "처리하지 않는 이벤트입니다(" + name + ")");
        };
    }

    private static Parsed push(String event, String repo, JsonNode root, boolean deleted) {
        String ref = text(root, "ref");
        if (ref == null || !ref.startsWith(HEADS)) return Parsed.ignored(event, repo, "브랜치 push가 아닙니다(태그 등)");
        if (deleted) return Parsed.ignored(event, repo, "브랜치 삭제입니다");
        String branch = ref.substring(HEADS.length());
        List<CommitInput> commits = new ArrayList<>();
        for (JsonNode c : root.path("commits")) {
            JsonNode author = c.path("author");
            commits.add(new CommitInput(text(c, "id"), text(c, "message"), text(author, "name"), text(author, "email"),
                    instant(text(c, "timestamp")), branch, text(c, "url")));
        }
        if (commits.isEmpty()) return Parsed.ignored(event, repo, "커밋이 없는 push입니다");
        return new Parsed(Kind.PUSH, event, repo, branch, commits, null, null);
    }

    private static List<String> texts(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) if (v != null && !v.isBlank()) out.add(v);
        return out;
    }

    private static boolean isZeroSha(String sha) {
        return sha != null && !sha.isEmpty() && sha.chars().allMatch(ch -> ch == '0');
    }

    /** 형식이 틀리면 null — 수집이 그 커밋만 INVALID로 돌려준다 */
    private static Instant instant(String value) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
