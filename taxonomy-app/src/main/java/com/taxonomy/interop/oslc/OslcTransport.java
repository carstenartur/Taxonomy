package com.taxonomy.interop.oslc;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.*;

/** Scoped HTTPS transport with DNS validation in the actual connection path, bounded responses and no redirect/retry. */
@Component
public class OslcTransport {
    private final OslcRemoteProfiles profiles;
    private final SystemRepositoryService repositories;
    private final Environment environment;
    private final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "oslc-deadline"); thread.setDaemon(true); return thread;
    });
    @jakarta.annotation.PreDestroy public void close() { deadlines.shutdownNow(); }
    public OslcTransport(OslcRemoteProfiles profiles, SystemRepositoryService repositories, Environment environment) {
        this.profiles = profiles; this.repositories = repositories; this.environment = environment;
    }
    public record Response(URI resource, String etag, byte[] content) { public Response { content = content.clone(); } @Override public byte[] content() { return content.clone(); } }
    public URI validate(RepositoryContext context, Connection connection, String resource) {
        return target(profile(context, connection), resource);
    }
    public Response read(RepositoryContext context, Connection connection, String resource, String expectedVersion) {
        var profile = profile(context, connection); URI target = target(profile, resource);
        DnsResolver resolver = new DnsResolver() {
            @Override public InetAddress[] resolve(String hostname) throws UnknownHostException {
                if (!target.getHost().equalsIgnoreCase(hostname)) throw new UnknownHostException("Host outside configured integration origin");
                InetAddress[] addresses = InetAddress.getAllByName(hostname);
                for (InetAddress address : addresses) if (!allowedAddress(address, profile.allowPrivateNetworks())) throw new UnknownHostException("Address outside integration network policy");
                return addresses;
            }
            @Override public String resolveCanonicalHostname(String hostname) throws UnknownHostException { resolve(hostname); return hostname; }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.ofSeconds(5)).build())
                .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
        try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .disableContentCompression().setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2)).setResponseTimeout(Timeout.ofSeconds(5)).build()).build()) {
            HttpGet request = new HttpGet(target); request.setHeader("Accept", "application/rdf+xml"); request.setHeader("OSLC-Core-Version", "3.0");
            if (expectedVersion != null) {
                if (expectedVersion.length() > 2048 || expectedVersion.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid external version");
                request.setHeader("If-Match", expectedVersion);
            }
            String configuration = connection.externalScope().configuration();
            if (configuration != null) {
                URI uri = URI.create(configuration);
                if (!uri.isAbsolute() || configuration.length() > 2048 || configuration.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid configuration");
                request.setHeader("Configuration-Context", configuration);
            }
            if (profile.credentialEnvironmentVariable() != null) {
                if (!profile.credentialEnvironmentVariable().matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalStateException("Invalid credential reference");
                String token = environment.getProperty(profile.credentialEnvironmentVariable());
                if (token == null || !token.matches("[A-Za-z0-9._~+/=-]{1,8192}")) throw new IntegrationProblem("REMOTE_CREDENTIAL_UNAVAILABLE", 503, "Configured credential is unavailable");
                request.setHeader("Authorization", "Bearer " + token);
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            var cancellation = deadlines.schedule(() -> request.cancel(), 15, java.util.concurrent.TimeUnit.SECONDS);
            try { return client.execute(request, response -> {
                int code = response.getCode();
                if (code != 200) throw remoteFailure(code);
                String media = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue().split(";", 2)[0].strip();
                if (!media.equalsIgnoreCase("application/rdf+xml")) throw new IntegrationProblem("REMOTE_MEDIA_TYPE", 502, "OSLC provider did not return the requested RDF/XML representation");
                if (response.getEntity() == null || response.getEntity().getContentLength() > ExchangeXml.MAX_BYTES) throw new IntegrationProblem("REMOTE_RESPONSE_LIMIT", 502, "Remote response exceeds the supported bound");
                try (var input = response.getEntity().getContent(); var output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (System.nanoTime() > deadline) throw new IntegrationProblem("REMOTE_TIMEOUT", 504, "Remote response deadline exceeded");
                        if (output.size() + count > ExchangeXml.MAX_BYTES) throw new IntegrationProblem("REMOTE_RESPONSE_LIMIT", 502, "Remote response exceeds the supported bound");
                        output.write(buffer, 0, count);
                    }
                    String etag = response.getFirstHeader("ETag") == null ? null : response.getFirstHeader("ETag").getValue();
                    if (etag != null && (etag.length() > 2048 || etag.chars().anyMatch(Character::isISOControl))) throw new IntegrationProblem("REMOTE_VERSION_INVALID", 502, "Invalid remote version metadata");
                    return new Response(target, etag, output.toByteArray());
                }
            }); } finally { cancellation.cancel(false); }
        } catch (SocketTimeoutException failure) { throw new IntegrationProblem("REMOTE_TIMEOUT", 504, "Remote request timed out"); }
        catch (IOException failure) { throw new IntegrationProblem("REMOTE_UNAVAILABLE", 502, "Remote resource is unavailable under the configured network policy"); }
    }
    private OslcRemoteProfiles.RemoteProfile profile(RepositoryContext context, Connection connection) {
        var value = connection.remoteProfile() == null ? null : profiles.getRemotes().get(connection.remoteProfile());
        var repository = repositories.getRepository(context.repositoryId());
        String owner = repository.getOwnerType() + ":" + repository.getOwnerId();
        if (value == null || !context.repositoryId().equals(value.repositoryId()) || !owner.equals(value.organizationId()) || !owner.equals(connection.organizationId())) throw IntegrationProblem.missing();
        return value;
    }
    public static URI target(OslcRemoteProfiles.RemoteProfile profile, String resource) {
        URI base = profile.baseUri();
        if (base == null || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null
                || !base.getPath().endsWith("/") || !(base.getScheme().equals("https") || profile.allowInsecureHttp() && base.getScheme().equals("http")))
            throw new IntegrationProblem("REMOTE_PROFILE_INVALID", 503, "Configured endpoint must define an explicit origin and path boundary");
        URI uri;
        try { uri = resource == null || resource.isBlank() ? base : base.resolve(URI.create(resource)); }
        catch (IllegalArgumentException invalid) { throw new IntegrationProblem("REMOTE_URI_REJECTED", 400, "Invalid remote resource identity"); }
        String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath().toLowerCase(Locale.ROOT);
        if (!Objects.equals(uri.getScheme(), base.getScheme()) || !Objects.equals(uri.getHost(), base.getHost()) || port(uri) != port(base)
                || uri.getUserInfo() != null || uri.getFragment() != null || !uri.normalize().getPath().startsWith(base.getPath())
                || rawPath.contains("%2e") || rawPath.contains("%2f") || rawPath.contains("%5c") || rawPath.contains("%25") || rawPath.contains("\\")
                || uri.getPath().chars().anyMatch(Character::isISOControl) || uri.toString().length() > 2048)
            throw new IntegrationProblem("REMOTE_URI_REJECTED", 400, "Resource is outside the configured integration boundary");
        if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&")) {
            String name = URLDecoder.decode(parameter.split("=", 2)[0], java.nio.charset.StandardCharsets.UTF_8);
            if (!Set.of("oslc.where", "oslc.select", "oslc.searchTerms", "oslc.prefix", "oslc.orderBy", "oslc.paging", "oslc.pageSize", "page", "pageToken", "configuration", "oslc_config.context", "repositoryId", "workspaceId", "branch").contains(name))
                throw new IntegrationProblem("REMOTE_QUERY_REJECTED", 400, "Remote query parameter is outside the declared OSLC profile");
        }
        return uri.normalize();
    }
    private static int port(URI uri) { return uri.getPort() < 0 ? uri.getScheme().equals("https") ? 443 : 80 : uri.getPort(); }
    public static boolean allowedAddress(InetAddress address, boolean privateNetworks) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
            if (a == 0 || a >= 240 || a == 169 && b == 254) return false;
            if (!privateNetworks && (a == 100 && b >= 64 && b <= 127 || a == 198 && (b == 18 || b == 19))) return false;
        } else if (!privateNetworks && (bytes[0] & 0xfe) == 0xfc) return false;
        return privateNetworks || !(address.isLoopbackAddress() || address.isSiteLocalAddress());
    }
    private static IntegrationProblem remoteFailure(int status) {
        String code = switch (status) { case 401, 403 -> "REMOTE_UNAUTHORIZED"; case 404, 410 -> "REMOTE_MISSING"; case 412 -> "REMOTE_STALE"; case 429 -> "REMOTE_RATE_LIMITED"; case 301, 302, 303, 307, 308 -> "REMOTE_MOVED"; default -> "REMOTE_UNAVAILABLE"; };
        return new IntegrationProblem(code, status == 412 ? 409 : status == 429 ? 429 : 502, "Remote request was not accepted; no synchronization success was recorded");
    }
}
