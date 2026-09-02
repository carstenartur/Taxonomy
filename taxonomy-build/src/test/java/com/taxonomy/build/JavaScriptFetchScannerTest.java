package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaScriptFetchScannerTest {

    @Test
    void countsExecutableCallsAndDirectApiLiteralsWithStableLines() {
        String source = """
                const first = fetch('/api/first');
                const second = window.fetch(
                    /* request target */ `/api/second/${id}`);
                const third = globalThis.fetch?.('/other');
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(
                        new JavaScriptFetchScanner.FetchCall(1, true),
                        new JavaScriptFetchScanner.FetchCall(2, true),
                        new JavaScriptFetchScanner.FetchCall(4, false));
    }

    @Test
    void classifiesTriviallyParenthesizedDirectApiLiterals() {
        String source = """
                fetch((/* grouping */ '/api/grouped'));
                fetch(((condition ? '/api/conditional' : '/other')));
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(
                        new JavaScriptFetchScanner.FetchCall(1, true),
                        new JavaScriptFetchScanner.FetchCall(2, false));
    }

    @Test
    void ignoresCommentsQuotedTextRegexAndTemplateLiteralText() {
        String source = """
                // fetch('/api/comment')
                /* window.fetch('/api/block') */
                const single = "fetch('/api/string')";
                const doubleQuoted = 'window.fetch("/api/string")';
                const expression = /fetch\\('\\/api\\/regex'\\)/g;
                const rawTemplate = `fetch('/api/raw-template')`;
                """;

        assertThat(JavaScriptFetchScanner.scan(source)).isEmpty();
        assertThat(FrontendApiBoundaryPolicy.countDirectFetch(source)).isZero();
    }

    @Test
    void ignoresRegexLiteralsAfterControlHeadersAndOperatorRuns() {
        String source = """
                if (ready) /fetch\\('\\/api\\/if'\\)/.test(value);
                while (ready) /fetch\\('\\/api\\/while'\\)/.test(value);
                const token = counter+++ /fetch\\('\\/api\\/operator'\\)/.test(value);
                object.fetch('/api/member');
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(new JavaScriptFetchScanner.FetchCall(4, true));
    }

    @Test
    void retainsExecutableFetchCallsInsideNestedTemplateExpressions() {
        String source = """
                const value = `raw fetch('/api/not-code') ${
                    condition
                        ? fetch('/api/live')
                        : `nested ${window.fetch('/other')}`
                } tail`;
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(
                        new JavaScriptFetchScanner.FetchCall(3, true),
                        new JavaScriptFetchScanner.FetchCall(4, false));
    }

    @Test
    void ignoresIdentifierFragmentsButCountsMemberAndBareCalls() {
        String source = """
                prefetch('/api/not-fetch');
                fetcher('/api/not-fetch');
                object.fetch('/api/member');
                fetch('/api/bare');
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(
                        new JavaScriptFetchScanner.FetchCall(3, true),
                        new JavaScriptFetchScanner.FetchCall(4, true));
    }

    @Test
    void preservesCallsAfterLiteralDivisionAndPostfixOperators() {
        String source = """
                const fromString = 'left' / fetch('/api/string-divisor');
                const fromTemplate = `left` / window.fetch('/api/template-divisor');
                const fromRegex = /left/ / globalThis.fetch('/api/regex-divisor');
                const fromIncrement = counter++ / fetch('/api/increment-divisor');
                const fromDecrement = counter-- / fetch('/api/decrement-divisor');
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(
                        new JavaScriptFetchScanner.FetchCall(1, true),
                        new JavaScriptFetchScanner.FetchCall(2, true),
                        new JavaScriptFetchScanner.FetchCall(3, true),
                        new JavaScriptFetchScanner.FetchCall(4, true),
                        new JavaScriptFetchScanner.FetchCall(5, true));
    }

    @Test
    void ignoresNamedAndMethodDeclarationsButCountsRealMemberCalls() {
        String source = """
                function fetch() {}
                const expression = function fetch() {};
                function * fetch() {}
                async function fetch() {}
                class Transport {
                    fetch() {}
                    async fetch() {}
                    *fetch() {}
                }
                const adapter = { fetch() {}, async fetch() {} };
                object.fetch('/api/member');
                """;

        assertThat(JavaScriptFetchScanner.scan(source))
                .containsExactly(
                        new JavaScriptFetchScanner.FetchCall(11, true));
    }

    @Test
    void policyDoesNotReportCommentOrStringFixturesAsTransportDebt() {
        String source = """
                // fetch('/api/comment')
                const documentation = "fetch('/api/example')";
                const regex = /fetch\\(/;
                """;

        List<JavaScriptFetchScanner.FetchCall> calls =
                JavaScriptFetchScanner.scan(source);

        assertThat(calls).isEmpty();
    }
}
