package com.taxonomy.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the canonical timeout, cancellation and ProblemDetail transport contract. */
class TaxonomyApiClientContractTest {

    @Test
    void namedHelpersUseOneCanonicalTransportInsteadOfCallingGlobalFetch() throws Exception {
        String source = resource("/static/js/api/taxonomy-api-client.js");

        assertThat(source)
                .contains("function request(url, init, options)")
                .contains("var transportFetch = window.fetch.bind(window)")
                .contains("prepared.credentials = 'same-origin'")
                .contains("headers.set(REQUEST_ID_HEADER, requestId)")
                .contains("var responseContexts = new WeakMap()")
                .contains("responseRequestId(response, context.requestId || null)")
                .contains("function createRequestScope(options, validated)")
                .contains("code: 'TIMEOUT'")
                .contains("code: 'ABORTED'")
                .contains("taxonomy-api-auth-failure")
                .contains("Automatic API retry requires idempotent: true")
                .contains("return request(url, { method: 'GET' }, requestOptions).then(parseJson)")
                .doesNotContain("function getJson(url) {\n        return fetch(url)")
                .doesNotContain("function sendJson(url, body, method) {\n        return fetch(url");
    }

    @Test
    void problemDetailsRemainStructuredOnApiErrors() throws Exception {
        String source = resource("/static/js/api/taxonomy-api-client.js");

        assertThat(source)
                .contains("this.type = details.type || null")
                .contains("this.title = details.title || null")
                .contains("this.detail = details.detail || null")
                .contains("this.instance = details.instance || null")
                .contains("this.requestId = details.requestId || null")
                .contains("problem.detail || problem.message || problem.error");
    }

    private static String resource(String path) throws IOException {
        try (InputStream input = TaxonomyApiClientContractTest.class.getResourceAsStream(path)) {
            assertThat(input).as("classpath resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
