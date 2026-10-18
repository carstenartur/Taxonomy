package com.taxonomy.plugins.leanix;

import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** One request-owned HTTPS session; fixed endpoints, no redirects/retries/cookies or ambient proxy. */
final class LeanIxHttp implements AutoCloseable {
    private static final byte[] AWS_METADATA_IPV6 = {
            (byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54
    };
    enum Endpoint {
        TOKEN("services/mtm/v1/oauth2/token", ContentType.APPLICATION_FORM_URLENCODED),
        GRAPHQL("services/pathfinder/v1/graphql", ContentType.APPLICATION_JSON);
        final String path; final ContentType type;
        Endpoint(String path, ContentType type) { this.path = path; this.type = type; }
    }
    private final URI origin;
    private final Limits limits;
    private final CloseableHttpClient http;
    private final ScheduledExecutorService timers;
    private long deadline;
    private int requests;
    private long bytes;

    LeanIxHttp(Request request, ReadAccess access, long startedNanos) {
        origin = request.origin(); validateOrigin(origin); limits = request.limits();
        deadline = startedNanos + TimeUnit.SECONDS.toNanos(limits.timeoutSeconds());
        try {
            final boolean privateNetworks = access.allowPrivateNetworks();
            // HttpClient may bypass its DNS resolver for literal addresses. Check those here too.
            String host = origin.getHost();
            if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
                try {
                    if (!allowed(InetAddress.getByName(host), privateNetworks)) throw new ReadFailure("NETWORK_POLICY");
                } catch (UnknownHostException invalid) { throw new ReadFailure("NETWORK_POLICY"); }
            }
            DnsResolver resolver = new DnsResolver() {
                public InetAddress[] resolve(String name) throws UnknownHostException {
                    if (!origin.getHost().equalsIgnoreCase(name)) throw new UnknownHostException("Origin mismatch");
                    InetAddress[] addresses = InetAddress.getAllByName(name);
                    if (addresses.length == 0) throw new UnknownHostException("No address");
                    for (InetAddress address : addresses) if (!allowed(address, privateNetworks)) throw new UnknownHostException("Network policy");
                    return addresses;
                }
                public String resolveCanonicalHostname(String name) throws UnknownHostException { resolve(name); return name; }
            };
            var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                    .setTlsSocketStrategy(ClientTlsStrategyBuilder.create().setSslContext(access.tls()).buildClassic())
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3))
                            .setSocketTimeout(Timeout.ofSeconds(5)).build()).setMaxConnTotal(1).setMaxConnPerRoute(1).build();
            http = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                    .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
                            .setConnectionRequestTimeout(Timeout.ofSeconds(2)).setResponseTimeout(Timeout.ofSeconds(5)).build()).build();
            timers = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "leanix-read-deadline"); t.setDaemon(true); return t;
            });
        } catch (RuntimeException e) { throw new ReadFailure("TRANSPORT_UNAVAILABLE"); }
    }
    int requests() { return requests; }
    long bytes() { return bytes; }
    void checkTime() {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new ReadFailure("READ_TIMEOUT");
    }
    void tokenLifetime(int seconds, long tokenRequestStarted) {
        if (seconds <= 1) throw new ReadFailure("TOKEN_EXPIRED");
        deadline = Math.min(deadline, tokenRequestStarted + TimeUnit.SECONDS.toNanos(Math.min(seconds - 1, 120)));
        checkTime();
    }
    byte[] post(Endpoint endpoint, String body, String authorization) {
        checkTime();
        if (requests >= limits.maxRequests() || bytes >= limits.maxTotalBytes()) throw new ReadFailure("READ_LIMIT");
        int bound = (int) Math.min(limits.maxResponseBytes(), limits.maxTotalBytes() - bytes);
        if (endpoint == Endpoint.TOKEN) bound = Math.min(bound, 32768);
        final int max = bound;
        HttpPost request = new HttpPost(origin.resolve(endpoint.path));
        request.setHeader("Accept", "application/json"); request.setHeader("Authorization", authorization);
        request.setEntity(new StringEntity(body, endpoint.type.withCharset(java.nio.charset.StandardCharsets.UTF_8)));
        var cancellation = timers.schedule(() -> request.cancel(), Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        requests++;
        try {
            byte[] content = http.execute(request, response -> {
                try {
                    int status = response.getCode();
                    if (status != 200) throw new ReadFailure(switch (status) {
                        case 401, 403 -> "UNAUTHORIZED";
                        case 429 -> "RATE_LIMITED";
                        case 301, 302, 303, 307, 308 -> "REDIRECT_REJECTED";
                        default -> "HTTP_FAILURE";
                    });
                    var media = response.getFirstHeader("Content-Type");
                    if (media == null || !media.getValue().split(";", 2)[0].strip().equalsIgnoreCase("application/json"))
                        throw new ReadFailure("MEDIA_TYPE");
                    var encoding = response.getFirstHeader("Content-Encoding");
                    if (encoding != null && !encoding.getValue().equalsIgnoreCase("identity")) throw new ReadFailure("MEDIA_TYPE");
                    var entity = response.getEntity();
                    if (entity == null || entity.getContentLength() > max) throw new ReadFailure("READ_LIMIT");
                    try (var in = entity.getContent(); var out = new ByteArrayOutputStream()) {
                        try {
                            byte[] buffer = new byte[8192]; int n;
                            while ((n = in.read(buffer, 0, Math.min(buffer.length, max - out.size() + 1))) != -1) {
                                checkTime();
                                if (out.size() + n > max) throw new ReadFailure("READ_LIMIT");
                                out.write(buffer, 0, n);
                            }
                            checkTime(); return out.toByteArray();
                        } catch (IOException | RuntimeException failure) {
                            // Abort before stream.close() can drain an oversized or interrupted body.
                            request.cancel(); throw failure;
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    // Header rejection must also abort before HttpClient closes the response.
                    request.cancel(); throw failure;
                }
            });
            bytes += content.length; checkTime(); return content;
        } catch (IOException e) { checkTime(); throw new ReadFailure("TRANSPORT_UNAVAILABLE"); }
        finally { cancellation.cancel(false); }
    }
    static void validateOrigin(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !Set.of("", "/").contains(uri.getRawPath()) || uri.toString().length() > 2048
                || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Use a configured HTTPS origin without credentials, query or application path");
    }
    static boolean allowed(InetAddress address, boolean privateNetworks) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = Byte.toUnsignedInt(b[0]), second = Byte.toUnsignedInt(b[1]);
            if (a == 0 || a >= 240 || a == 169 && second == 254) return false;
            if (a == 100 && second == 100 && Byte.toUnsignedInt(b[2]) == 100 && Byte.toUnsignedInt(b[3]) == 200) return false;
            if (!privateNetworks && (a == 100 && second >= 64 && second <= 127 || a == 198 && (second == 18 || second == 19))) return false;
        } else if (Arrays.equals(b, AWS_METADATA_IPV6) || !privateNetworks && (b[0] & 0xfe) == 0xfc) return false;
        return privateNetworks || !(address.isLoopbackAddress() || address.isSiteLocalAddress());
    }
    @Override public void close() {
        timers.shutdownNow();
        try { http.close(); } catch (IOException ignored) { /* Never retain wire diagnostics. */ }
    }
}
