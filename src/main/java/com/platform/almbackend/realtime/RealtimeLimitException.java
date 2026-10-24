package com.platform.almbackend.realtime;

/** 실시간 연결 상한 초과 — 429 {@code {"error"}} */
public class RealtimeLimitException extends RuntimeException {
    public RealtimeLimitException(String message) {
        super(message);
    }
}
