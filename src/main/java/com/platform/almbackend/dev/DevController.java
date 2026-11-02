package com.platform.almbackend.dev;

import com.platform.almbackend.dev.DevDtos.CommitPage;
import com.platform.almbackend.dev.DevDtos.DevSettingsRequest;
import com.platform.almbackend.dev.DevDtos.DevSettingsResponse;
import com.platform.almbackend.dev.DevDtos.DevelopmentResponse;
import com.platform.almbackend.dev.DevDtos.IngestRequest;
import com.platform.almbackend.dev.DevDtos.IngestResponse;
import com.platform.almbackend.dev.DevDtos.RepositoriesRequest;
import com.platform.almbackend.dev.DevDtos.RepositoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * 개발 기록 — 저장소 등록·커밋 수집·개발 정보 조회(V41). 인증은 다른 API와 같은 JWT다: 사람은 PAT(게이트웨이가 JWT로 교환,
 * 스코프 {@code alm:write}), AI 직원은 agent-service가 받은 페르소나 토큰.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Development", description = "개발 기록 — 저장소 등록·커밋 수집·이슈 개발 정보(커밋·PR)")
public class DevController {
    private final CodeRepositoryService repositories;
    private final DevCommitService commits;
    private final DevSettingsService settings;

    @Operation(summary = "프로젝트에 등록된 저장소 목록", description = "프로젝트 VIEW")
    @GetMapping("/api/alm/projects/{projectId}/settings/repositories")
    public List<RepositoryResponse> repositories(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return repositories.list(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트 저장소 목록을 통째로 바꾼다", description = "프로젝트 ADMIN. 최대 20개, URL은 정규화해 저장한다")
    @PutMapping("/api/alm/projects/{projectId}/settings/repositories")
    public List<RepositoryResponse> replaceRepositories(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                                        @RequestBody RepositoriesRequest request,
                                                        @AuthenticationPrincipal Jwt jwt) {
        return repositories.replace(userId(jwt), projectId, request);
    }

    @Operation(summary = "프로젝트 개발 설정(브랜치 이름 접두) 조회", description = "프로젝트 VIEW. 저장한 적 없으면 접두 없음·version 0")
    @GetMapping("/api/alm/projects/{projectId}/settings/development")
    public DevSettingsResponse devSettings(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return settings.get(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트 개발 설정(브랜치 이름 접두) 수정",
            description = "프로젝트 ADMIN. 접두는 영숫자·. _ - /, 50자 이하(git 참조 규칙). expectedVersion이 어긋나면 409")
    @PutMapping("/api/alm/projects/{projectId}/settings/development")
    public DevSettingsResponse updateDevSettings(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                                 @RequestBody DevSettingsRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        return settings.update(userId(jwt), projectId, request);
    }

    @Operation(summary = "커밋을 수집해 메시지 안 이슈 키로 이슈에 잇는다",
            description = "한 저장소의 커밋 최대 500건. 멱등(같은 sha 재전송 무해). 연결 권한은 키가 가리키는 이슈 프로젝트의 EDIT — "
                    + "없으면 그 키만 건너뛰고 결과에 이유를 싣는다. org 불능이면 503(아무것도 남지 않는다)")
    @PostMapping("/api/alm/dev/commits")
    public IngestResponse ingest(@RequestBody IngestRequest request, @AuthenticationPrincipal Jwt jwt) {
        return commits.ingest(userId(jwt), request);
    }

    @Operation(summary = "이슈 개발 정보 — 커밋(최신 순)·PR·요약", description = "프로젝트 VIEW")
    @GetMapping("/api/alm/issues/{issueId}/development")
    public DevelopmentResponse development(@Parameter(description = "이슈 ID") @PathVariable long issueId,
                                           @AuthenticationPrincipal Jwt jwt) {
        return commits.development(userId(jwt), issueId);
    }

    @Operation(summary = "프로젝트 최근 커밋 — 등록 저장소 커밋 ∪ 프로젝트 이슈에 이어진 커밋, 최신 순 커서",
            description = "프로젝트 VIEW. limit 기본 50·최대 100. 다음 쪽은 응답의 nextCursor를 cursor로")
    @GetMapping("/api/alm/projects/{projectId}/commits")
    public CommitPage projectCommits(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                     @Parameter(description = "이전 응답의 nextCursor") @RequestParam(required = false) String cursor,
                                     @Parameter(description = "쪽 크기(1~100)") @RequestParam(required = false) Integer limit,
                                     @AuthenticationPrincipal Jwt jwt) {
        return commits.projectCommits(userId(jwt), projectId, cursor, limit);
    }
}
