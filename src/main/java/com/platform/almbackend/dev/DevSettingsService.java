package com.platform.almbackend.dev;

import com.platform.almbackend.dev.DevDtos.DevSettingsRequest;
import com.platform.almbackend.dev.DevDtos.DevSettingsResponse;
import com.platform.almbackend.domain.ProjectDevSettings;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.ProjectDevSettingsRepository;
import com.platform.common.error.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 프로젝트 개발 설정(V43) — git 브랜치 이름 접두. 읽기 VIEW(브랜치 이름 복사는 누구나 한다), 쓰기 ADMIN.
 * 행이 없으면 접두 없음·version 0.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DevSettingsService {
    public static final int MAX_PREFIX = 50;
    /** git 참조 이름에 쓸 수 있는 글자 중 접두에 허용하는 것 — 공백·제어문자·~^:?*[\ 등은 처음부터 받지 않는다 */
    private static final Pattern PREFIX_CHARS = Pattern.compile("[A-Za-z0-9._/-]+");

    private final ProjectDevSettingsRepository repository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public DevSettingsResponse get(long userId, long projectId) {
        projectService.require(userId, projectId, AlmAction.VIEW);
        return repository.findById(projectId).map(DevSettingsService::toResponse)
                .orElse(new DevSettingsResponse(projectId, "", 0));
    }

    public DevSettingsResponse update(long userId, long projectId, DevSettingsRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion이 필요합니다");
        }
        ProjectDevSettings row = repository.findById(projectId).orElse(null);
        int current = row == null ? 0 : row.getVersion();
        if (current != request.expectedVersion()) {
            throw new ConflictException("다른 사용자가 먼저 개발 설정을 수정했습니다. 현재 " + current
                    + ", 요청 " + request.expectedVersion());
        }
        String prefix = normalizePrefix(request.branchPrefix());
        if (row == null) row = ProjectDevSettings.of(projectId);
        row.apply(prefix, Instant.now().truncatedTo(ChronoUnit.MICROS));
        try {
            repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // 행이 없던 두 요청이 동시에 처음 저장했다
            throw new ConflictException("다른 사용자가 먼저 개발 설정을 수정했습니다. 새로 고친 뒤 다시 저장하세요");
        }
        return toResponse(row);
    }

    /**
     * 앞뒤 공백과 끝 {@code /}는 지운다(프론트 {@code branchName}도 끝 /를 지운다). 빈 값 = 접두 없음.
     * 나머지는 git 참조 규칙에 걸리면 거절한다 — 조용히 고치면 복사한 브랜치가 설정과 달라진다.
     */
    static String normalizePrefix(String raw) {
        String prefix = raw == null ? "" : raw.trim().replaceAll("/+$", "");
        if (prefix.isEmpty()) return "";
        if (prefix.length() > MAX_PREFIX) {
            throw new IllegalArgumentException("브랜치 접두는 " + MAX_PREFIX + "자 이하여야 합니다");
        }
        if (!PREFIX_CHARS.matcher(prefix).matches()) {
            throw new IllegalArgumentException("브랜치 접두에는 영문·숫자와 . _ - / 만 쓸 수 있습니다");
        }
        if (prefix.startsWith("/") || prefix.contains("//")) {
            throw new IllegalArgumentException("브랜치 접두는 /로 시작하거나 //를 담을 수 없습니다");
        }
        if (prefix.contains("..")) {
            throw new IllegalArgumentException("브랜치 접두에는 ..를 쓸 수 없습니다");
        }
        for (String part : prefix.split("/")) {
            if (part.startsWith(".") || part.startsWith("-") || part.endsWith(".") || part.endsWith(".lock")) {
                throw new IllegalArgumentException("브랜치 접두의 각 부분은 . 또는 -로 시작하거나 . 또는 .lock으로 끝날 수 없습니다");
            }
        }
        return prefix;
    }

    private static DevSettingsResponse toResponse(ProjectDevSettings row) {
        return new DevSettingsResponse(row.getProjectId(), row.getBranchPrefix(), row.getVersion());
    }
}
