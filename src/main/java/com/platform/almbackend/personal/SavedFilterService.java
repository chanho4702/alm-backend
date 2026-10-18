package com.platform.almbackend.personal;

import com.platform.almbackend.domain.SavedFilter;
import com.platform.almbackend.repository.SavedFilterRepository;
import com.platform.almbackend.search.aql.AqlSyntax;
import com.platform.common.error.ConflictException;
import com.platform.common.error.ForbiddenException;
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
 * 저장 필터 — 사이드바에 꽂아 두는 검색. 프로젝트 권한을 묻지 않는다(질의를 <b>실행</b>할 때
 * 검색이 볼 수 있는 범위로 좁힌다). 비공개 필터는 소유자 밖으로 한 줄도 새지 않고, 공유 필터(F-12)는 조직 전체가
 * 보되 소유자만 고친다.
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

    /** {@code shared}·{@code ownerId}·{@code mine}은 2026-10-18 추가(F-12, 하위 호환) */
    public record FilterResponse(long id, String name, String kind, String query,
                                 Instant createdAt, Instant updatedAt,
                                 boolean shared, long ownerId, boolean mine) {
        static FilterResponse from(SavedFilter filter, long viewerId) {
            return new FilterResponse(filter.getId(), filter.getName(), filter.getKind(), filter.getQuery(),
                    filter.getCreatedAt(), filter.getUpdatedAt(),
                    filter.isShared(), filter.getOwnerId(), filter.getOwnerId() == viewerId);
        }
    }

    /** 만들 때는 이름·종류·질의가 필요하다. shared는 생략하면 비공개 */
    public record FilterCreateRequest(String name, String kind, String query, Boolean shared) {}

    /** 고칠 때는 보낸 것만 바뀐다 — null은 "그대로 두라"는 뜻이다 */
    public record FilterUpdateRequest(String name, String kind, String query, Boolean shared) {}

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
                .map(f -> FilterResponse.from(f, userId))
                .sorted(byName())
                .toList();
    }

    /**
     * 남이 공유한 필터(F-12) — 조직 전체에 보인다. 질의만 주고 결과는 주지 않는다: 실행은 보는 사람이 검색·AQL로
     * 하므로 그 사람이 볼 수 있는 이슈만 나온다. 내 공유 필터는 {@link #list}에 이미 있어 뺀다.
     */
    @Transactional(readOnly = true)
    public List<FilterResponse> shared(long userId) {
        return filters.findBySharedTrue().stream()
                .filter(f -> f.getOwnerId() != userId)
                .map(f -> FilterResponse.from(f, userId))
                .sorted(byName())
                .toList();
    }

    /** 단건 열기 — 내 것이거나 공유된 것. 비공개인 남의 것은 404 */
    @Transactional(readOnly = true)
    public FilterResponse get(long userId, long filterId) {
        return FilterResponse.from(requireVisible(userId, filterId), userId);
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
        SavedFilter created = SavedFilter.of(userId, name, kind, query, Boolean.TRUE.equals(request.shared()), now());
        return FilterResponse.from(save(created), userId);
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
        filter.edit(name, kind, query, request.shared(), now());
        flush();
        return FilterResponse.from(filter, userId);
    }

    public void delete(long userId, long filterId) {
        filters.delete(require(userId, filterId));
    }

    /**
     * 고치거나 지울 대상 — 소유자만. 공유돼 보이는 남의 필터는 403(보이는 것을 없다고 하지 않는다),
     * 비공개인 남의 것은 404(있다는 사실도 알리지 않는다).
     */
    private SavedFilter require(long userId, long filterId) {
        SavedFilter filter = requireVisible(userId, filterId);
        if (filter.getOwnerId() != userId) throw new ForbiddenException("공유된 필터는 만든 사람만 수정·삭제할 수 있습니다");
        return filter;
    }

    private SavedFilter requireVisible(long userId, long filterId) {
        return filters.findById(filterId)
                .filter(f -> f.getOwnerId() == userId || f.isShared())
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
