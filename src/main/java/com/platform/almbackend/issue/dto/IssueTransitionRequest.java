package com.platform.almbackend.issue.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.platform.almbackend.domain.IssueResolution;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 전환 실행(2026-10-24 A4·C2) — 전환 하나와 그 전환 화면의 값을 한 요청으로. 화면에 없는 칸을 보내면 400이다.
 */
@Schema(description = "전환 실행 요청. fields에는 그 전환의 screenFields에 있는 칸만 보낼 수 있다")
public record IssueTransitionRequest(
        @Schema(description = "전환 ID(GET .../transitions의 id)", example = "t-done")
        @NotBlank(message = "전환 ID가 필요합니다")
        @Size(max = 120, message = "전환 ID는 120자 이하여야 합니다")
        String transitionId,
        @Schema(description = "화면이 본 이슈 버전. 주면 서버 값과 다를 때 409, 생략하면 검사하지 않는다", example = "3")
        Integer expectedVersion,
        @Schema(description = "전환 화면 값 — 키를 보낸 칸만 바뀐다(assigneeId·fixVersionId·dueDate는 null이면 해제)")
        @Valid Fields fields,
        @Schema(description = "전환 코멘트 — 화면에 comment 칸이 있을 때만. 비우면 남기지 않는다")
        @Size(max = 50000, message = "코멘트는 50000자 이하여야 합니다")
        String comment) {

    @JsonIgnore
    public Fields fieldsOrEmpty() { return fields == null ? new Fields() : fields; }

    /** 보낸 키만 적용 — 일괄 편집 Changes와 같은 방식(세터가 키의 존재를 기록) */
    @Schema(description = "전환 화면 값")
    public static final class Fields {
        private final Set<String> present = new HashSet<>();
        private IssueResolution resolution;
        private Long assigneeId;
        @Positive(message = "fixVersionId는 양수여야 합니다")
        private Long fixVersionId;
        private LocalDate dueDate;

        @Schema(description = "해결. null이면 기본값(완료). 완료 상태로 가는 전환에만") public IssueResolution getResolution() { return resolution; }
        @Schema(description = "담당자 사용자 ID. null이면 미지정") public Long getAssigneeId() { return assigneeId; }
        @Schema(description = "수정 버전 ID. null이면 해제") public Long getFixVersionId() { return fixVersionId; }
        @Schema(description = "마감일. null이면 해제") public LocalDate getDueDate() { return dueDate; }

        @JsonProperty("resolution") public void setResolution(IssueResolution v) { resolution = v; present.add("resolution"); }
        @JsonProperty("assigneeId") public void setAssigneeId(Long v) { assigneeId = v; present.add("assignee"); }
        @JsonProperty("fixVersionId") public void setFixVersionId(Long v) { fixVersionId = v; present.add("fixVersion"); }
        @JsonProperty("dueDate") public void setDueDate(LocalDate v) { dueDate = v; present.add("dueDate"); }

        /** 화면 칸 id(resolution·assignee·fixVersion·dueDate) 기준으로 보냈나 */
        public boolean has(String screenField) { return present.contains(screenField); }

        @JsonIgnore
        public Set<String> sent() { return Set.copyOf(present); }
    }
}
