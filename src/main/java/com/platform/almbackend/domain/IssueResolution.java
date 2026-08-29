package com.platform.almbackend.domain;

/** 해결 — 지라 기본 4종. 새 값을 더하면 V6의 CHECK 제약에도 더한다. */
public enum IssueResolution {
    DONE,
    WONT_DO,
    DUPLICATE,
    CANNOT_REPRODUCE
}
