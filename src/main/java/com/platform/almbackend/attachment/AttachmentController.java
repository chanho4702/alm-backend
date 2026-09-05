package com.platform.almbackend.attachment;

import com.platform.almbackend.attachment.dto.AttachmentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "이슈 첨부 업로드·다운로드·삭제")
public class AttachmentController {
    private final AttachmentService service;

    @Operation(summary = "이슈에 파일을 첨부한다")
    @PostMapping("/api/alm/issues/{issueId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(
            @Parameter(description = "이슈 ID") @PathVariable long issueId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        return service.upload(userId(jwt), issueId, file);
    }

    @Operation(summary = "이슈의 첨부 목록을 조회한다")
    @GetMapping("/api/alm/issues/{issueId}/attachments")
    public List<AttachmentResponse> list(@Parameter(description = "이슈 ID") @PathVariable long issueId, @AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), issueId);
    }

    /** Content-Disposition attachment 고정 — 브라우저 인라인 실행(XSS) 차단 */
    @Operation(summary = "첨부 파일을 내려받는다")
    @GetMapping("/api/alm/attachments/{id}")
    public ResponseEntity<Resource> download(@Parameter(description = "첨부 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        AttachmentService.DownloadItem item = service.download(userId(jwt), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encode(item.meta().getFilename()))
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(item.meta().getContentType()))
                .contentLength(item.meta().getSizeBytes())
                .body(item.resource());
    }

    /** 안전한 래스터 이미지만 인라인으로 — 썸네일용 */
    @Operation(summary = "이미지 첨부를 인라인으로 조회한다")
    @GetMapping("/api/alm/attachments/{id}/inline")
    public ResponseEntity<Resource> inline(@Parameter(description = "첨부 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        AttachmentService.DownloadItem item = service.inline(userId(jwt), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encode(item.meta().getFilename()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60, no-transform")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentType(MediaType.parseMediaType(item.meta().getContentType()))
                .contentLength(item.meta().getSizeBytes())
                .body(item.resource());
    }

    @Operation(summary = "첨부를 삭제한다")
    @DeleteMapping("/api/alm/attachments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "첨부 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt), id);
    }

    private static String encode(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
