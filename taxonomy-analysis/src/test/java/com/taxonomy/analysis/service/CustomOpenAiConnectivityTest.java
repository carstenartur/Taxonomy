package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CustomOpenAiConnectivityTest {
    private static final String URL = "http://model-service:8000/v1/chat/completions";

    @Test
    void checksTheSameServerWithoutGeneratingACompletionAndCachesTheResult() {
        List<HttpRequest> requests = new ArrayList<>();
        AtomicLong now = new AtomicLong();
        var probe = new CustomOpenAiConnectivity(request -> { requests.add(request); return 200; }, now::get);

        assertThat(probe.check(URL, "secret")).isEqualTo(CustomOpenAiConnectivity.Result.REACHABLE);
        assertThat(probe.check(URL, "secret")).isEqualTo(CustomOpenAiConnectivity.Result.REACHABLE);
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().method()).isEqualTo("GET");
        assertThat(requests.getFirst().uri().toString()).isEqualTo("http://model-service:8000/v1/models");
        assertThat(requests.getFirst().headers().firstValue("Authorization")).contains("Bearer secret");
        assertThat(requests.getFirst().timeout()).contains(Duration.ofSeconds(3));

        now.set(Duration.ofSeconds(16).toNanos());
        probe.check(URL, "secret");
        assertThat(requests).hasSize(2);
        probe.check(URL, null);
        assertThat(requests).hasSize(3);
        assertThat(requests.getLast().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void distinguishesUnreachableAuthenticationAndUnsupportedModelLists() {
        var unreachable = new CustomOpenAiConnectivity(request -> { throw new IOException("private detail"); }, () -> 0L);
        assertThat(unreachable.check(URL, null)).isEqualTo(CustomOpenAiConnectivity.Result.UNREACHABLE);
        assertThat(new CustomOpenAiConnectivity(request -> 401, () -> 0L).check(URL, "bad"))
                .isEqualTo(CustomOpenAiConnectivity.Result.AUTHENTICATION_FAILED);
        assertThat(new CustomOpenAiConnectivity(request -> 503, () -> 0L).check(URL, null))
                .isEqualTo(CustomOpenAiConnectivity.Result.UNREACHABLE);
        assertThat(new CustomOpenAiConnectivity(request -> 404, () -> 0L).check(URL, null))
                .isEqualTo(CustomOpenAiConnectivity.Result.UNKNOWN);
        assertThat(new CustomOpenAiConnectivity(request -> 302, () -> 0L).check(URL, null))
                .isEqualTo(CustomOpenAiConnectivity.Result.UNKNOWN);
    }

    @Test
    void preservesTheConfiguredPathAndQueryWithoutChangingTheOrigin() {
        List<HttpRequest> requests = new ArrayList<>();
        var probe = new CustomOpenAiConnectivity(request -> { requests.add(request); return 200; }, () -> 0L);
        probe.check("https://example.test/proxy%20path/v1/chat/completions?api-version=1", null);
        assertThat(requests.getFirst().uri().toString())
                .isEqualTo("https://example.test/proxy%20path/v1/models?api-version=1");
    }

    @Test
    void realTransportDoesNotReportAClosedLocalPortAsReachable() {
        assertThat(new CustomOpenAiConnectivity().check("http://127.0.0.1:1/v1/chat/completions", null))
                .isEqualTo(CustomOpenAiConnectivity.Result.UNREACHABLE);
    }
}
