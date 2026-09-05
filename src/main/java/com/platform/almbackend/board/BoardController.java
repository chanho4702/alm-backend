package com.platform.almbackend.board;

import com.platform.almbackend.board.BoardService.BoardRequest;
import com.platform.almbackend.board.BoardService.BoardResponse;
import com.platform.almbackend.issue.dto.IssueResponse;
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

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;

@RestController
@RequiredArgsConstructor
@Tag(name = "Boards", description = "보드 정의와 보드에 걸리는 이슈 목록")
public class BoardController {
    private final BoardService service;

    @Operation(summary = "프로젝트의 보드 목록을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/boards")
    public List<BoardResponse> list(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트에 보드를 만든다")
    @PostMapping("/api/alm/projects/{projectId}/boards")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse create(@Parameter(description = "프로젝트 ID") @PathVariable long projectId, @RequestBody BoardRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(userId(jwt), projectId, request);
    }

    @Operation(summary = "보드 하나를 조회한다")
    @GetMapping("/api/alm/boards/{boardId}")
    public BoardResponse get(@Parameter(description = "보드 ID") @PathVariable long boardId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(userId(jwt), boardId);
    }

    @Operation(summary = "보드의 이름·필터·컬럼 구성을 수정한다")
    @PutMapping("/api/alm/boards/{boardId}")
    public BoardResponse update(@Parameter(description = "보드 ID") @PathVariable long boardId, @RequestBody BoardRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.update(userId(jwt), boardId, request);
    }

    @Operation(summary = "보드를 삭제한다")
    @DeleteMapping("/api/alm/boards/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "보드 ID") @PathVariable long boardId, @AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt), boardId);
    }

    @Operation(summary = "보드 필터에 걸리는 이슈를 조회한다")
    @GetMapping("/api/alm/boards/{boardId}/issues")
    public List<IssueResponse> issues(@Parameter(description = "보드 ID") @PathVariable long boardId, @AuthenticationPrincipal Jwt jwt) {
        return service.issues(userId(jwt), boardId);
    }
}
