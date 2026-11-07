package com.platform.almbackend.dev.scm;

import com.platform.almbackend.dev.scm.ScmSyncDtos.ScmSyncListResponse;
import com.platform.almbackend.dev.scm.ScmSyncDtos.ScmSyncRequest;
import com.platform.almbackend.dev.scm.ScmSyncDtos.ScmSyncView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.almbackend.issue.IssueController.userId;

/** 저장소 폴링 동기화(V45) 관리 — 프로젝트 ADMIN(PAT는 admin 스코프 — 게이트웨이 /projects/*\/settings/** 쓰기 규칙) */
@RestController
@RequiredArgsConstructor
@Tag(name = "Development sync", description = "저장소 폴링 동기화 — 폐쇄망에서도 GitHub·GitLab API를 읽어 커밋·PR/MR을 이슈에 잇는다")
public class ScmSyncController {
    private final ScmSyncService service;

    @Operation(summary = "동기화 설정·상태", description = "프로젝트 ADMIN. 설치 스위치(ALM_SCM_SYNC)와 저장소별 폴링 상태. 토큰은 싣지 않는다")
    @GetMapping("/api/alm/projects/{projectId}/settings/scm-sync")
    public ScmSyncListResponse list(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), projectId);
    }

    @Operation(summary = "저장소 폴링 등록·수정", description = "프로젝트 ADMIN. 등록된 저장소만, 처음엔 token 필수. ALM_SCM_SYNC=webhook이면 400")
    @PutMapping("/api/alm/projects/{projectId}/settings/scm-sync")
    public ScmSyncView upsert(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                              @RequestBody ScmSyncRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.upsert(userId(jwt), projectId, request);
    }

    @Operation(summary = "저장소 폴링 삭제", description = "프로젝트 ADMIN. 토큰·커서를 지운다(수집한 커밋·연결은 남는다)")
    @DeleteMapping("/api/alm/projects/{projectId}/settings/scm-sync/{syncId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                       @Parameter(description = "동기화 ID") @PathVariable long syncId, @AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt), projectId, syncId);
    }

    @Operation(summary = "지금 동기화", description = "프로젝트 ADMIN. 요청 안에서 한 번 돌리고 결과 상태를 돌려준다. 다른 실행 중·레이트 리밋 대기면 409")
    @PostMapping("/api/alm/projects/{projectId}/settings/scm-sync/{syncId}/run")
    public ScmSyncView runNow(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                              @Parameter(description = "동기화 ID") @PathVariable long syncId, @AuthenticationPrincipal Jwt jwt) {
        return service.runNow(userId(jwt), projectId, syncId);
    }
}
