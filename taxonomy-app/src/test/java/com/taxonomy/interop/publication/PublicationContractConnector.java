package com.taxonomy.interop.publication;

import com.taxonomy.extension.api.integration.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationJson;
import tools.jackson.databind.json.JsonMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/**
 * Explicit insecure-loopback test transport; no redirects, bounded response and request deadline. Never a production connector.
 */
public final class PublicationContractConnector implements ConditionalPublicationConnector {

    volatile PublicationContractProvider provider;
    private volatile java.net.URI endpoint;
    volatile String fixtureCredential;
    volatile Runnable afterResponse = () -> {};
    public void endpoint(java.net.URI value) {
        if (!"http".equals(value.getScheme()) || !"127.0.0.1".equals(value.getHost()) || value.getPort() < 1 || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null || !(value.getPath().isEmpty() || value.getPath().equals("/"))) throw new IllegalArgumentException("Only an explicit credential-free loopback test endpoint is permitted");
        endpoint = value;
    }


    final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());

    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();

    public IntegrationDescriptor descriptor() {
        return new IntegrationDescriptor(PublicationContractProvider.PROFILE, "1", "HTTP contract test", Set.of(Capability.ARCHITECTURE_MODEL, Capability.READ_LINK), Set.of("application/json"));
    }

    public ExchangeDocument previewInbound(InboundRequest r) {
        throw new UnsupportedOperationException();
    }

    public ExchangeFile previewOutbound(OutboundRequest r) {
        throw new UnsupportedOperationException();
    }

    public void validateInboundSelection(OutboundRequest r) {
        document(r.document());
    }

    public PublicationCapabilities publicationCapabilities(IntegrationContext c, PublicationScope s) {
        return PublicationContractProvider.CAPS;
    }

    public ScopeSnapshot readPublicationScope(IntegrationContext c, PublicationScope s, String revision) {
        return call("/scope", null, ScopeSnapshot.class);
    }

    public PublicationReceipt publishItem(IntegrationContext c, PublicationItemRequest r) {
        return call("/item", r, PublicationReceipt.class);
    }

    public PublicationReceiptLookup lookupPublicationReceipt(IntegrationContext c, PublicationReceiptQuery q) {
        return call("/receipt", q, PublicationReceiptLookup.class);
    }

    <T> T call(String path, Object body, Class<T> type) {
        org.junit.jupiter.api.Assertions.assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(), "HTTP must run outside database/workspace transactions");
        try {
            var builder = HttpRequest.newBuilder((endpoint == null ? provider.base() : endpoint).resolve(path)).timeout(Duration.ofSeconds(5));
            if (fixtureCredential != null) builder.header("Authorization", "Bearer " + fixtureCredential);
            if (body != null) {
                builder.POST(HttpRequest.BodyPublishers.ofString(json.write(body)));
            } else {
                builder.GET();
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("CONTRACT_TRANSPORT");
                }
                byte[] bytes = stream.readNBytes(MAX_DOCUMENT_BYTES + 1);
                if (bytes.length > MAX_DOCUMENT_BYTES) {
                    throw new IllegalStateException("CONTRACT_LIMIT");
                }
                String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (fixtureCredential != null && (text.contains(fixtureCredential) || response.headers().map().values().stream().flatMap(java.util.Collection::stream).anyMatch(v -> v.contains(fixtureCredential)))) throw new IllegalStateException("CONTRACT_REFLECTION");
                T result = json.read(text, type);
                if (path.equals("/item") || path.equals("/receipt")) afterResponse.run();
                return result;
            }
        } catch (Exception failure) {
            throw new IllegalStateException("CONTRACT_TRANSPORT");
        }
    }
}
