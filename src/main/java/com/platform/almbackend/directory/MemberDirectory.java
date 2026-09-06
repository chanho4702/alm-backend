package com.platform.almbackend.directory;

import java.util.Collection;
import java.util.Map;

/**
 * 사용자 디렉터리 조회 — org-service가 사람의 원장이다. ALM은 id만 저장하고 이름·이메일·상태는 여기서 읽는다
 * (common-proto 0.16.0 {@code GetMembers}).
 *
 * <p>여러 명이면 한 번에 묻는다 — 워처가 열 명인 이슈의 상태가 바뀌면 왕복도 열 번이 된다.
 * 없는 id는 결과에서 빠지므로 호출측은 개수를 요청과 맞추지 않는다.
 *
 * <p>실패를 어떻게 다룰지는 <b>호출측이 정한다</b>. 알림 메일은 org가 잠깐 불능이라고 막히면 안 되는 부수
 * 채널이라 빈 결과로 받고 스냅샷으로 폴백하면 되지만, 계정 상태 게이트는 "못 읽었다"와 "그런 사람 없다"를
 * 구분해야 한다 — 전자를 후자로 읽으면 정지된 계정이 org 장애 중에 그대로 들어온다. 그래서
 * {@link #lookup}은 결과와 함께 왜 비었는지를 준다.
 */
public interface MemberDirectory {

    /** 조회가 어떻게 끝났는가 — {@code UNAVAILABLE}만 가용성 장애이고, {@code FAILED}는 그 밖의 오류다 */
    enum Outcome { OK, UNAVAILABLE, FAILED }

    record Lookup(Outcome outcome, Map<Long, DirectoryMember> members) {

        public static Lookup ok(Map<Long, DirectoryMember> members) {
            return new Lookup(Outcome.OK, members);
        }

        public boolean ok() {
            return outcome == Outcome.OK;
        }
    }

    /** id → 사람 + 조회 결과. 못 찾았거나 조회가 실패한 id는 {@code members}에 없다. */
    Lookup lookup(Collection<Long> ids);

    /**
     * 이메일(또는 이메일 local-part)로 사람을 찾는다 — {@code LookupMembers}(0.15.0). {@code lookup}과
     * 방향이 반대다. org에는 <b>표시 이름으로 찾는 창구가 없다</b>(이메일과 local-part만 본다) —
     * 이름 해석은 호출측이 다른 방법으로 해야 한다.
     *
     * <p>후보가 둘 이상인 질의는 org가 아예 답을 주지 않는다(남의 이름으로 잘못 짝지으면 안 되므로).
     * 못 찾았거나 조회가 실패한 질의는 결과에 없다 — 기본 구현은 이 창구를 안 쓰는 대역용으로 빈 결과다.
     *
     * @return 물어본 문자열(소문자) → 사람
     */
    default Map<String, DirectoryMember> lookupByEmail(Collection<String> emailsOrLocalParts) {
        return Map.of();
    }

    /** 실패를 구분할 필요가 없는 호출측용 — 못 읽었으면 빈 결과다 */
    default Map<Long, DirectoryMember> members(Collection<Long> ids) {
        return lookup(ids).members();
    }
}
