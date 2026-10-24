package com.platform.almbackend.view;

import com.platform.almbackend.view.SavedViewService.ViewCreateRequest;
import com.platform.almbackend.view.SavedViewService.ViewResponse;
import com.platform.almbackend.view.SavedViewService.ViewUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/** 저장된 보기(D1, V31) — 필터 + 표시 옵션. 개인 보기와 프로젝트 공유 보기 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Saved Views", description = "저장된 보기(필터 + 표시 옵션) — 개인·프로젝트 공유")
public class SavedViewController {
    private final SavedViewService views;

    @Operation(summary = "내 개인 보기를 조회한다", description = "projectId를 주면 그 프로젝트의 개인 보기만. 공유 보기는 프로젝트 목록에서 본다.")
    @GetMapping("/api/alm/me/views")
    public List<ViewResponse> mine(@Parameter(description = "프로젝트 ID(선택)") @RequestParam(required = false) Long projectId,
                                   @AuthenticationPrincipal Jwt jwt) {
        return views.mine(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트의 보기를 조회한다", description = "프로젝트 공유 보기(먼저) + 그 프로젝트의 내 개인 보기, 각각 이름 순. VIEW가 필요하다.")
    @GetMapping("/api/alm/projects/{projectId}/views")
    public List<ViewResponse> forProject(@Parameter(description = "프로젝트 ID") @PathVariable long projectId,
                                         @AuthenticationPrincipal Jwt jwt) {
        return views.forProject(userId(jwt), projectId);
    }

    @Operation(summary = "보기 하나를 연다", description = "내 것이거나 볼 수 있는 프로젝트의 공유 보기. 그 밖은 404.")
    @GetMapping("/api/alm/views/{id}")
    public ViewResponse get(@Parameter(description = "보기 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return views.get(userId(jwt), id);
    }

    @Operation(summary = "보기를 만든다",
            description = "project 범위는 프로젝트 EDIT, 프로젝트를 지정한 개인 보기는 VIEW가 필요하다. 필터가 aql이면 문법을 검사해 400 `{error, position, expected}`.")
    @PostMapping("/api/alm/views")
    @ResponseStatus(HttpStatus.CREATED)
    public ViewResponse create(@Valid @RequestBody ViewCreateRequest request, @AuthenticationPrincipal Jwt jwt) {
        return views.create(userId(jwt), request);
    }

    @Operation(summary = "보기를 고친다 — expectedVersion이 어긋나면 409",
            description = "보낸 항목만 바뀐다. 공유 보기는 만든 사람(지금 EDIT) 또는 프로젝트 ADMIN만 고친다(그 밖은 403).")
    @PutMapping("/api/alm/views/{id}")
    public ViewResponse update(@Parameter(description = "보기 ID") @PathVariable long id,
                               @Valid @RequestBody ViewUpdateRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return views.update(userId(jwt), id, request);
    }

    @Operation(summary = "보기를 지운다")
    @DeleteMapping("/api/alm/views/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "보기 ID") @PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        views.delete(userId(jwt), id);
    }
}
