package com.platform.almbackend.admin.dto;

import java.util.List;

public record AuditPageResponse(List<AuditLogResponse> items, int page, int size, long total) {}
