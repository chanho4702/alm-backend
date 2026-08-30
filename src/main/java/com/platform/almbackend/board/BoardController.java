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

@RestController
@RequiredArgsConstructor
public class BoardController {
    private final BoardService service;

    @GetMapping("/api/alm/projects/{projectId}/boards")
    public List<BoardResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), projectId);
    }

    @PostMapping("/api/alm/projects/{projectId}/boards")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse create(@PathVariable long projectId, @RequestBody BoardRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(userId(jwt), projectId, request);
    }

    @GetMapping("/api/alm/boards/{boardId}")
    public BoardResponse get(@PathVariable long boardId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(userId(jwt), boardId);
    }

    @PutMapping("/api/alm/boards/{boardId}")
    public BoardResponse update(@PathVariable long boardId, @RequestBody BoardRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.update(userId(jwt), boardId, request);
    }

    @DeleteMapping("/api/alm/boards/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long boardId, @AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt), boardId);
    }

    @GetMapping("/api/alm/boards/{boardId}/issues")
    public List<IssueResponse> issues(@PathVariable long boardId, @AuthenticationPrincipal Jwt jwt) {
        return service.issues(userId(jwt), boardId);
    }
}
