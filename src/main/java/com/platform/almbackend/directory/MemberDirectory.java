package com.platform.almbackend.directory;

import java.util.Collection;
import java.util.Map;

/**
 * 사용자 디렉터리 조회 — org-service가 사람의 원장이다. ALM은 id만 저장하고 이름·이메일은 여기서 읽는다
 * (common-proto 0.16.0 {@code GetMembers}).
 *
 * <p>여러 명이면 한 번에 묻는다 — 워처가 열 명인 이슈의 상태가 바뀌면 왕복도 열 번이 된다.
 * 없는 id는 결과에서 빠지므로 호출측은 개수를 요청과 맞추지 않는다.
 *
 * <p>조회 실패는 던지지 않고 빈 결과다. 이메일 알림은 org가 잠깐 불능이라고 해서 막히면 안 되는 부수 채널이라
 * 호출측이 스냅샷으로 폴백한다 — 권한 판정과 달리 여기서 fail-closed할 것이 없다.
 */
public interface MemberDirectory {

    /** id → 사람. 못 찾았거나 조회가 실패한 id는 결과에 없다. */
    Map<Long, DirectoryMember> members(Collection<Long> ids);
}
