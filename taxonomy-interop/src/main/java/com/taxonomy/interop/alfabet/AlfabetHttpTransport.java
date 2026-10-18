package com.taxonomy.interop.alfabet;

import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.oslc.OslcTransport;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Fixed read endpoints only. DNS policy is enforced by the actual connection manager, as in OSLC. */
final class AlfabetHttpTransport implements AutoCloseable {
    enum Endpoint {
        TOKEN("api/token", ContentType.APPLICATION_FORM_URLENCODED),
        OBJECTS("api/v2/objects", ContentType.APPLICATION_JSON);
        final String path;
        final ContentType contentType;
        Endpoint(String path, ContentType contentType) { this.path = path; this.contentType = contentType; }
    }
    private final URI base;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService cancellation = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "alfabet-read-deadline"); thread.setDaemon(true); return thread;
    });

    AlfabetHttpTransport(AlfabetReadProfile profile) {
        base = profile.baseUri();
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!base.getHost().equalsIgnoreCase(host)) throw new UnknownHostException("Origin outside read profile");
                InetAddress[] addresses = InetAddress.getAllByName(host);
                for (InetAddress address : addresses)
                    if (!OslcTransport.allowedAddress(address, profile.allowPrivateNetworks()))
                        throw new UnknownHostException("Address outside integration network policy");
                return addresses;
            }
            @Override public String resolveCanonicalHostname(String host) throws UnknownHostException { resolve(host); return host; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(5)).build()).setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2))
                        .setResponseTimeout(Timeout.ofSeconds(5)).build()).build();
    }

    byte[] post(Endpoint endpoint, String body, String token, int maxBytes, long deadline) {
        requireTime(deadline);
        if (maxBytes <= 0) throw problem("ALFABET_READ_LIMIT");
        // No API accepts a caller-supplied path, verb, query or redirect destination.
        HttpPost request = new HttpPost(base.resolve(endpoint.path));
        request.setHeader("Accept", "application/json");
        if (token != null) request.setHeader("Authorization", "Bearer " + token);
        request.setEntity(new StringEntity(body, endpoint.contentType.withCharset(java.nio.charset.StandardCharsets.UTF_8)));
        var timer = cancellation.schedule(() -> request.cancel(), Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try {
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status != 200) throw problem(switch (status) {
                    case 401, 403 -> "ALFABET_UNAUTHORIZED";
                    case 429 -> "ALFABET_RATE_LIMITED";
                    case 301, 302, 303, 307, 308 -> "ALFABET_REDIRECT_REJECTED";
                    default -> "ALFABET_UNAVAILABLE";
                });
                var type = response.getFirstHeader("Content-Type");
                if (type == null || !type.getValue().split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
                    throw problem("ALFABET_MEDIA_TYPE");
                var encoding = response.getFirstHeader("Content-Encoding");
                if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity")) throw problem("ALFABET_MEDIA_TYPE");
                var entity = response.getEntity();
                if (entity == null || entity.getContentLength() > maxBytes) throw problem("ALFABET_READ_LIMIT");
                try (var input = entity.getContent(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        requireTime(deadline);
                        if (output.size() + count > maxBytes) throw problem("ALFABET_READ_LIMIT");
                        output.write(buffer, 0, count);
                    }
                    requireTime(deadline);
                    return output.toByteArray();
                }
            });
        } catch (IOException failure) {
            requireTime(deadline);
            // The exception/cause can contain endpoint names or credential-bearing wire data. Do not retain it.
            throw problem("ALFABET_UNAVAILABLE");
        } finally { timer.cancel(false); }
    }
    static void requireTime(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw problem("ALFABET_READ_TIMEOUT");
    }
    static IntegrationProblem problem(String code) {
        return new IntegrationProblem(code, code.equals("ALFABET_READ_TIMEOUT") ? 504 : 502,
                "Alfabet read did not complete; no inventory, absence or synchronization success was recorded");
    }
    @Override public void close() {
        cancellation.shutdownNow();
        try { client.close(); } catch (IOException ignored) { /* No payload or secret-bearing diagnostics. */ }
    }
}
