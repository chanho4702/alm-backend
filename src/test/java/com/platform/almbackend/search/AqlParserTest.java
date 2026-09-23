package com.platform.almbackend.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.almbackend.search.aql.AqlAst;
import com.platform.almbackend.search.aql.AqlException;
import com.platform.almbackend.search.aql.AqlJson;
import com.platform.almbackend.search.aql.AqlParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 파서 계약 — 스펙 §6 테스트 벡터. 여기 적힌 JSON이 <b>프론트 {@code store/aql/parser.ts}가 만들어야 할
 * AST</b>다. 두 구현이 갈라지면 이 문자열이 먼저 어긋난다(같은 예시가 README AQL 절에도 있다).
 */
class AqlParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private String ast(String aql) {
        try {
            return JSON.writeValueAsString(AqlJson.query(AqlParser.parse(aql)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 벡터1_AND와_함수() {
        assertThat(ast("status = \"진행 중\" AND assignee = currentUser()")).isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"compare","field":"status","operator":"=","value":{"type":"string","value":"진행 중"}},\
                {"kind":"compare","field":"assignee","operator":"=","value":{"type":"function","name":"currentUser","args":[]}}\
                ]},"orderBy":[]}""");
    }

    @Test
    void 벡터2_괄호와_OR와_ORDER_BY_두개() {
        assertThat(ast("project = ALM AND (priority >= high OR due <= +3d) ORDER BY due ASC, priority DESC"))
                .isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"compare","field":"project","operator":"=","value":{"type":"ident","value":"ALM"}},\
                {"kind":"or","children":[\
                {"kind":"compare","field":"priority","operator":">=","value":{"type":"ident","value":"high"}},\
                {"kind":"compare","field":"due","operator":"<=","value":{"type":"ident","value":"+3d"}}]}\
                ]},"orderBy":[{"field":"due","direction":"asc"},{"field":"priority","direction":"desc"}]}""");
    }

    @Test
    void 벡터3_IN과_NOT() {
        assertThat(ast("labels IN (backend, \"api v2\") AND NOT type = 버그")).isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"in","field":"labels","negated":false,"values":[\
                {"type":"ident","value":"backend"},{"type":"string","value":"api v2"}]},\
                {"kind":"not","child":{"kind":"compare","field":"type","operator":"=",\
                "value":{"type":"ident","value":"버그"}}}\
                ]},"orderBy":[]}""");
    }

    @Test
    void IN_뒤에는_괄호_목록_대신_함수_하나가_올_수_있다() {
        assertThat(ast("sprint IN openSprints()")).isEqualTo(
                "{\"where\":{\"kind\":\"in\",\"field\":\"sprint\",\"negated\":false,\"values\":["
                        + "{\"type\":\"function\",\"name\":\"openSprints\",\"args\":[]}]},\"orderBy\":[]}");
        // 함수가 아니면 여전히 괄호 목록이 필요하다
        assertThatThrownBy(() -> AqlParser.parse("sprint IN backend"))
                .isInstanceOf(AqlException.class)
                .hasMessageContaining("여는 괄호가 필요합니다");
    }

    @Test
    void 벡터4_IS_EMPTY와_부등() {
        assertThat(ast("sprint IS EMPTY AND statusCategory != complete")).isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"empty","field":"sprint","negated":false},\
                {"kind":"compare","field":"statusCategory","operator":"!=",\
                "value":{"type":"ident","value":"complete"}}\
                ]},"orderBy":[]}""");
    }

    @Test
    void 벡터5_AND_셋은_한_노드로_평탄화된다() {
        assertThat(ast("text ~ 결제 AND created >= startOfMonth() AND assignee IS NOT EMPTY")).isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"compare","field":"text","operator":"~","value":{"type":"ident","value":"결제"}},\
                {"kind":"compare","field":"created","operator":">=",\
                "value":{"type":"function","name":"startOfMonth","args":[]}},\
                {"kind":"empty","field":"assignee","negated":true}\
                ]},"orderBy":[]}""");
    }

    @Test
    void 벡터6_별칭은_쓴_그대로_담기고_정규화는_해석이_한다() {
        assertThat(ast("상태 = 완료 AND 담당자 = 김찬호")).isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"compare","field":"상태","operator":"=","value":{"type":"ident","value":"완료"}},\
                {"kind":"compare","field":"담당자","operator":"=","value":{"type":"ident","value":"김찬호"}}\
                ]},"orderBy":[]}""");
    }

    @Test
    void 벡터7_방향을_안_쓰면_asc이고_상대날짜는_ident다() {
        assertThat(ast("resolution IS EMPTY AND updated < -14d ORDER BY updated")).isEqualTo("""
                {"where":{"kind":"and","children":[\
                {"kind":"empty","field":"resolution","negated":false},\
                {"kind":"compare","field":"updated","operator":"<","value":{"type":"ident","value":"-14d"}}\
                ]},"orderBy":[{"field":"updated","direction":"asc"}]}""");
    }

    @Test
    void 빈_질의는_조건도_정렬도_없다() {
        assertThat(ast("")).isEqualTo("{\"where\":null,\"orderBy\":[]}");
        assertThat(ast("   ")).isEqualTo("{\"where\":null,\"orderBy\":[]}");
        assertThat(ast("ORDER BY key DESC"))
                .isEqualTo("{\"where\":null,\"orderBy\":[{\"field\":\"key\",\"direction\":\"desc\"}]}");
    }

    @Test
    void AND가_OR보다_강하게_묶인다() {
        // a OR b AND c  ==  a OR (b AND c)
        assertThat(ast("status = a OR status = b AND status = c")).contains("\"kind\":\"or\"");
        assertThat(ast("status = a OR status = b AND status = c"))
                .contains("{\"kind\":\"and\",\"children\":[{\"kind\":\"compare\",\"field\":\"status\","
                        + "\"operator\":\"=\",\"value\":{\"type\":\"ident\",\"value\":\"b\"}}");
    }

    @Test
    void 괄호가_붙지_않으면_함수가_아니다() {
        // `done (x)`는 함수 호출이 아니라 "값 뒤에 예상하지 못한 입력"이다
        assertThatThrownBy(() -> AqlParser.parse("status = done (x)"))
                .isInstanceOf(AqlException.class)
                .hasMessageContaining("여기서 끝나야 합니다");
    }

    @Test
    void 오류벡터_이중등호는_위치7이다() {
        AqlException e = assertThrows(AqlException.class, () -> AqlParser.parse("status == done"));
        assertThat(e.getMessage()).isEqualTo("연산자를 모릅니다: ==");
        assertThat(e.position()).isEqualTo(7);
        assertThat(e.expected()).containsExactly("=", "!=");
    }

    @Test
    void 오류벡터_값이_없으면_입력_끝을_가리킨다() {
        AqlException e = assertThrows(AqlException.class, () -> AqlParser.parse("status = "));
        assertThat(e.getMessage()).isEqualTo("값이 필요합니다");
        assertThat(e.position()).isEqualTo(9);
    }

    @Test
    void 오류벡터_괄호가_안_닫히면_끝자리를_가리킨다() {
        AqlException e = assertThrows(AqlException.class, () -> AqlParser.parse("(status = done"));
        assertThat(e.getMessage()).isEqualTo("괄호를 닫아야 합니다");
        assertThat(e.position()).isEqualTo(14);
        assertThat(e.expected()).containsExactly(")");
    }

    @Test
    void 오류벡터_따옴표를_안_닫으면_여는_따옴표를_가리킨다() {
        AqlException e = assertThrows(AqlException.class, () -> AqlParser.parse("status = \"진행"));
        assertThat(e.getMessage()).isEqualTo("따옴표를 닫아야 합니다");
        assertThat(e.position()).isEqualTo(9);
    }

    @Test
    void 키워드는_대소문자를_가리지_않는다() {
        assertThat(ast("status = a and priority = b order by key desc"))
                .isEqualTo(ast("status = a AND priority = b ORDER BY key DESC"));
        assertThat(ast("sprint is not empty")).isEqualTo(ast("sprint IS NOT EMPTY"));
        assertThat(ast("labels not in (a)")).isEqualTo(ast("labels NOT IN (a)"));
    }

    @Test
    void 중첩이_깊으면_재귀하기_전에_거절한다() {
        // 괄호도 NOT도 재귀라 둘 다 막아야 StackOverflowError가 500으로 새지 않는다
        AqlException parens = assertThrows(AqlException.class,
                () -> AqlParser.parse("(".repeat(60) + "status = done" + ")".repeat(60)));
        assertThat(parens.getMessage()).isEqualTo("너무 깊게 중첩됐습니다 (최대 50단계)");

        assertThatThrownBy(() -> AqlParser.parse("NOT ".repeat(60) + "status = done"))
                .isInstanceOf(AqlException.class);

        // 50단계까지는 통과한다
        AqlParser.parse("(".repeat(49) + "status = done" + ")".repeat(49));
    }

    @Test
    void 숫자와_낱말을_가른다() {
        AqlAst.Query query = AqlParser.parse("estimate > 3.5");
        AqlAst.Compare compare = (AqlAst.Compare) query.where();
        assertThat(compare.value().kind()).isEqualTo(AqlAst.ValueKind.NUMBER);

        AqlAst.Compare date = (AqlAst.Compare) AqlParser.parse("due = 2026-09-06").where();
        assertThat(date.value().kind()).isEqualTo(AqlAst.ValueKind.IDENT);
    }
}
