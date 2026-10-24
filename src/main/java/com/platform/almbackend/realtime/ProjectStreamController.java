package com.platform.almbackend.realtime;

import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * {@code GET /api/alm/projects/{projectId}/stream} — 프로젝트 실시간 무효화 신호(SSE). VIEW 권한(없으면 403, 휴지통 404,
 * org 불능 503 — 스트림을 열기 전에 판정한다). EventSource는 Authorization 헤더를 실을 수 없어 클라이언트는 fetch 스트리밍으로
 * 붙는다 — 그래서 재연결 위치는 {@code Last-Event-ID} 헤더 또는 {@code lastEventId} 쿼리 둘 다 받는다.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Realtime", description = "프로젝트 실시간 무효화 신호(SSE)")
public class ProjectStreamController {
    private final ProjectStreamHub hub;
    private final ProjectService projectService;

    @Operation(summary = "프로젝트 실시간 스트림에 붙는다(SSE) — 이슈·스프린트 변경을 무효화 신호(id·version·종류)로 받는다",
            description = "이벤트: ready · issue · sprint · reset(gap·overflow — 화면 전체 재조회) · revoked(권한 회수 — 닫힘). "
                    + "heartbeat는 주석 줄. 재연결은 Last-Event-ID(또는 lastEventId 쿼리). 연결 상한 초과는 429.")
    @GetMapping("/api/alm/projects/{projectId}/stream")
    public SseEmitter stream(
            @Parameter(description = "프로젝트 ID") @PathVariable long projectId,
            @Parameter(description = "마지막으로 받은 신호 id(재연결)") @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            @Parameter(description = "Last-Event-ID 헤더 대신(fetch 클라이언트 편의)") @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletResponse response) {
        long userId = userId(jwt);
        projectService.require(userId, projectId, AlmAction.VIEW);
        String lastEventId = lastEventIdHeader != null && !lastEventIdHeader.isBlank() ? lastEventIdHeader : lastEventIdParam;
        SseEmitter emitter = hub.subscribe(userId, projectId, lastEventId);
        // nginx가 응답을 모아 두지 않게 — nginx location의 proxy_buffering off와 이중으로
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return emitter;
    }

    /**
     * 스트림 요청은 {@code Accept: text/event-stream}이라 공통 핸들러의 JSON 본문이 내용 협상에 걸려(406 → 500) 오류 계약이 깨진다.
     * 이 컨트롤러(와 그 앞 계정 상태 게이트)의 오류만 Content-Type을 JSON으로 못박아 같은 {@code {"error"}} 계약으로 낸다.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({
            com.platform.common.error.NotFoundException.class,
            com.platform.common.error.ForbiddenException.class,
            com.platform.common.error.ServiceUnavailableException.class,
            IllegalArgumentException.class})
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> streamError(RuntimeException e) {
        org.springframework.http.HttpStatus status = switch (e) {
            case com.platform.common.error.NotFoundException x -> org.springframework.http.HttpStatus.NOT_FOUND;
            case com.platform.common.error.ForbiddenException x -> org.springframework.http.HttpStatus.FORBIDDEN;
            case com.platform.common.error.ServiceUnavailableException x -> org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
            default -> org.springframework.http.HttpStatus.BAD_REQUEST;
        };
        return org.springframework.http.ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("error", e.getMessage() == null ? status.getReasonPhrase() : e.getMessage()));
    }
}
