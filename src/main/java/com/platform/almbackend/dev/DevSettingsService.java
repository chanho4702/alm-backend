package com.platform.almbackend.dev;

import com.platform.almbackend.dev.DevDtos.DevSettingsRequest;
import com.platform.almbackend.dev.DevDtos.DevSettingsResponse;
import com.platform.almbackend.domain.ProjectDevSettings;
import com.platform.almbackend.permission.AlmAction;
import com.platform.almbackend.project.ProjectService;
import com.platform.almbackend.repository.ProjectDevSettingsRepository;
import com.platform.common.error.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
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

    /**
     * 낙관적 락은 <b>조건부 쓰기 한 문장</b>이 판정한다 — 읽고 비교한 뒤 저장하면 같은 expectedVersion의 동시 PUT 둘이
     * 모두 통과해 뒤엣것이 앞엣것을 덮는다(lost update). 있는 행은 {@code UPDATE … WHERE version = ?},
     * 없는 행은 {@code INSERT … ON CONFLICT DO NOTHING}이고, 어느 쪽이든 0행이면 경합에서 진 것이라 409다.
     */
    public DevSettingsResponse update(long userId, long projectId, DevSettingsRequest request) {
        projectService.require(userId, projectId, AlmAction.ADMIN);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion이 필요합니다");
        }
        int expected = request.expectedVersion();
        String prefix = normalizePrefix(request.branchPrefix());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int written;
        try {
            written = expected == 0
                    ? repository.insertIfAbsent(projectId, prefix, now)
                    : repository.updateIfVersion(projectId, expected, prefix, now);
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // DB가 동시 쓰기를 직렬화하다 거절했다(유니크 충돌·동시 갱신) — 같은 경합 패배다.
            // 이 트랜잭션은 이미 실패 상태라(PostgreSQL) 현재 version을 다시 읽지 않는다
            throw new ConflictException("다른 사용자가 먼저 개발 설정을 수정했습니다. 새로 고친 뒤 다시 저장하세요");
        }
        if (written == 0) throw conflict(projectId, expected);
        return repository.findById(projectId).map(DevSettingsService::toResponse)
                .orElseThrow(() -> conflict(projectId, expected));
    }

    private ConflictException conflict(long projectId, int expected) {
        int current = repository.findById(projectId).map(ProjectDevSettings::getVersion).orElse(0);
        return new ConflictException("다른 사용자가 먼저 개발 설정을 수정했습니다. 현재 " + current + ", 요청 " + expected);
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
