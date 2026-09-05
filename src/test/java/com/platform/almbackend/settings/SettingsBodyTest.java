package com.platform.almbackend.settings;

import com.platform.almbackend.settings.SettingsBody.FieldConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 필드 구성 정규화·해석 — 스프링 없이 본문 레코드만 본다(컨트롤러 계약은 SettingsControllerTest). */
class SettingsBodyTest {

    private static SettingsBody bodyWith(List<FieldConfig> fields, Map<String, List<FieldConfig>> byType) {
        return new SettingsBody(List.of(), List.of(), Map.of(), List.of(), null, null, fields, byType);
    }

    @Test
    void 기준_구성에_없는_필드도_기본값으로_채워_null이_섞이지_않는다() {
        List<FieldConfig> merged = SettingsBody.overlay(
                List.of(new FieldConfig("dueDate", false, false)),
                List.of(new FieldConfig("assignee", true, true)));

        assertThat(merged).hasSize(13).doesNotContainNull();
        assertThat(merged.stream().map(FieldConfig::id)).containsExactlyElementsOf(SettingsBody.FIELD_IDS);
        // 덮어쓰기가 이긴다
        assertThat(merged.get(1)).isEqualTo(new FieldConfig("assignee", true, true));
        // 기준에 있으면 기준을 따른다
        assertThat(merged.get(7)).isEqualTo(new FieldConfig("dueDate", false, false));
        // 양쪽에 없으면 표시·비필수
        assertThat(merged.get(0)).isEqualTo(new FieldConfig("description", true, false));
    }

    @Test
    void 타입_해석은_기본_위에_필드_단위로_얹히고_응답_정규화와_같은_값이다() {
        SettingsBody body = bodyWith(
                List.of(new FieldConfig("assignee", true, true)),
                Map.of("bug", List.of(new FieldConfig("dueDate", true, true))));

        // 해석과 응답이 갈라지면 화면이 서버와 다른 필수를 그린다
        assertThat(body.resolveFields("bug")).isEqualTo(body.fieldsByType().get("bug"));
        // 덮어쓰기가 없는 필드는 기본을 물려받는다
        assertThat(body.fieldsById("bug").get("assignee").required()).isTrue();
        assertThat(body.fieldsById("bug").get("dueDate").required()).isTrue();
        // 기본 구성 자체는 그대로다
        assertThat(body.fieldsById().get("dueDate").required()).isFalse();
        // 키가 없는 타입과 null 타입은 기본을 따른다
        assertThat(body.resolveFields("task")).isEqualTo(body.fields());
        assertThat(body.resolveFields(null)).isEqualTo(body.fields());
    }

    @Test
    void 빈_덮어쓰기와_구버전_본문은_기본_구성으로_읽는다() {
        SettingsBody empty = bodyWith(List.of(), Map.of("bug", List.of()));
        assertThat(empty.fieldsByType()).isEmpty();
        assertThat(empty.resolveFields("bug")).isEqualTo(empty.fields());
        assertThat(empty.fields()).isEqualTo(SettingsBody.defaultFields());

        SettingsBody legacy = bodyWith(null, null);
        assertThat(legacy.fieldsByType()).isEmpty();
        assertThat(legacy.fields()).isEqualTo(SettingsBody.defaultFields());
        assertThat(legacy.resolveFields("bug")).isEqualTo(SettingsBody.defaultFields());
    }
}
