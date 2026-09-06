package com.platform.almbackend.search.aql;

import java.util.List;

/**
 * AQL 문법·해석 오류. 프론트 에디터가 {@code position}으로 밑줄을 긋고 {@code expected}로 힌트를 띄운다.
 *
 * <p>{@code IllegalArgumentException}을 상속하지 않는다 — common-starter의 공통 핸들러가 먼저 잡아
 * {@code {"error"}}만 남기면 위치 정보가 사라진다. 전용 핸들러는
 * {@link AqlExceptionHandler}에 있다.
 */
public class AqlException extends RuntimeException {

    /** 0부터 세는 입력 문자열 오프셋 */
    private final int position;

    /** 그 자리에 올 수 있었던 것(비어 있을 수 있다) */
    private final List<String> expected;

    public AqlException(String message, int position, List<String> expected) {
        super(message);
        this.position = Math.max(0, position);
        this.expected = expected == null ? List.of() : List.copyOf(expected);
    }

    public static AqlException at(int position, String message, String... expected) {
        return new AqlException(message, position, List.of(expected));
    }

    public int position() {
        return position;
    }

    public List<String> expected() {
        return expected;
    }
}
