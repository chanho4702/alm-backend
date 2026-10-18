package com.platform.almbackend.issue;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.repository.IssueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 랭크 그룹(프로젝트+스프린트, 상태 무관) 순서열 다루기 — 수정(상태·스프린트가 바뀌면 컬럼 맨 뒤)과
 * 이동·랭크가 같은 계산을 쓴다. 그룹은 언제나 1..n으로 조밀하게 유지한다.
 */
@Component
@RequiredArgsConstructor
class IssueRankGroups {
    private final IssueRepository issues;

    /** 그 그룹에서 이 이슈를 뺀 나머지(현재 순서) — 삽입 위치를 계산할 작업 목록 */
    List<Issue> without(Issue issue, Long sprintId) {
        List<Issue> group = new ArrayList<>(issues.findRankGroup(issue.getProjectId(), sprintId));
        group.removeIf(entry -> entry.getId().equals(issue.getId()));
        return group;
    }

    /**
     * beforeId는 대상 컬럼 안의 이슈여야 한다. 다른 컬럼의 이슈거나 이미 사라졌으면 -1이다 —
     * 드래그 도중 다른 사용자가 그 이슈를 옮겼을 수 있고, 화면은 이동 후 항상 재조회한다.
     */
    static int indexOfInColumn(List<Issue> group, Long beforeId, String status) {
        if (beforeId == null) return -1;
        for (int i = 0; i < group.size(); i++) {
            Issue entry = group.get(i);
            if (entry.getId().equals(beforeId) && Objects.equals(entry.getStatus(), status)) return i;
        }
        return -1;
    }

    static int indexOf(List<Issue> group, Long beforeId) {
        if (beforeId == null) return -1;
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i).getId().equals(beforeId)) return i;
        }
        return -1;
    }

    /** 대상 컬럼의 마지막 다음 자리. 컬럼이 비었으면 그룹 맨 뒤다. */
    static int afterLastOfStatus(List<Issue> group, String status) {
        int last = -1;
        for (int i = 0; i < group.size(); i++) {
            if (Objects.equals(group.get(i).getStatus(), status)) last = i;
        }
        return last < 0 ? group.size() : last + 1;
    }

    static void resequence(List<Issue> group) {
        for (int i = 0; i < group.size(); i++) {
            group.get(i).resequence(i + 1L);
        }
    }
}
