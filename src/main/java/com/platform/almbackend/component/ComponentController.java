package com.platform.almbackend.component;

import com.platform.almbackend.component.ComponentService.ComponentRequest;
import com.platform.almbackend.component.ComponentService.ComponentResponse;
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

@RestController
@RequiredArgsConstructor
@Tag(name = "Components", description = "프로젝트 컴포넌트와 기본 담당자")
public class ComponentController {
    private final ComponentService service;

    @Operation(summary = "프로젝트의 컴포넌트 목록을 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/components")
    public List<ComponentResponse> list(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트에 컴포넌트를 만든다")
    @PostMapping("/api/alm/projects/{projectId}/components")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentResponse create(@PathVariable long projectId, @RequestBody ComponentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(userId(jwt), projectId, request);
    }

    @Operation(summary = "컴포넌트를 수정한다")
    @PutMapping("/api/alm/components/{id}")
    public ComponentResponse update(@PathVariable long id, @RequestBody ComponentRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.update(userId(jwt), id, request);
    }

    @Operation(summary = "컴포넌트를 삭제한다")
    @DeleteMapping("/api/alm/components/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(userId(jwt), id);
    }
}
