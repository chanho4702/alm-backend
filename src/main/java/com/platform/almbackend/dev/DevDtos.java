package com.platform.almbackend.dev;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 개발 기록(커밋 연결, V41) 요청·응답 */
public final class DevDtos {
    private DevDtos() {}

    // ── 저장소 등록 ──

    @Schema(description = "프로젝트 저장소 하나")
    public record RepositoryInput(
            @Schema(description = "표시 이름(비우면 owner/repo)", example = "alm-backend") String name,
            @Schema(description = "저장소 URL — 정규화해 저장한다(끝 / · .git 제거, SSH 형식은 https로)",
                    example = "https://github.com/chanho4702/alm-backend") String url,
            @Schema(description = "기본 브랜치(비우면 main)", example = "main") String defaultBranch) {}

    @Schema(description = "저장소 목록 통째 교체 — 보낸 목록이 곧 등록 상태다(최대 20개)")
    public record RepositoriesRequest(List<RepositoryInput> repositories) {}

    @Schema(description = "등록된 저장소")
    public record RepositoryResponse(long id, String name, String url, String defaultBranch) {}

    // ── 개발 설정(V43) ──

    @Schema(description = "프로젝트 개발 설정 수정 — expectedVersion이 어긋나면 409")
    public record DevSettingsRequest(
            @Schema(description = "git 브랜치 이름 접두. 빈 문자열 = 접두 없음. 끝 /는 지운다", example = "feature")
            String branchPrefix,
            @Schema(description = "읽은 version(행이 없으면 0)", example = "0") Integer expectedVersion) {}

    @Schema(description = "프로젝트 개발 설정 — 브랜치 이름은 [<branchPrefix>/]<키 소문자>-<슬러그>")
    public record DevSettingsResponse(
            long projectId,
            @Schema(description = "브랜치 이름 접두(없으면 빈 문자열)", example = "feature") String branchPrefix,
            @Schema(description = "낙관적 락 버전 — 저장한 적 없으면 0", example = "1") int version) {}

    // ── 수집 ──

    @Schema(description = "커밋 한 건")
    public record CommitInput(
            @Schema(description = "전체 sha(16진 40자 또는 64자)", example = "3dc8bbc1f0e2a7d4c5b6a7980123456789abcdef") String sha,
            @Schema(description = "커밋 메시지 전체 — 첫 줄이 제목, 나머지가 본문. 이슈 키는 여기서 찾는다",
                    example = "feat(alm): 커밋 연결 [PLAT-12]\n\nRefs: PLAT-13") String message,
            @Schema(description = "작성자 이름") String authorName,
            @Schema(description = "작성자 이메일") String authorEmail,
            @Schema(description = "작성 시각(ISO-8601)", example = "2026-10-25T03:00:00Z") Instant authoredAt,
            @Schema(description = "브랜치(비우면 요청의 branch)") String branch,
            @Schema(description = "커밋 웹 URL(비우면 <저장소 URL>/commit/<sha>)") String url) {}

    @Schema(description = "커밋 수집 요청 — 한 저장소의 커밋 묶음(최대 500건)")
    public record IngestRequest(
            @Schema(description = "저장소 URL", example = "https://github.com/chanho4702/alm-backend") String repository,
            @Schema(description = "커밋들의 기본 브랜치", example = "main") String branch,
            List<CommitInput> commits) {}

    @Schema(description = "연결된 이슈")
    public record IssueRef(long issueId, String issueKey) {}

    @Schema(description = "연결하지 못한 이슈 키와 이유 — ISSUE_NOT_FOUND · NO_PERMISSION(대상 프로젝트 EDIT 없음) · PROJECT_ARCHIVED")
    public record SkippedKey(String issueKey, String reason) {}

    @Schema(description = "커밋 한 건의 처리 결과")
    public record CommitResult(
            @Schema(description = "요청의 sha(정규화 전 값일 수 있다)") String sha,
            String shortSha,
            @Schema(description = "STORED(새로 저장) · DUPLICATE(이미 있던 커밋) · SKIPPED(저장 안 함 — reason) · INVALID(형식 오류 — reason)")
            String status,
            @Schema(description = "SKIPPED·INVALID 이유", example = "NO_LINKABLE_ISSUE") String reason,
            Long commitId,
            @Schema(description = "이번에 새로 이어진 이슈") List<IssueRef> linked,
            @Schema(description = "이미 이어져 있던 이슈") List<IssueRef> alreadyLinked,
            @Schema(description = "건너뛴 이슈 키") List<SkippedKey> skipped) {}

    @Schema(description = "수집 결과 — 커밋마다 결과 하나(요청 순서)")
    public record IngestResponse(String repository, int received, int stored, int duplicates, int skipped, int invalid,
                                 @Schema(description = "새로 만든 이슈↔커밋 연결 수") int linksCreated,
                                 List<CommitResult> results) {}

    @Schema(description = "PR 연결 결과(저장소 웹훅) — 제목·브랜치 이름 안 이슈 키마다")
    public record PullRequestLinkResult(String url, List<IssueRef> linked, List<IssueRef> alreadyLinked, List<SkippedKey> skipped) {}

    // ── 조회 ──

    @Schema(description = "커밋(조회용)")
    public record CommitView(
            @Schema(description = "수집한 커밋 id. 웹 링크(kind=COMMIT)로만 붙은 옛 연결이면 null") Long id,
            @Schema(description = "COMMIT(수집) · WEB_LINK(웹 링크 kind=COMMIT)") String source,
            String repositoryName, String repositoryUrl, String sha, String shortSha, String title,
            @Schema(description = "본문 앞부분(최대 2000자)") String message,
            String authorName, String authorEmail,
            @Schema(description = "작성 시각(WEB_LINK면 null)") Instant authoredAt,
            String branch, String url,
            @Schema(description = "프로젝트 최근 커밋에서만 — 이어진 이슈(볼 수 있는 프로젝트 것만)") List<IssueRef> issues) {}

    @Schema(description = "PR(웹 링크 kind=PR — link_pr 도구·화면에서 붙인 것)")
    public record PullRequestView(long id, String url, String title, long createdBy, Instant createdAt,
                                  @Schema(description = "OPEN · MERGED · CLOSED(V45 — 웹훅·폴링이 채운다). 모르면 null") String state) {}

    @Schema(description = "개발 요약")
    public record DevelopmentSummary(
            @Schema(description = "커밋 수(수집 + 옛 웹 링크)") int commits,
            @Schema(description = "커밋이 걸친 저장소 수") int repositories,
            int pullRequests,
            @Schema(description = "가장 최근 커밋 작성 시각") Instant lastCommitAt) {}

    @Schema(description = "이슈 개발 정보 — 커밋 최신 순(최대 200건, 더 있으면 truncated) + PR")
    public record DevelopmentResponse(long issueId, String issueKey, DevelopmentSummary summary,
                                      List<CommitView> commits, boolean truncated, List<PullRequestView> pullRequests) {}

    @Schema(description = "프로젝트 최근 커밋 한 쪽 — nextCursor가 null이면 끝")
    public record CommitPage(List<CommitView> items, String nextCursor) {}
}
