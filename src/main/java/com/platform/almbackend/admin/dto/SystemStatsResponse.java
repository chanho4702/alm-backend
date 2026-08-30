package com.platform.almbackend.admin.dto;

/** 관리 콘솔 시스템 현황 — 용량 가시성의 최소치 */
public record SystemStatsResponse(long projects, long issues, long attachments, long attachmentBytes, long auditEntries) {}
