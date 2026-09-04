package com.platform.almbackend;

import com.platform.almbackend.issue.IssueService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 설명 필수 판정 — 태그만 있는 HTML은 빈 값(프론트 isEmptyHtml과 같은 규칙) */
class DescriptionRequiredTest {
    @Test
    void 태그만_있는_HTML은_비어_있다() {
        assertThat(IssueService.hasText(null)).isFalse();
        assertThat(IssueService.hasText("")).isFalse();
        assertThat(IssueService.hasText("<p></p>")).isFalse();
        assertThat(IssueService.hasText("<p>&nbsp;</p>")).isFalse();
        assertThat(IssueService.hasText("<p>배경</p>")).isTrue();
        assertThat(IssueService.hasText("설명")).isTrue();
    }
}
