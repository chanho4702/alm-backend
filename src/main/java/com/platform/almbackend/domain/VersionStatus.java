package com.platform.almbackend.domain;

/** 버전 상태 — 지라와 같은 3단계. 미릴리스 → 릴리스 / 보관. */
public enum VersionStatus {
    UNRELEASED,
    RELEASED,
    ARCHIVED
}
