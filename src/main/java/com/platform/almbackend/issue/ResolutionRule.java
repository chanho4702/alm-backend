package com.platform.almbackend.issue;

import com.platform.almbackend.domain.IssueResolution;

/**
 * 해결(resolution) 규칙 — 서버가 정본이다(2026-10-24 A1). 지라와 같다: 완료 의미(kind = complete) 상태로
 * <b>들어가면</b> 해결이 기본값("완료")으로 채워지고, 완료에서 <b>나가면</b> 비워진다. 명시한 값은 기본값보다
 * 우선하되, 완료가 아닌 이슈에는 설정할 수 없다. 프론트 목업 {@code applyResolutionRule}(mock/rules.ts)과 같은 규칙이다.
 *
 * <p>상태가 바뀌는 모든 경로(PUT 수정·보드 이동·일괄 편집·전환 실행·스킴 변경 이관)가 이 함수 하나를 거친다.
 * 해결일({@code resolved_at})은 {@code Issue.changeResolution}이 값의 변화에 맞춰 찍고 지운다.
 */
public final class ResolutionRule {
    public static final String NOT_COMPLETE_MESSAGE = "완료된 이슈에만 해결을 설정할 수 있습니다";

    private ResolutionRule() {}

    /**
     * @param wasComplete   이전 상태가 완료 의미였나
     * @param isComplete    새 상태가 완료 의미인가
     * @param current       지금 저장된 해결
     * @param explicitGiven 요청이 해결을 <b>지정했나</b>. 수정 요청은 화면이 들고 있던 값을 그대로 다시 보내므로
     *                      (REST 어댑터가 현재 값을 실어 보낸다) "현재 값과 다를 때만" 지정으로 본다 — 호출자가 판정한다
     * @param explicit      지정한 값(null = 해제 요청)
     * @param defaultValue  완료로 들어갈 때의 기본값(전환 후처리 setResolution). null이면 DONE
     * @return 저장할 해결
     * @throws IllegalArgumentException 완료가 아닌 상태에 해결을 지정했을 때
     */
    public static IssueResolution resolve(boolean wasComplete, boolean isComplete, IssueResolution current,
                                          boolean explicitGiven, IssueResolution explicit,
                                          IssueResolution defaultValue) {
        IssueResolution fallback = defaultValue == null ? IssueResolution.DONE : defaultValue;
        if (!isComplete) {
            if (explicitGiven && explicit != null) throw new IllegalArgumentException(NOT_COMPLETE_MESSAGE);
            return null;
        }
        if (explicitGiven) return explicit == null ? fallback : explicit;
        if (!wasComplete || current == null) return fallback;
        return current;
    }
}
