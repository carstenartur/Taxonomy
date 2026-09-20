package com.taxonomy.interop.publication;

import com.sun.net.httpserver.*;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.taxonomy.extension.api.integration.PublicationBounds.*;

/**
 * Test-only HTTP contract. One atomic file replacement commits scope and durable key receipts together.
 */
public final class PublicationContractProvider implements AutoCloseable {

    public static final String PROFILE = "taxonomy-publication-contract-v1";

    static final ProviderIdentity PROVIDER = new ProviderIdentity("contract-fixture", "repository", "immutable-config");

    public static final PublicationScope SCOPE = new PublicationScope(new ExternalScope("CONTRACT", "repository", "config"), "urn:contract:scope", "all");

    public static final PublicationCapabilities CAPS = new PublicationCapabilities(1, CONTRACT_VERSION, PROVIDER, SCOPE, Set.of(Guarantee.values()), Set.of(MutationKind.values()), Set.of(ArtifactKind.values()), "test-contract-evidence", "test-capabilities-v1", MAX_MUTATIONS, MAX_ITEM_BYTES, 86400);

    public record Durable(long revision, Map<String, Artifact> artifacts, Map<String, ResourceState> resources, Map<String, PublicationReceipt> receipts, Map<String, Integer> mutations) {
    }

    final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());

    final PublicationDigests digests = new PublicationDigests(json);

    final HttpServer server;

    final Path file;

    Durable data;

    final AtomicInteger writes = new AtomicInteger(), lookups = new AtomicInteger(), reads = new AtomicInteger();

    volatile int staleAt, closeAt, malformedAt, noEffectAt, failReadAt;

    volatile LookupState lookupState;
    volatile int responseStatus;
    volatile boolean oversizedResponse, foreignReceipt;
    volatile String reflectedCredential;
    public void closeAfterWrite(int ordinal) { closeAt = ordinal; }
    public synchronized String revision() { return snapshot().revision(); }
    public synchronized void seedDocument(ExchangeDocument document) throws Exception { seed(document); }


    volatile Runnable beforeWrite = () -> {
    }, afterCommit = () -> {
    }, beforeRead = () -> {
    };

    final List<PublicationItemRequest> requests = new CopyOnWriteArrayList<>();

    public PublicationContractProvider(Path file) throws Exception {
        this.file = file;
        data = Files.exists(file) ? json.read(Files.readString(file), Durable.class) : new Durable(0, Map.of(), Map.of(), Map.of(), Map.of());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/scope", e -> {
            int number = reads.incrementAndGet();
            beforeRead.run();
            if (number == failReadAt) {
                e.sendResponseHeaders(503, -1);
                e.close();
                return;
            }
            respond(e, snapshot());
        });
        server.createContext("/receipt", e -> {
            lookups.incrementAndGet();
            if (lookupState != null) {
                respond(e, new PublicationReceiptLookup(lookupState, null));
                return;
            }
            var q = json.read(new String(e.getRequestBody().readNBytes(MAX_ITEM_BYTES)), PublicationReceiptQuery.class);
            PublicationReceipt receipt;
            synchronized (this) {
                receipt = data.receipts().get(q.idempotencyKey());
            }
            respond(e, receipt == null ? new PublicationReceiptLookup(LookupState.NOT_FOUND, null) : new PublicationReceiptLookup(LookupState.FOUND, receipt));
        });
        server.createContext("/item", e -> {
            int number = writes.incrementAndGet();
            var request = json.read(new String(e.getRequestBody().readNBytes(MAX_ITEM_BYTES)), PublicationItemRequest.class);
            requests.add(request);
            beforeWrite.run();
            PublicationReceipt receipt;
            synchronized (this) {
                if (number == staleAt) {
                    externalChange();
                }
                try {
                    receipt = mutate(request);
                } catch (IllegalArgumentException reused) {
                    e.sendResponseHeaders(409, -1);
                    e.close();
                    return;
                }
            }
            afterCommit.run();
            if (number == closeAt) {
                e.close();
                return;
            }
            if (number == malformedAt) {
                respond(e, Map.of("unexpected", "receipt"));
                return;
            }
            if (responseStatus != 0) {
                if (responseStatus == 302) e.getResponseHeaders().set("Location", base().resolve("/redirect-target").toString());
                e.sendResponseHeaders(responseStatus, -1); e.close(); return;
            }
            if (oversizedResponse) {
                e.sendResponseHeaders(200, MAX_DOCUMENT_BYTES + 1L);
                try { e.getResponseBody().write(new byte[MAX_DOCUMENT_BYTES + 1]); } finally { e.close(); }
                return;
            }
            if (reflectedCredential != null) {
                e.getResponseHeaders().set("X-Fixture-Reflection", reflectedCredential);
                respond(e, Map.of("error", reflectedCredential, "version", reflectedCredential)); return;
            }
            if (foreignReceipt) receipt = new PublicationReceipt(receipt.schemaVersion(), receipt.contractVersion(), new ProviderIdentity("foreign", "repository", "configuration"), receipt.scope(), receipt.operationId(), receipt.itemId(), receipt.idempotencyKey(), receipt.planFingerprint(), receipt.requestFingerprint(), receipt.state(), receipt.receiptSequence(), receipt.terminal(), receipt.beforeScopeRevision(), receipt.afterScopeRevision(), receipt.resultingResource(), receipt.failureCode(), receipt.recordedAt());
            respond(e, receipt);
        });
        server.start();
    }

    public URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    synchronized ScopeSnapshot snapshot() {
        var doc = new ExchangeDocument(PROFILE, "1", "scope-" + data.revision(), true, "", List.of(), List.of(), List.of(), Map.of(), List.of());
        doc = ExchangeItems.expand(doc, data.artifacts());
        return new ScopeSnapshot(1, PROVIDER, SCOPE, "scope-" + data.revision(), digests.semantic(doc), true, doc, data.resources());
    }

    synchronized void seed(ExchangeDocument document) throws Exception {
        var artifacts = PublicationDigests.items(document);
        var resources = new TreeMap<String, ResourceState>();
        long revision = data.revision() + 1;
        artifacts.values().forEach(a -> resources.put(a.id(), new ResourceState(a.id(), true, "resource-" + revision, digests.semantic(a))));
        save(new Durable(revision, artifacts, resources, data.receipts(), data.mutations()));
    }

    synchronized void externalChange() throws java.io.IOException {
        save(new Durable(data.revision() + 1, data.artifacts(), data.resources(), data.receipts(), data.mutations()));
    }

    synchronized PublicationReceipt mutate(PublicationItemRequest r) throws java.io.IOException {
        if (!r.provider().equals(PROVIDER) || !r.scope().equals(SCOPE) || !r.requestFingerprint().equals(digests.requestFingerprint(r))) {
            throw new IllegalArgumentException();
        }
        var previous = data.receipts().get(r.item().idempotencyKey());
        if (previous != null) {
            if (!previous.requestFingerprint().equals(r.requestFingerprint())) {
                throw new IllegalArgumentException();
            }
            if (previous.terminal()) {
                return previous;
            }
        }
        var current = data.resources().getOrDefault(r.item().resourceId(), new ResourceState(r.item().resourceId(), false, null, null));
        boolean noEffect = noEffectAt > 0 && writes.get() == noEffectAt;
        boolean applied = !noEffect && r.expectedScopeRevision().equals("scope-" + data.revision()) && current.equals(r.item().expectedResource()) && (r.item().mutation() != MutationKind.CREATE || !current.exists());
        var artifacts = new TreeMap<>(data.artifacts());
        var resources = new TreeMap<>(data.resources());
        var mutations = new TreeMap<>(data.mutations());
        long revision = data.revision();
        ResourceState result = r.item().expectedResource();
        if (applied) {
            revision++;
            if (r.item().mutation() == MutationKind.DELETE) {
                artifacts.values().removeIf(a -> a.id().equals(r.item().resourceId()));
                result = new ResourceState(r.item().resourceId(), false, null, null);
            } else {
                artifacts.put(ExchangeItems.key(r.item().target()), r.item().target());
                result = new ResourceState(r.item().resourceId(), true, "resource-" + revision, digests.semantic(r.item().target()));
            }
            resources.put(r.item().resourceId(), result);
            mutations.merge(r.item().resourceId(), 1, Integer::sum);
        }
        var receipt = new PublicationReceipt(1, CONTRACT_VERSION, PROVIDER, SCOPE, r.operationId(), r.item().itemId(), r.item().idempotencyKey(), r.planFingerprint(), r.requestFingerprint(), applied ? ReceiptState.APPLIED : noEffect ? ReceiptState.RETRYABLE_NO_EFFECT : ReceiptState.REJECTED_STALE, previous == null ? 1 : previous.receiptSequence() + 1, !noEffect, r.expectedScopeRevision(), applied ? "scope-" + revision : r.expectedScopeRevision(), result, applied ? null : noEffect ? "TRY_LATER" : "STALE_SCOPE", Instant.now());
        var receipts = new TreeMap<>(data.receipts());
        receipts.put(r.item().idempotencyKey(), receipt);
        save(new Durable(revision, artifacts, resources, receipts, mutations));
        return receipt;
    }

    private void save(Durable next) throws java.io.IOException {
        Path pending = file.resolveSibling(file.getFileName() + ".next");
        byte[] encoded = json.write(next).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(encoded); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        data = next;
    }

    synchronized int mutationCount(String id) {
        return data.mutations().getOrDefault(id, 0);
    }

    private void respond(HttpExchange e, Object result) throws java.io.IOException {
        byte[] bytes = json.write(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(200, bytes.length);
        e.getResponseBody().write(bytes);
        e.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
