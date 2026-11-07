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

    /** PR/MR 한 건 — state는 OPEN·MERGED·CLOSED. number는 GitHub PR 번호·GitLab MR iid */
    record PullItem(String url, long number, String title, String sourceBranch, String state, Instant updatedAt, String apiRef) {}

    /**
     * PR/MR 커서 — <b>시각만</b> 둔다. API가 같은 updated_at 안의 순서를 보장하지 않으니 번호로 동점을 깨지 않는다:
     * 커서와 같은 시각의 항목은 매번 다시 처리한다(수집·PR 링크가 멱등이라 안전).
     *
     * @param page GitHub 이어 읽기 쪽 번호 힌트(null = 1쪽부터). GitLab은 updated_after로 거르니 쓰지 않는다
     */
    record PullCursor(Instant updatedAt, Integer page) {}

    /**
     * @param pulls       처리할 것(커서 이상, 오름차순)
     * @param probeEtag   "가장 최근 갱신 1건" 탐침의 ETag — 다 따라잡았을 때 저장해 다음 실행이 304로 건너뛴다
     * @param notModified 탐침이 304 — 그 뒤 갱신된 PR이 없다(pulls는 비고 커서는 그대로)
     * @param caughtUp    목록 끝까지 읽었다
     * @param next        다음 커서 — 끝까지 처리한 마지막 시각 묶음의 시각(+ 쪽 힌트)
     * @param forced      같은 시각 묶음이 한 실행의 예산보다 커서 그 시각을 건너뛰며 강제로 전진했다(일부 누락 가능)
     */
    record PullBatch(List<PullItem> pulls, String probeEtag, boolean notModified, boolean caughtUp, PullCursor next, boolean forced) {}

    /**
     * 오름차순으로 받은 항목을 시각 묶음 단위로 고른다. 커서보다 오래된 것은 건너뛰고, 커서와 같은 시각(경계)은 다시 처리한다.
     * 새로 처리한 것(커서 뒤)이 soft 상한에 닿으면 <b>다음 시각 묶음이 시작될 때</b> 멈춘다 — 같은 시각 묶음은 상한을 넘어도
     * 끝까지 처리한다(hard 상한까지). 다음 커서는 "끝까지 처리한 마지막 묶음"의 시각이다.
     */
    final class PullScan {
        private final Instant cursor;
        private final int softCap;
        private final int hardCap;
        private final List<PullItem> out = new java.util.ArrayList<>();
        private Instant group;        // 지금 처리 중인 시각 묶음
        private Instant completed;    // 끝까지 처리한 마지막 묶음(커서보다 뒤)
        private int fresh;            // 커서 뒤 항목 수(경계 재처리는 세지 않는다)
        private boolean boundaryClosed; // 경계 묶음(커서와 같은 시각)을 끝까지 봤다 — 그 뒤 시각의 항목을 받았다
        private boolean stopped;

        public PullScan(Instant cursor, int softCap, int hardCap) {
            this.cursor = cursor;
            this.softCap = softCap;
            this.hardCap = hardCap;
        }

        /** 받으면 true, 여기서 멈추라면 false */
        public boolean offer(PullItem item) {
            Instant t = item.updatedAt();
            if (t == null || t.isBefore(cursor)) return true; // 이미 지나간 것(또는 첫 동기화 범위 밖)
            if (group != null && t.isAfter(group)) {
                if (group.isAfter(cursor)) completed = group; // 이전 묶음을 끝까지 봤다
                else boundaryClosed = true;
                if (fresh >= softCap) {
                    stopped = true;
                    return false;
                }
            }
            if (out.size() >= hardCap) {
                stopped = true;
                return false;
            }
            group = t;
            out.add(item);
            if (t.isAfter(cursor)) fresh++;
            return true;
        }

        public List<PullItem> items() {
            return out;
        }

        public boolean stopped() {
            return stopped;
        }

        /** 끝까지 읽었다면 마지막 묶음도 끝난 것이다 */
        public void reachedEnd() {
            if (group != null && group.isAfter(cursor)) completed = group;
        }

        /** 다음 커서 — 끝낸 묶음이 없으면 그대로 */
        public Instant next() {
            return completed != null ? completed : cursor;
        }

        /**
         * 끝까지 읽지 못한 채 끝났을 때의 다음 커서와 강제 여부.
         * <ul>
         *   <li>끝낸 새 묶음이 있으면 그 시각(일반).</li>
         *   <li>경계 묶음은 끝까지 봤는데(그 뒤 시각 U의 항목을 받았다) 예산이 다해 U가 미완이면 — 막힌 것이 아니다. 경계 시각
         *       바로 뒤(커서 + 1ms)로만 옮긴다: 경계는 방금 끝까지 처리했으니 다음 실행이 그것을 다시 읽느라 예산을 쓰지 않고 U를
         *       처음부터 읽는다(U는 건너뛰지 않는다).</li>
         *   <li>한 묶음 안에서 예산이 다했다(같은 시각이 예산보다 많다) — 진짜 막힘. 그 시각을 지나 강제로 전진한다(나머지는
         *       건너뛴다, 경고) — 다른 PR 갱신이 영원히 막히는 것보다 낫다.</li>
         * </ul>
         *
         */
        public Unfinished unfinished() {
            if (completed != null) return new Unfinished(completed, false);
            if (boundaryClosed) return new Unfinished(cursor.plusMillis(1), false);
            if (group != null) return new Unfinished(group.plusMillis(1), true);
            return new Unfinished(cursor, false); // 아무것도 처리하지 않았다(GitHub 첫 동기화가 옛 쪽을 지나는 중 — 쪽 힌트로 전진)
        }

        /** 끝까지 못 읽고 끝난 실행의 다음 커서와 강제 전진 여부 */
        public record Unfinished(Instant next, boolean forced) {}
    }

    Map<String, String> authHeaders(String token);

    CommitBatch commits(Target target, String branch, Instant since, String etag);

    /**
     * 커서 이후 PR/MR을 updated_at 오름차순으로 — 오래된 것부터 차례로 따라잡는다({@link PullScan} 규칙). 다 못 읽으면
     * caughtUp=false이고 다음 실행이 next 커서에서 잇는다. {@code probeEtag}가 있으면(직전에 다 따라잡았다) 먼저
     * "가장 최근 갱신 1건"을 If-None-Match로 물어 304면 아무것도 읽지 않는다.
     */
    PullBatch pulls(Target target, PullCursor cursor, String probeEtag);

    /** 열린 PR/MR의 커밋(호스팅 상한까지) */
    List<CommitInput> pullCommits(Target target, PullItem pull);
}
