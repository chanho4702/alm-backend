package com.platform.almbackend.dev;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 커밋 메시지 이슈 키 파싱 경계(V41) + 저장소 URL 정규화 */
class IssueKeyParserTest {
    private static final Set<String> KEYS = Set.of("PLAT", "WIKI", "MY-APP", "APP", "DS_2");

    @Test
    void 흔한_표기를_모두_잡고_대문자로_정규화한다() {
        assertThat(IssueKeyParser.parse("[PLAT-12] 로그인 (WIKI-3)\n\nRefs: plat-13\nfixes #1, Fixes PLAT-14.", KEYS))
                .containsExactly("PLAT-12", "WIKI-3", "PLAT-13", "PLAT-14");
        assertThat(IssueKeyParser.parse("feat/PLAT-7-login: x", KEYS)).containsExactly("PLAT-7");
        assertThat(IssueKeyParser.parse("PLAT-012 와 PLAT-12 는 같다", KEYS)).containsExactly("PLAT-12");
        assertThat(IssueKeyParser.parse("DS_2-5", KEYS)).containsExactly("DS_2-5");
    }

    @Test
    void 등록되지_않은_키와_낱말에_붙은_키는_아니다() {
        assertThat(IssueKeyParser.parse("UTF-8 ISO-8601 SHA-256 NOPE-3", KEYS)).isEmpty();
        assertThat(IssueKeyParser.parse("xPLAT-12 PLAT-12a PLAT_12 PLAT-0 PLAT-1234567890", KEYS)).isEmpty();
        assertThat(IssueKeyParser.parse("", KEYS)).isEmpty();
        assertThat(IssueKeyParser.parse("PLAT-1", Set.of())).isEmpty();
    }

    @Test
    void 하이픈이_든_프로젝트_키는_가장_긴_등록_키를_고른다() {
        assertThat(IssueKeyParser.parse("MY-APP-3", KEYS)).containsExactly("MY-APP-3");
        assertThat(IssueKeyParser.parse("OTHER-APP-4 feature-PLAT-5", KEYS)).containsExactly("APP-4", "PLAT-5");
    }

    @Test
    void 한_메시지에서_키는_20개까지() {
        StringBuilder message = new StringBuilder();
        for (int i = 1; i <= 30; i++) message.append("PLAT-").append(i).append(' ');
        assertThat(IssueKeyParser.parse(message.toString(), KEYS)).hasSize(IssueKeyParser.MAX_KEYS).startsWith("PLAT-1");
    }

    @Test
    void 저장소_URL은_같은_저장소면_같은_문자열이다() {
        assertThat(RepositoryUrls.normalize("https://GitHub.com/Owner/Repo.git/")).isEqualTo("https://github.com/Owner/Repo");
        assertThat(RepositoryUrls.normalize("git@github.com:Owner/Repo.git")).isEqualTo("https://github.com/Owner/Repo");
        assertThat(RepositoryUrls.normalize("http://gitea.local:3000/team/sub/repo")).isEqualTo("http://gitea.local:3000/team/sub/repo");
        assertThat(RepositoryUrls.defaultName("https://github.com/Owner/Repo")).isEqualTo("Owner/Repo");
        assertThatThrownBy(() -> RepositoryUrls.normalize("https://github.com/only")).hasMessageContaining("소유자/저장소");
        assertThatThrownBy(() -> RepositoryUrls.normalize("ftp://x.com/a/b")).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> RepositoryUrls.normalize("https://u:p@x.com/a/b")).hasMessage("저장소 URL에 계정 정보를 넣을 수 없습니다");
        assertThatThrownBy(() -> RepositoryUrls.normalize("https://x.com/a/b?x=1")).hasMessageContaining("질의");
    }

    @Test
    void 커서는_왕복하고_깨지면_400용_예외다() {
        assertThatThrownBy(() -> DevCommitService.decodeCursor("!!")).isInstanceOf(IllegalArgumentException.class);
        String[] split = DevCommitService.splitMessage("  제목  \r\n\r\n\r\n본문1\n본문2\n");
        assertThat(split[0]).isEqualTo("제목");
        assertThat(split[1]).isEqualTo("본문1\n본문2");
        assertThat(DevCommitService.splitMessage("제목만")[1]).isNull();
    }
}
