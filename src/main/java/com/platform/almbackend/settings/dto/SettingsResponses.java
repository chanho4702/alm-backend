package com.platform.almbackend.settings.dto;

import com.platform.almbackend.domain.IssueTypeDef;
import com.platform.almbackend.domain.SettingsScheme;
import com.platform.almbackend.domain.StatusCategory;
import com.platform.almbackend.domain.StatusDef;
import com.platform.almbackend.settings.SettingsBody;

public final class SettingsResponses {
    private SettingsResponses() {}

    public record CategoryResponse(String id, String name, String kind, String color, int order, boolean builtIn) {
        public static CategoryResponse from(StatusCategory c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getKind(), c.getColor(), c.getSortOrder(), c.isBuiltIn());
        }
    }

    /** icon은 저장된 원본이다(빈 문자열 = 미지정) — 레지스트리 편집기가 "미지정"을 보여야 하므로 폴백하지 않는다 */
    public record StatusResponse(String id, String name, String categoryId, String description, String icon) {
        public static StatusResponse from(StatusDef d) {
            return new StatusResponse(d.getId(), d.getName(), d.getCategoryId(), d.getDescription(), d.getIcon());
        }
    }

    public record LinkTypeResponse(String id, String name, String outward, String inward, int order, boolean builtIn) {
        public static LinkTypeResponse from(com.platform.almbackend.domain.LinkTypeDef t) {
            return new LinkTypeResponse(t.getId(), t.getName(), t.getOutward(), t.getInward(), t.getSortOrder(), t.isBuiltIn());
        }
    }

    public record PriorityResponse(String id, String name, String icon, String color, String description, int order, boolean builtIn) {
        public static PriorityResponse from(com.platform.almbackend.domain.PriorityDef p) {
            return new PriorityResponse(p.getId(), p.getName(), p.getIcon(), p.getColor(), p.getDescription(), p.getSortOrder(), p.isBuiltIn());
        }
    }

    public record IssueTypeResponse(String id, String name, String icon, String color, String level,
                                    String description, int order, boolean builtIn) {
        public static IssueTypeResponse from(IssueTypeDef t) {
            return new IssueTypeResponse(t.getId(), t.getName(), t.getIcon(), t.getColor(), t.getLevel(),
                    t.getDescription(), t.getSortOrder(), t.isBuiltIn());
        }
    }

    /** 본문은 레지스트리로 해석된 값(kind/color 포함) */
    public record SchemeResponse(String id, String name, boolean isDefault, SettingsBody body) {
        public static SchemeResponse of(SettingsScheme s, SettingsBody body) {
            return new SchemeResponse(s.getId(), s.getName(), s.isDefault(), body);
        }
    }

    public record ResolvedSettingsResponse(SettingsBody body, String source, SchemeResponse scheme) {}
}
