package com.platform.almbackend.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "알림 일괄 처리(V40). 남의 알림 id는 조용히 건너뛴다(updated에 세지 않는다)")
public record NotificationBulkRequest(
        @Schema(description = "알림 id 목록(최대 200)")
        @NotEmpty(message = "알림을 하나 이상 고르세요")
        @Size(max = 200, message = "한 번에 200건까지 처리할 수 있습니다")
        List<@NotNull(message = "빈 알림 id는 쓸 수 없습니다") Long> ids,
        @Schema(description = "read · unread · archive · unarchive", example = "read")
        @NotBlank(message = "action이 필요합니다")
        String action) {}
