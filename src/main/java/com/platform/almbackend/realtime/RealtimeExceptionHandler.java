package com.platform.almbackend.realtime;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 연결 상한 → 429 + 공통 오류 계약. 스트림 요청(Accept: text/event-stream)이어도 JSON으로 답한다 */
@RestControllerAdvice
public class RealtimeExceptionHandler {
    @ExceptionHandler(RealtimeLimitException.class)
    public ResponseEntity<Map<String, String>> limited(RealtimeLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .header("Retry-After", "30").body(Map.of("error", e.getMessage()));
    }
}
