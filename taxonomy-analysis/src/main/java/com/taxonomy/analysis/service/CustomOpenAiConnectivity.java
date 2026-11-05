package com.taxonomy.analysis.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** A bounded, non-generating check of the configured compatible server. */
@Component
public class CustomOpenAiConnectivity {
    public enum Result { REACHABLE, UNREACHABLE, AUTHENTICATION_FAILED, UNKNOWN }

    @FunctionalInterface
    interface Transport {
        int send(HttpRequest request) throws IOException, InterruptedException;
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final long CACHE_NANOS = Duration.ofSeconds(15).toNanos();
    private final Transport transport;
    private final LongSupplier clock;
    private String cachedUrl;
    private String cachedKey;
    private long checkedAt;
    private Result cached;

    public CustomOpenAiConnectivity() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.transport = request -> {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                return response.statusCode();
            }
        };
        this.clock = System::nanoTime;
    }

    CustomOpenAiConnectivity(Transport transport, LongSupplier clock) {
        this.transport = Objects.requireNonNull(transport);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns UNKNOWN when a server does not provide the optional models endpoint. */
    public synchronized Result check(String completionUrl, String apiKey) {
        long now = clock.getAsLong();
        long age = now - checkedAt;
        if (cached != null && age >= 0 && age < CACHE_NANOS
                && Objects.equals(cachedUrl, completionUrl) && Objects.equals(cachedKey, apiKey)) {
            return cached;
        }
        Result result = probe(completionUrl, apiKey);
        cachedUrl = completionUrl;
        cachedKey = apiKey;
        cached = result;
        checkedAt = clock.getAsLong();
        return result;
    }

    private Result probe(String completionUrl, String apiKey) {
        try {
            URI configured = URI.create(completionUrl);
            String scheme = configured.getScheme();
            String path = configured.getRawPath();
            String suffix = "/chat/completions";
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || configured.getHost() == null || configured.getUserInfo() != null
                    || path == null || !path.endsWith(suffix)) return Result.UNKNOWN;
            String query = configured.getRawQuery() == null ? "" : "?" + configured.getRawQuery();
            URI models = URI.create(scheme + "://" + configured.getRawAuthority()
                    + path.substring(0, path.length() - suffix.length()) + "/models" + query);
            HttpRequest.Builder request = HttpRequest.newBuilder(models).timeout(TIMEOUT)
                    .header("Accept", "application/json").GET();
            if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
            int status = transport.send(request.build());
            if (status >= 200 && status < 300) return Result.REACHABLE;
            if (status == 401 || status == 403) return Result.AUTHENTICATION_FAILED;
            if (status >= 500) return Result.UNREACHABLE;
            return Result.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.UNKNOWN;
        } catch (IOException | IllegalArgumentException | NullPointerException failure) {
            return failure instanceof IOException ? Result.UNREACHABLE : Result.UNKNOWN;
        }
    }
}
