package com.platform.almbackend.issue.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 일괄 편집 — 이슈 N개에 같은 필드 변경을 한 요청으로(감사 A-11). 행마다 자기 트랜잭션이고, 행마다 결과를 돌려준다.
 */
@Schema(description = "이슈 일괄 편집 요청. items의 이슈마다 changes를 적용한다")
public record IssueBulkUpdateRequest(
        @Schema(description = "대상 이슈와 화면이 본 버전. 최대 " + IssueBulkUpdateRequest.MAX_ITEMS + "건")
        @NotEmpty(message = "선택한 이슈가 없습니다")
        @Size(max = IssueBulkUpdateRequest.MAX_ITEMS, message = "한 번에 " + IssueBulkUpdateRequest.MAX_ITEMS + "건까지 바꿀 수 있습니다")
        List<@NotNull(message = "대상 이슈가 비어 있습니다") @Valid Item> items,
        @Schema(description = "바꿀 필드. 보낸 필드만 바뀐다(생략=그대로, null=해제)")
        @NotNull(message = "바꿀 내용이 없습니다")
        @Valid Changes changes) {

    public static final int MAX_ITEMS = 200;

    @Schema(description = "대상 이슈 한 건")
    public record Item(
            @Schema(description = "이슈 ID", example = "1024")
            @NotNull(message = "issueId가 필요합니다") @Positive(message = "issueId는 양수여야 합니다") Long issueId,
            @Schema(description = "화면이 본 이슈 버전. 서버 값과 다르면 그 행만 CONFLICT. 생략하면 행을 처리하는 순간의 버전으로 저장한다(충돌 검사 없음)",
                    example = "3")
            Integer expectedVersion) {}

    /**
     * 보낸 필드만 적용한다 — JSON에 키가 있으면(값이 null이어도) "바꾼다", 없으면 "그대로". 레코드로는 "없음"과
     * "null"을 가를 수 없어 세터가 키의 존재를 기록하는 클래스로 둔다(Jackson 버전과 무관하게 세터는 키가 있을 때만 불린다).
     */
    @Schema(description = "바꿀 필드 — 키를 보낸 것만 바뀐다. assigneeId·sprintId·fixVersionId는 null이면 해제(미지정·백로그·없음)")
    public static final class Changes {
        private final Set<String> present = new HashSet<>();
        private String status;
        private String priority;
        private Long assigneeId;
        private Long sprintId;
        private Long fixVersionId;
        @Size(max = 50, message = "라벨은 최대 50개까지 지정할 수 있습니다")
        private List<@Size(max = 80, message = "라벨은 80자 이하여야 합니다") String> addLabels;
        @Size(max = 50, message = "라벨은 최대 50개까지 지정할 수 있습니다")
        private List<String> removeLabels;

        @Schema(description = "상태 ID", example = "inprogress") public String getStatus() { return status; }
        @Schema(description = "우선순위 ID", example = "high") public String getPriority() { return priority; }
        @Schema(description = "담당자 사용자 ID. null이면 미지정", example = "42") public Long getAssigneeId() { return assigneeId; }
        @Schema(description = "스프린트 ID. null이면 백로그", example = "12") public Long getSprintId() { return sprintId; }
        @Schema(description = "수정 버전 ID. null이면 해제", example = "5") public Long getFixVersionId() { return fixVersionId; }
        @Schema(description = "더할 라벨") public List<String> getAddLabels() { return addLabels; }
        @Schema(description = "뺄 라벨") public List<String> getRemoveLabels() { return removeLabels; }

        @JsonProperty("status") public void setStatus(String v) { status = v; present.add("status"); }
        @JsonProperty("priority") public void setPriority(String v) { priority = v; present.add("priority"); }
        @JsonProperty("assigneeId") public void setAssigneeId(Long v) { assigneeId = v; present.add("assigneeId"); }
        @JsonProperty("sprintId") public void setSprintId(Long v) { sprintId = v; present.add("sprintId"); }
        @JsonProperty("fixVersionId") public void setFixVersionId(Long v) { fixVersionId = v; present.add("fixVersionId"); }
        @JsonProperty("addLabels") public void setAddLabels(List<String> v) { addLabels = v; present.add("addLabels"); }
        @JsonProperty("removeLabels") public void setRemoveLabels(List<String> v) { removeLabels = v; present.add("removeLabels"); }

        /** 이 필드를 바꾸라는 요청인가(키가 왔는가) */
        public boolean has(String field) { return present.contains(field); }

        @JsonIgnore
        public boolean isEmpty() {
            return present.isEmpty()
                    || (present.stream().allMatch(f -> f.equals("addLabels") || f.equals("removeLabels"))
                        && (addLabels == null || addLabels.isEmpty()) && (removeLabels == null || removeLabels.isEmpty()));
        }
    }
}
