package com.platform.almbackend.dev.scm;

import com.platform.almbackend.dev.DevDtos.CommitInput;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Git 호스팅 하나(GitHub·GitLab)의 읽기 — 폴링 러너가 제공자를 몰라도 되게 같은 모양으로 돌려준다 */
public interface ScmProvider {

    /** 이 저장소의 대상·인증 — 한 실행 동안 쓴다 */
    record Target(String repositoryUrl, String apiBase, String token, ScmHttp.Call call) {}

    /**
     * @param notModified 304(ETag 그대로) — commits는 비고 커서·ETag는 그대로 둔다
     * @param truncated   쪽 상한에 걸려 더 오래된 것을 다 읽지 못했다
     * @param maxCommittedAt 읽은 커밋 중 가장 늦은 커밋 시각(다음 since 상한)
     */
    record CommitBatch(List<CommitInput> commits, String etag, boolean notModified, boolean truncated, Instant maxCommittedAt) {}

    /** PR/MR 한 건 — state는 OPEN·MERGED·CLOSED. number는 GitHub PR 번호·GitLab MR iid(키셋의 동점 깨기) */
    record PullItem(String url, long number, String title, String sourceBranch, String state, Instant updatedAt, String apiRef) {}

    /**
     * PR/MR 키셋 커서 — 마지막으로 처리한 (updated_at, number). 그보다 큰 것만 처리한다(오름차순).
     *
     * @param page GitHub 이어 읽기 쪽 번호 힌트(null = 1쪽부터). GitLab은 updated_after로 거르니 쓰지 않는다
     */
    record PullCursor(Instant updatedAt, long number, Integer page) {
        boolean before(Instant updated, long n) {
            int c = updated.compareTo(updatedAt);
            return c > 0 || (c == 0 && n > number);
        }
    }

    /**
     * @param pulls      처리할 것(커서 뒤, 오름차순, 상한까지)
     * @param probeEtag  "가장 최근 갱신 1건" 탐침의 ETag — 다 따라잡았을 때 저장해 다음 실행이 304로 건너뛴다
     * @param notModified 탐침이 304 — 그 뒤 갱신된 PR이 없다(pulls는 비고 커서는 그대로)
     * @param caughtUp   목록 끝까지 읽었다(상한에 걸리지 않았다)
     * @param next       다음 커서(처리한 마지막 항목, 없으면 받은 커서 그대로)와 쪽 힌트
     */
    record PullBatch(List<PullItem> pulls, String probeEtag, boolean notModified, boolean caughtUp, PullCursor next) {}

    Map<String, String> authHeaders(String token);

    CommitBatch commits(Target target, String branch, Instant since, String etag);

    /**
     * 커서 뒤의 PR/MR을 updated_at 오름차순으로 — 오래된 것부터 차례로 따라잡는다. 한 번에 max-pull-requests건·max-pages쪽까지,
     * 넘으면 caughtUp=false이고 다음 실행이 next 커서에서 잇는다. {@code probeEtag}가 있으면(직전에 다 따라잡았다) 먼저
     * "가장 최근 갱신 1건"을 If-None-Match로 물어 304면 아무것도 읽지 않는다.
     */
    PullBatch pulls(Target target, PullCursor cursor, String probeEtag);

    /** 열린 PR/MR의 커밋(호스팅 상한까지) */
    List<CommitInput> pullCommits(Target target, PullItem pull);
}
