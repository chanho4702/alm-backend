package com.platform.almbackend.settings.dto;

public final class RegistryRequests {
    private RegistryRequests() {}

    /** 생성은 name·kind·color 필수, 수정은 준 것만 바꾼다 */
    public record CategoryRequest(String name, String kind, String color) {}

    /** icon은 프론트 아이콘 맵의 lucide 키. 빈 문자열이면 카테고리 기본 아이콘으로 폴백한다 */
    public record StatusRequest(String name, String categoryId, String description, String icon) {}

    public record IssueTypeRequest(String name, String icon, String color, String level, String description) {}

    public record PriorityRequest(String name, String icon, String color, String description) {}

    public record LinkTypeRequest(String name, String outward, String inward) {}

    public record MoveRequest(int delta) {}
}
