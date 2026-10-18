package com.platform.almbackend.collab;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.IssueLink;
import com.platform.almbackend.domain.IssueWebLink;
import com.platform.almbackend.domain.LinkTypeDef;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.IssueLinkRepository;
import com.platform.almbackend.repository.IssueRepository;
import com.platform.almbackend.repository.IssueWebLinkRepository;
import com.platform.almbackend.repository.LinkTypeDefRepository;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 이슈 사이 링크와 외부(웹) 링크 — 링크 중복 금지, 대상 프로젝트를 볼 수 없으면 만들지 못하고 목록에서는 가린다.
 * (2026-10-18 CollaborationService에서 분리 — 동작 불변)
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IssueLinkService {
    private final IssueRepository issues;
    private final IssueLinkRepository links;
    private final IssueWebLinkRepository webLinks;
    private final LinkTypeDefRepository linkTypes;
    private final ProjectService projectService;
    private final IssueActivityService activityLog;

    public record LinkResponse(long id, long sourceId, long targetId, String type) {
        static LinkResponse from(IssueLink l) { return new LinkResponse(l.getId(), l.getSourceId(), l.getTargetId(), l.getType()); }
    }
    public record WebLinkResponse(long id, long issueId, String url, String title, String kind, long createdBy, Instant createdAt) {
        static WebLinkResponse from(IssueWebLink w) {
            return new WebLinkResponse(w.getId(), w.getIssueId(), w.getUrl(), w.getTitle(), w.getKind(), w.getCreatedBy(), w.getCreatedAt());
        }
    }
    /** created=false면 이미 있던 링크를 그대로 돌려준 것(멱등) — 컨트롤러가 200/201을 가른다 */
    public record WebLinkOutcome(WebLinkResponse link, boolean created) {}
    /**
     * blocks: outward=차단함, inward=차단됨 / relates: 항상 outward.
     * {@code restricted}면 반대편 프로젝트를 볼 수 없어 {@code other}가 키·id·타입·상태만 남은 축약본이다
     * (제목은 "볼 수 없는 이슈", 설명·담당자·라벨 등은 비운다 — 감사 A-01, 2026-10-18 필드 추가).
     */
    public record LinkView(LinkResponse link, IssueResponse other, String direction, boolean restricted) {}

    /** 링크 반대편을 볼 권한이 없을 때 제목 대신 보이는 문구 */
    public static final String RESTRICTED_TITLE = "볼 수 없는 이슈";

    // ── 링크 ──

    @Transactional(readOnly = true)
    public List<LinkView> links(long userId, long issueId) {
        Issue self = require(userId, issueId, AlmAction.VIEW);
        List<IssueLink> rows = links.findBySourceIdOrTargetId(issueId, issueId);
        if (rows.isEmpty()) return List.of();
        // 반대편 이슈는 한 번에, 링크 타입은 맵 하나로 — 링크마다 두 번씩 읽던 N+1(감사 A-20)
        List<Long> otherIds = rows.stream()
                .map(l -> l.getSourceId() == issueId ? l.getTargetId() : l.getSourceId()).distinct().toList();
        Map<Long, Issue> others = new HashMap<>();
        for (Issue other : issues.findAllById(otherIds)) others.put(other.getId(), other);
        Map<String, Boolean> symmetricByType = new HashMap<>();
        for (LinkTypeDef def : linkTypes.findAll()) symmetricByType.put(def.getId(), def.isSymmetric());
        // 반대편 프로젝트를 볼 수 있는가 — 프로젝트마다 한 번만 판정한다(감사 A-01)
        Map<Long, Boolean> visible = new HashMap<>();
        visible.put(self.getProjectId(), true);
        List<LinkView> views = new ArrayList<>();
        for (IssueLink link : rows) {
            long otherId = link.getSourceId() == issueId ? link.getTargetId() : link.getSourceId();
            Issue other = others.get(otherId);
            if (other == null) continue;
            boolean symmetric = symmetricByType.getOrDefault(link.getType(), false);
            String direction = symmetric || link.getSourceId() == issueId ? "outward" : "inward";
            boolean canSee = visible.computeIfAbsent(other.getProjectId(), pid -> projectService.canView(userId, pid));
            views.add(new LinkView(LinkResponse.from(link),
                    canSee ? IssueResponse.from(other) : restricted(other), direction, !canSee));
        }
        return views;
    }

    public LinkResponse addLink(long userId, long sourceId, long targetId, String type) {
        Issue source = require(userId, sourceId, AlmAction.EDIT);
        if (sourceId == targetId) throw new IllegalArgumentException("자기 자신과는 연결할 수 없습니다");
        // 대상도 볼 수 있어야 한다(지라: 대상 Browse 권한) — 링크로 남의 프로젝트 이슈를 끌어와 읽는 길을 막는다(감사 A-01)
        Issue target = require(userId, targetId, AlmAction.VIEW);
        LinkTypeDef def = linkTypes.findById(type == null ? "" : type)
                .orElseThrow(() -> new IllegalArgumentException("없는 링크 타입입니다: " + type));
        boolean symmetric = def.isSymmetric();
        boolean duplicate = links.findBySourceIdOrTargetId(sourceId, sourceId).stream().anyMatch(l -> {
            if (!l.getType().equals(type)) return false;
            if (l.getSourceId() == sourceId && l.getTargetId() == targetId) return true;
            return symmetric && l.getSourceId() == targetId && l.getTargetId() == sourceId;
        });
        if (duplicate) throw new IllegalArgumentException("이미 연결돼 있습니다");
        IssueLink link = links.save(IssueLink.of(sourceId, targetId, type));
        Instant now = now();
        String label = def.getName();
        activityLog.record(source.getId(), userId, "link", label + " 링크: " + target.getKey(), now);
        activityLog.record(target.getId(), userId, "link", label + " 링크: " + source.getKey(), now);
        return LinkResponse.from(link);
    }

    public void removeLink(long userId, long linkId) {
        IssueLink link = links.findById(linkId).orElseThrow(() -> new NotFoundException("링크를 찾을 수 없습니다"));
        require(userId, link.getSourceId(), AlmAction.EDIT);
        links.delete(link);
    }

    // ── 외부(웹) 링크 — 에이전트 git 연결(P2a): PR·커밋·웹 URL을 이슈에 붙인다 ──

    private static final Set<String> WEB_LINK_KINDS = Set.of("PR", "COMMIT", "WEB");

    @Transactional(readOnly = true)
    public List<WebLinkResponse> webLinks(long userId, long issueId) {
        require(userId, issueId, AlmAction.VIEW);
        return webLinks.findByIssueIdOrderByIdDesc(issueId).stream().map(WebLinkResponse::from).toList();
    }

    /**
     * 같은 issue+url이면 새로 만들지 않고 기존 것을 돌려준다 — 커밋 파서 재실행에도 중복이 안 쌓인다.
     * 동시 요청이 조회를 함께 통과해도 유니크 제약(V25)에 걸린 쪽은 먼저 들어간 행을 돌려받는다.
     */
    public WebLinkOutcome addWebLink(long userId, long issueId, String url, String title, String kind) {
        Issue issue = require(userId, issueId, AlmAction.EDIT);
        String normalizedUrl = requireUrl(url);
        String normalizedKind = requireKind(kind);
        Optional<IssueWebLink> existing = webLinks.findByIssueIdAndUrl(issueId, normalizedUrl);
        if (existing.isPresent()) {
            return new WebLinkOutcome(WebLinkResponse.from(existing.get()), false);
        }
        String normalizedTitle = normalizeTitle(title);
        Instant now = now();
        boolean inserted = webLinks.insertIfAbsent(issueId, normalizedUrl, normalizedTitle, normalizedKind, userId, now) == 1;
        IssueWebLink saved = webLinks.findByIssueIdAndUrl(issueId, normalizedUrl)
                .orElseThrow(() -> new IllegalStateException("웹 링크 저장 직후 조회에 실패했습니다"));
        if (!inserted) {
            return new WebLinkOutcome(WebLinkResponse.from(saved), false);
        }
        String detail = normalizedKind + ": " + (normalizedTitle != null ? normalizedTitle : normalizedUrl);
        activityLog.record(issue.getId(), userId, "web_link_added", detail.length() > 500 ? detail.substring(0, 500) : detail, now);
        return new WebLinkOutcome(WebLinkResponse.from(saved), true);
    }

    public void removeWebLink(long userId, long id) {
        IssueWebLink link = webLinks.findById(id).orElseThrow(() -> new NotFoundException("웹 링크를 찾을 수 없습니다"));
        require(userId, link.getIssueId(), AlmAction.EDIT);
        webLinks.delete(link);
    }

    private static String requireUrl(String value) {
        String trimmed = requireText(value, "URL을 입력하세요");
        if (trimmed.length() > 500) throw new IllegalArgumentException("URL이 너무 깁니다");
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 형식이 올바르지 않습니다");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
            throw new IllegalArgumentException("URL 형식이 올바르지 않습니다");
        }
        return trimmed;
    }

    private static String requireKind(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!WEB_LINK_KINDS.contains(normalized)) throw new IllegalArgumentException("없는 링크 종류입니다: " + value);
        return normalized;
    }

    private static String normalizeTitle(String title) {
        if (title == null) return null;
        String trimmed = title.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > 200) throw new IllegalArgumentException("제목이 너무 깁니다");
        return trimmed;
    }

    /**
     * 볼 수 없는 링크 반대편 — 키·id·타입·상태만 남긴다. 화면이 아이콘·키로 행을 그릴 수 있게 하되
     * 제목·설명·사람·라벨처럼 내용을 드러내는 값은 비운다.
     */
    private static IssueResponse restricted(Issue other) {
        // 허용: id·key·type·status. 나머지(프로젝트·우선순위·해결·버전·정렬·시각)는 전부 중립값 — 볼 수 없는 이슈의
        // 진행 상황·활동 시점을 흘리지 않는다(Codex 교차검증 P2)
        return new IssueResponse(other.getId(), other.getKey(), 0L, RESTRICTED_TITLE, "",
                other.getType(), other.getStatus(), null, null, 0L, null, null, null, null,
                null, null, List.of(), List.of(), 0L, 0,
                null, null, null, null);
    }

    private Issue require(long userId, long issueId, AlmAction action) {
        Issue issue = issues.findById(issueId).orElseThrow(() -> new NotFoundException("이슈를 찾을 수 없습니다"));
        projectService.require(userId, issue.getProjectId(), action);
        return issue;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
