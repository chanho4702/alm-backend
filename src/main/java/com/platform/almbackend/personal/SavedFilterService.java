package com.platform.almbackend.personal;

import com.platform.almbackend.domain.SavedFilter;
import com.platform.almbackend.repository.SavedFilterRepository;
import com.platform.almbackend.search.aql.AqlSyntax;
import com.platform.common.error.ConflictException;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Collator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 내 저장 필터 — 사이드바에 꽂아 두는 검색. 프로젝트 권한을 묻지 않는다(질의를 <b>실행</b>할 때
 * 검색이 볼 수 있는 범위로 좁힌다), 대신 소유자 밖으로는 한 줄도 새지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SavedFilterService {

    private static final int MAX_NAME = 60;
    private static final int MAX_QUERY = 4000;
    /** 이름이 이미 있을 때 프론트가 그대로 보여 주는 문장 — 바꾸면 프론트 문구도 함께 바꾼다 */
    private static final String DUPLICATE_NAME = "같은 이름의 필터가 있습니다";

    private final SavedFilterRepository filters;

    public record FilterResponse(long id, String name, String kind, String query,
                                 Instant createdAt, Instant updatedAt) {
        static FilterResponse from(SavedFilter filter) {
            return new FilterResponse(filter.getId(), filter.getName(), filter.getKind(), filter.getQuery(),
                    filter.getCreatedAt(), filter.getUpdatedAt());
        }
    }

    /** 만들 때는 셋 다 필요하다 */
    public record FilterCreateRequest(String name, String kind, String query) {}

    /** 고칠 때는 보낸 것만 바뀐다 — null은 "그대로 두라"는 뜻이다 */
    public record FilterUpdateRequest(String name, String kind, String query) {}

    /**
     * 이름 순. 정렬은 DB가 아니라 여기서 한다 — {@code ORDER BY name}은 콜레이션을 타서 한글·영문이
     * 섞이면 H2와 Postgres가, 같은 Postgres라도 로케일이 다르면 순서가 갈린다. 사이드바 순서가
     * 환경 따라 달라지면 안 되므로 한국어 {@link Collator}로 못 박는다.
     *
     * <p>강도는 PRIMARY라 대소문자를 가리지 않는다({@code apple}과 {@code Apple}이 동률). 동률은
     * id 오름차순으로 끊어 페이지마다 순서가 흔들리지 않게 한다.
     */
    @Transactional(readOnly = true)
    public List<FilterResponse> list(long userId) {
        return filters.findByOwnerId(userId).stream()
                .map(FilterResponse::from)
                .sorted(byName())
                .toList();
    }

    /** {@code Collator}는 스레드 안전이 아니다 — 공유 상수로 두지 않고 호출마다 만든다 */
    private static Comparator<FilterResponse> byName() {
        Collator collator = Collator.getInstance(Locale.KOREAN);
        collator.setStrength(Collator.PRIMARY);
        return Comparator.comparing(FilterResponse::name, collator).thenComparingLong(FilterResponse::id);
    }

    public FilterResponse create(long userId, FilterCreateRequest request) {
        String name = requireName(request.name());
        String kind = requireKind(request.kind());
        String query = requireQuery(request.query(), kind);
        if (filters.existsByOwnerIdAndName(userId, name)) throw new ConflictException(DUPLICATE_NAME);
        SavedFilter created = SavedFilter.of(userId, name, kind, query, now());
        return FilterResponse.from(save(created));
    }

    public FilterResponse update(long userId, long filterId, FilterUpdateRequest request) {
        SavedFilter filter = require(userId, filterId);
        String name = request.name() == null ? null : requireName(request.name());
        String kind = request.kind() == null ? null : requireKind(request.kind());
        // kind만 aql로 바꾸면 이미 저장돼 있던 문자열이 검사 대상이다 — 저장된 값과 새 값 중 최종값을 본다
        String query = request.query() == null ? null : requireQuery(request.query(), kind == null ? filter.getKind() : kind);
        if (query == null && kind != null) requireQuery(filter.getQuery(), kind);
        if (name != null && !name.equals(filter.getName()) && filters.existsByOwnerIdAndName(userId, name)) {
            throw new ConflictException(DUPLICATE_NAME);
        }
        filter.edit(name, kind, query, now());
        flush();
        return FilterResponse.from(filter);
    }

    public void delete(long userId, long filterId) {
        filters.delete(require(userId, filterId));
    }

    /** 남의 것은 없는 것과 같다 — 403으로 존재를 알리지 않는다 */
    private SavedFilter require(long userId, long filterId) {
        return filters.findByIdAndOwnerId(filterId, userId)
                .orElseThrow(() -> new NotFoundException("저장 필터를 찾을 수 없습니다: " + filterId));
    }

    /** 이름 유니크는 DB에도 있다 — 동시에 두 개가 들어오면 여기서 같은 문장으로 되돌린다 */
    private SavedFilter save(SavedFilter filter) {
        try {
            return filters.saveAndFlush(filter);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(DUPLICATE_NAME);
        }
    }

    private void flush() {
        try {
            filters.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(DUPLICATE_NAME);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("필터 이름을 입력하세요");
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("필터 이름은 " + MAX_NAME + "자 이하여야 합니다");
        }
        return trimmed;
    }

    private static String requireKind(String kind) {
        String trimmed = kind == null ? "" : kind.trim();
        if (!SavedFilter.KIND_SMART.equals(trimmed) && !SavedFilter.KIND_AQL.equals(trimmed)) {
            throw new IllegalArgumentException("필터 종류는 smart 또는 aql입니다");
        }
        return trimmed;
    }

    /**
     * AQL은 저장할 때 문법을 본다 — 못 여는 필터를 사이드바에 꽂아 두고 누를 때마다 400을 보느니
     * 저장을 거절하는 편이 낫다. 값 해석(그런 상태가 있는가)은 실행할 때 한다.
     */
    private static String requireQuery(String query, String kind) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("필터 질의를 입력하세요");
        if (trimmed.length() > MAX_QUERY) {
            throw new IllegalArgumentException("필터 질의는 " + MAX_QUERY + "자 이하여야 합니다");
        }
        if (SavedFilter.KIND_AQL.equals(kind)) AqlSyntax.requireValid(trimmed);
        return trimmed;
    }
}
