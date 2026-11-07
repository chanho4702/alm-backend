package com.platform.almbackend.dev;

import com.platform.almbackend.dev.DevDtos.RepositoriesRequest;
import com.platform.almbackend.dev.DevDtos.RepositoryInput;
import com.platform.almbackend.dev.DevDtos.RepositoryResponse;
import com.platform.almbackend.domain.CodeRepository;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.CodeRepositoryRepository;
import com.platform.almbackend.repository.RepositoryScmSyncRepository;
import com.platform.almbackend.repository.RepositoryWebhookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 프로젝트 저장소 등록(V41) — 읽기 VIEW, 쓰기 ADMIN(프로젝트 설정). 목록 통째 교체 */
@Service
@RequiredArgsConstructor
@Transactional
public class CodeRepositoryService {
    public static final int MAX_REPOSITORIES = 20;

    private final ProjectService projectService;
    private final CodeRepositoryRepository repositories;
    private final RepositoryWebhookRepository webhooks;
    private final RepositoryScmSyncRepository scmSyncs;

    @Transactional(readOnly = true)
    public List<RepositoryResponse> list(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return repositories.findByProjectIdOrderByPositionAscIdAsc(projectId).stream().map(CodeRepositoryService::toResponse).toList();
    }

    public List<RepositoryResponse> replace(long userId, long projectId, RepositoriesRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        List<RepositoryInput> inputs = request == null || request.repositories() == null ? List.of() : request.repositories();
        if (inputs.size() > MAX_REPOSITORIES) {
            throw new IllegalArgumentException("저장소는 프로젝트당 " + MAX_REPOSITORIES + "개까지 등록할 수 있습니다");
        }
        Set<String> seen = new HashSet<>();
        List<CodeRepository> fresh = new ArrayList<>();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int position = 0;
        for (RepositoryInput input : inputs) {
            if (input == null) throw new IllegalArgumentException("저장소 항목이 비었습니다");
            String url = RepositoryUrls.normalize(input.url());
            if (!seen.add(url)) throw new IllegalArgumentException("같은 저장소가 두 번 들어 있습니다: " + url);
            String name = input.name() == null || input.name().isBlank() ? RepositoryUrls.defaultName(url) : input.name().trim();
            if (name.length() > 100) throw new IllegalArgumentException("저장소 이름은 100자 이하여야 합니다");
            String branch = input.defaultBranch() == null || input.defaultBranch().isBlank() ? "main" : input.defaultBranch().trim();
            if (branch.length() > 100) throw new IllegalArgumentException("기본 브랜치 이름은 100자 이하여야 합니다");
            fresh.add(CodeRepository.of(projectId, name, url, branch, position++, now));
        }
        repositories.deleteByProjectId(projectId);
        // 등록에서 빠진 저장소의 웹훅(V44)은 함께 지운다 — 남은 저장소의 웹훅은 URL로 묶여 있어 목록 교체(행 id 변경)에도 그대로다
        // 폴링 동기화(V45)도 같은 규칙 — 빠진 저장소의 토큰·커서를 남기지 않는다
        if (seen.isEmpty()) {
            webhooks.deleteByProjectId(projectId);
            scmSyncs.deleteByProjectId(projectId);
        } else {
            webhooks.deleteByProjectIdAndRepositoryUrlNotIn(projectId, seen);
            scmSyncs.deleteByProjectIdAndRepositoryUrlNotIn(projectId, seen);
        }
        return repositories.saveAll(fresh).stream().map(CodeRepositoryService::toResponse).toList();
    }

    private static RepositoryResponse toResponse(CodeRepository r) {
        return new RepositoryResponse(r.getId(), r.getName(), r.getUrl(), r.getDefaultBranch());
    }
}
