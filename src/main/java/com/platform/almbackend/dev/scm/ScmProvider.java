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

    /** PR/MR 한 건 — state는 OPEN·MERGED·CLOSED */
    record PullItem(String url, String number, String title, String sourceBranch, String state, Instant updatedAt, String apiRef) {}

    /**
     * @param truncated     상한(max-pull-requests·쪽)에 걸려 since까지 다 읽지 못했다 — 러너가 창을 이어 읽는다(커서·ETag를 움직이지 않는다)
     * @param oldestUpdatedAt 이번에 처리한 것 중 가장 오래된 updated_at(다음 실행은 이보다 새 것을 건너뛴다)
     */
    record PullBatch(List<PullItem> pulls, String etag, boolean notModified, Instant maxUpdatedAt, boolean truncated,
                     Instant oldestUpdatedAt) {}

    Map<String, String> authHeaders(String token);

    CommitBatch commits(Target target, String branch, Instant since, String etag);

    /**
     * since 이후 갱신된 PR/MR — 최신 갱신 순. {@code skipNewerThan}이 있으면(창 이어 읽기) 그보다 새 항목은 이미 처리했으니 건너뛴다.
     */
    PullBatch pulls(Target target, Instant since, Instant skipNewerThan, String etag);

    /** 열린 PR/MR의 커밋(호스팅 상한까지) */
    List<CommitInput> pullCommits(Target target, PullItem pull);
}
