package com.platform.almbackend.search.aql;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AQL 오류만 다루는 400 핸들러 — 공통 {@code {"error"}} 계약에 {@code position}·{@code expected}를 더한다.
 * 프론트 에디터가 그 자리에 밑줄을 긋고 힌트를 띄운다.
 *
 * <p>{@link AqlException}이 {@code IllegalArgumentException}을 상속하지 않는 이유가 여기 있다 —
 * common-starter의 공통 핸들러가 먼저 잡으면 위치가 사라진다.
 */
@RestControllerAdvice
public class AqlExceptionHandler {

    @ExceptionHandler(AqlException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> aql(AqlException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("position", e.position());
        body.put("expected", e.expected());
        return body;
    }
}
