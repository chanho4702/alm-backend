package com.platform.almbackend.issue.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 계획 필드(V30) — 시작일·스토리 포인트. {@code details}와 따로 둔 이유: {@code details}는 "보내면 통째로 교체"라
 * 새 필드를 거기 넣으면 이 필드를 모르는 옛 클라이언트의 저장이 값을 지운다. 여기는 <b>키를 보낸 것만</b> 바뀐다
 * (생략=그대로, null=비우기) — 일괄 편집 {@code changes}와 같은 방식이다.
 */
@Schema(description = "계획 필드 — 키를 보낸 것만 바뀐다(생략=그대로, null=비우기). 생성 때는 생략=비어 있음")
public final class IssuePlanningRequest {
    private final Set<String> present = new HashSet<>();
    private LocalDate startDate;
    @DecimalMin(value = "0", message = "스토리 포인트는 0 이상이어야 합니다")
    @DecimalMax(value = "9999", message = "스토리 포인트는 9999 이하여야 합니다")
    @Digits(integer = 4, fraction = 1, message = "스토리 포인트는 소수 첫째 자리까지 입력하세요")
    private BigDecimal storyPoints;

    public IssuePlanningRequest() {}

    /** 코드에서 만들 때(자동화·복제) — 둘 다 보낸 것으로 친다 */
    public static IssuePlanningRequest of(LocalDate startDate, BigDecimal storyPoints) {
        IssuePlanningRequest request = new IssuePlanningRequest();
        request.setStartDate(startDate);
        request.setStoryPoints(storyPoints);
        return request;
    }

    @Schema(description = "시작일. 마감일보다 늦으면 400", example = "2026-10-27")
    public LocalDate getStartDate() { return startDate; }

    @Schema(description = "스토리 포인트(0~9999, 소수 첫째 자리)", example = "3")
    public BigDecimal getStoryPoints() { return storyPoints; }

    @JsonProperty("startDate") public void setStartDate(LocalDate v) { startDate = v; present.add("startDate"); }
    @JsonProperty("storyPoints") public void setStoryPoints(BigDecimal v) { storyPoints = v; present.add("storyPoints"); }

    /** 이 필드를 바꾸라는 요청인가(키가 왔는가) */
    public boolean has(String field) { return present.contains(field); }

    @JsonIgnore
    public boolean isEmpty() { return present.isEmpty(); }
}
