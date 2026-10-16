package com.platform.almbackend.issue;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.issue.dto.IssueResponse;
import com.platform.almbackend.repository.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 이슈 여러 건 → 응답 DTO. 라벨·컴포넌트는 지연 {@code @ElementCollection}이라 이슈마다 꺼내면 페이지 50건에
 * 질의가 100번 더 나간다(2026-10-16 실측: 102 SQL). 여기서는 페이지의 id로 라벨 한 번·컴포넌트 한 번만 읽는다.
 *
 * <p>컬렉션을 fetch join으로 같이 읽지 않는 이유: 컬렉션 둘을 함께 조인하면 행이 곱으로 불어나고, DB 페이징과
 * 섞으면 Hibernate가 전량을 메모리에서 자른다(HHH90003004). 페이지는 페이지대로 자르고 컬렉션만 따로 읽는다.
 * 입력 순서(=정렬 순서)를 그대로 지킨다.
 */
@Component
@RequiredArgsConstructor
public class IssueResponses {
    /** IN 목록 상한 — 프로젝트 전체 목록(보드)처럼 큰 입력도 질의 수가 건수/500으로 묶인다 */
    static final int CHUNK = 500;

    private final IssueRepository issues;

    public List<IssueResponse> of(List<Issue> page) {
        return map(page, Issue::getId, (issue, c) -> IssueResponse.of(issue, c.labels(), c.componentIds()));
    }

    /** 이슈와 같은 테이블을 읽는 다른 매핑(AQL 행 등)에도 쓴다 */
    public <T> List<IssueResponse> map(List<T> rows, Function<T, Long> idOf,
                                       BiFunction<T, Collections, IssueResponse> build) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(idOf).toList();
        Map<Long, List<String>> labels = new HashMap<>();
        Map<Long, List<Long>> components = new HashMap<>();
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            for (Object[] row : issues.findLabelRows(chunk)) {
                labels.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>()).add((String) row[1]);
            }
            for (Object[] row : issues.findComponentRows(chunk)) {
                components.computeIfAbsent(((Number) row[0]).longValue(), k -> new ArrayList<>())
                        .add(((Number) row[1]).longValue());
            }
        }
        List<IssueResponse> out = new ArrayList<>(rows.size());
        for (T row : rows) {
            long id = idOf.apply(row);
            out.add(build.apply(row, new Collections(
                    labels.getOrDefault(id, List.of()), components.getOrDefault(id, List.of()))));
        }
        return out;
    }

    public record Collections(List<String> labels, List<Long> componentIds) {}
}
