package com.platform.almbackend.issue;

import com.platform.almbackend.issue.dto.IssueResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.platform.almbackend.issue.IssueController.userId;

/** 플래그(막힘, D2) — 편집 폼과 따로 바로 켜고 끈다 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Issues", description = "이슈 생성·수정·이동·순서 변경·가져오기")
public class IssueFlagController {
    private final IssueFlagService flags;

    @Schema(description = "플래그 켜기·끄기")
    public record FlagRequest(
            @Schema(description = "true면 막힘으로 표시, false면 해제", example = "true")
            @NotNull(message = "flagged가 필요합니다") Boolean flagged,
            @Schema(description = "이유(선택) — 주면 \"플래그 추가: …\" 코멘트로 남는다", example = "API 키 발급 대기")
            @Size(max = 5000, message = "이유는 5000자 이하여야 합니다") String comment) {}

    @Operation(summary = "이슈 플래그(막힘)를 켜거나 끈다",
            description = "편집 폼의 version을 올리지 않는다(보드 이동과 같은 규칙) — expectedVersion이 없다. 같은 값이면 아무것도 하지 않는다.")
    @PutMapping("/api/alm/issues/{issueId}/flag")
    public IssueResponse flag(@Parameter(description = "이슈 ID") @PathVariable long issueId,
                              @Valid @RequestBody FlagRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return flags.flag(userId(jwt), issueId, request.flagged(), request.comment());
    }
}
