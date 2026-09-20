package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.sparx.SparxOslcAmCodec;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxAmReadEvidence;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.oslc.OslcTransport;
import com.taxonomy.interop.persistence.IntegrationStore.Connection;
import com.taxonomy.workspace.service.RepositoryContext;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Read the complete bounded AM query chain; a failed page never produces a partial preview. */
public final class SparxOslcAmReader {
    private final OslcTransport transport;
    private final SparxOslcAmCodec codec = new SparxOslcAmCodec();
    private final SparxAmReadBudget.Limits limits;
    private final java.util.function.LongSupplier clock;
    public SparxOslcAmReader(OslcTransport transport) { this(transport, SparxAmReadBudget.Limits.defaults(), System::nanoTime); }
    public SparxOslcAmReader(OslcTransport transport, SparxAmReadBudget.Limits limits, java.util.function.LongSupplier clock) {
        this.transport = transport; this.limits = limits; this.clock = clock;
    }

    public URI validate(RepositoryContext context, Connection connection, String resource) {
        URI base = transport.validate(context, connection, null);
        if (!base.getPath().endsWith("/oslc/am/") || !base.toString().equals(connection.externalScope().repository())
                || connection.externalScope().configuration() != null)
            throw new IntegrationProblem("SPARX_AM_SCOPE", 400, "PCS model scope must equal the configured /oslc/am/ boundary, without a configuration context");
        URI uri = transport.validate(context, connection, resource == null || resource.isBlank() ? "sp/" : resource);
        boolean discovery = uri.getPath().equals(base.getPath() + "sp/");
        if (!discovery && !uri.getPath().equals(base.getPath() + "qc/"))
            throw new IntegrationProblem("SPARX_AM_RESOURCE", 400, "Choose model service discovery or an unfiltered AM query");
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String name = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8);
            if (discovery || !Set.of("oslc.paging", "oslc.pageSize", "page", "pageToken").contains(name))
                throw new IntegrationProblem("SPARX_AM_QUERY", 400, "Filtered or projected AM queries cannot establish a reviewable model scope");
        }
        return uri;
    }

    public ExchangeDocument read(RepositoryContext context, Connection connection, String resource, String version) {
        if ("2".equals(connection.profileVersion())) return readV2(context, connection, resource, version);
        if (!"1".equals(connection.profileVersion())) throw IntegrationProblem.conflict("PROFILE_VERSION_CHANGED");
        URI next = validate(context, connection, resource);
        URI base = transport.validate(context, connection, null);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        long bytes = 0;
        if (next.getPath().endsWith("/sp/")) {
            var discovery = transport.readPcs(context, connection, next.toString());
            bytes += discovery.content().length;
            next = validate(context, connection, codec.queryBase(discovery.content(), discovery.resource()).toString());
            if (!next.getPath().endsWith("/qc/")) throw new IntegrationProblem("SPARX_AM_DISCOVERY", 502, "Discovery must identify the model query capability");
        }
        Set<URI> visited = new HashSet<>();
        List<SparxOslcAmCodec.Page> pages = new ArrayList<>();
        while (next != null) {
            next = validate(context, connection, next.toString());
            if (!next.getPath().endsWith("/qc/") || !visited.add(next) || pages.size() >= SparxOslcAmCodec.MAX_PAGES)
                throw new IntegrationProblem("SPARX_AM_PAGE_LIMIT", 502, "AM query has a loop or exceeds the supported page bound");
            if (System.nanoTime() >= deadline) throw new IntegrationProblem("REMOTE_TIMEOUT", 504, "AM collection deadline exceeded");
            var response = transport.readPcs(context, connection, next.toString());
            if (System.nanoTime() >= deadline) throw new IntegrationProblem("REMOTE_TIMEOUT", 504, "AM collection deadline exceeded");
            if ((bytes += response.content().length) > ExchangeXml.MAX_BYTES)
                throw new IntegrationProblem("REMOTE_RESPONSE_LIMIT", 502, "AM collection exceeds the supported bound");
            pages.add(new SparxOslcAmCodec.Page(response.resource(), response.etag(), response.content()));
            next = codec.nextPage(response.content(), response.resource());
        }
        ExchangeDocument document = codec.read(pages, base);
        if (version != null && !version.equals(document.externalVersion()))
            throw new IntegrationProblem("REMOTE_STALE", 409, "AM collection changed; fetch and review a fresh preview");
        return document;
    }
    private ExchangeDocument readV2(RepositoryContext context, Connection connection, String resource, String expectedVersion) {
        var budget = new SparxAmReadBudget(limits, clock);
        var evidence = new SparxAmReadEvidence();
        var codec = new SparxOslcAmCodec("2");
        URI first = validate(context, connection, resource), base = transport.validate(context, connection, null);
        if (first.getPath().endsWith("/sp/")) {
            var discovery = fetch(context, connection, budget, first, "sp", "ROOT", 0, evidence);
            first = validate(context, connection, codec.queryBase(discovery.content(), discovery.resource()).toString());
            if (!first.getPath().endsWith("/qc/")) throw new IntegrationProblem("SPARX_AM_DISCOVERY", 502, "Discovery must identify the model query capability");
        }
        var roots = collectChain(context, connection, budget, first, "qc", "ROOT", evidence);
        var identifiers = codec.rootIdentifiers(roots, base); budget.roots(identifiers.size());
        List<SparxOslcAmCodec.Collection> collections = new ArrayList<>();
        for (String root : identifiers) {
            collectFeature(context, connection, budget, base, "linkedresources", root, collections, evidence);
            collectFeature(context, connection, budget, base, "taggedvalues", root, collections, evidence);
            if (root.startsWith("el_")) {
                var attributes = collectFeature(context, connection, budget, base, "attributes", root, collections, evidence);
                for (String attribute : codec.featureIdentifiers(attributes, base))
                    collectFeature(context, connection, budget, base, "taggedvalues", attribute, collections, evidence);
                var operations = collectFeature(context, connection, budget, base, "operations", root, collections, evidence);
                for (String operation : codec.featureIdentifiers(operations, base)) {
                    collectFeature(context, connection, budget, base, "taggedvalues", operation, collections, evidence);
                    collectFeature(context, connection, budget, base, "parameters", operation, collections, evidence);
                }
            }
        }
        var parsed = codec.read(roots, collections, base); budget.checkTime();
        String version = evidence.version();
        if (expectedVersion != null && !expectedVersion.equals(version)) throw IntegrationProblem.conflict("REMOTE_STALE");
        Map<String,String> metadata = new TreeMap<>(parsed.metadata());
        metadata.put("responseCount", Integer.toString(budget.responseCount())); metadata.put("byteCount", Long.toString(budget.byteCount()));
        return new ExchangeDocument(parsed.profile(), "2", version, false, "", parsed.artifacts(), parsed.relations(), parsed.placements(), metadata, parsed.losses());
    }
    private SparxOslcAmCodec.Collection collectFeature(RepositoryContext context, Connection connection, SparxAmReadBudget budget,
            URI base, String kind, String owner, List<SparxOslcAmCodec.Collection> collections, SparxAmReadEvidence evidence) {
        SparxMappingProfile.prefixedGuid(owner, ownerPrefixes(kind));
        URI first = base.resolve(kind + "/" + owner.replace("{", "%7B").replace("}", "%7D") + "/");
        var collection = new SparxOslcAmCodec.Collection(kind, owner, collectChain(context, connection, budget, first, kind, owner, evidence));
        collections.add(collection); return collection;
    }
    private List<SparxOslcAmCodec.Page> collectChain(RepositoryContext context, Connection connection, SparxAmReadBudget budget,
            URI first, String kind, String owner, SparxAmReadEvidence evidence) {
        Set<URI> visited = new HashSet<>(); List<SparxOslcAmCodec.Page> pages = new ArrayList<>(); URI next = first;
        while (next != null) {
            URI validated = kind.equals("qc") ? validate(context, connection, next.toString()) : validateFeature(context, connection, next, kind, owner);
            if (!first.getPath().equals(validated.getPath())) throw new IntegrationProblem("SPARX_AM_PAGING", 502, "Collection paging must retain its exact endpoint");
            if (!visited.add(validated)) throw new IntegrationProblem("SPARX_AM_PAGE_LIMIT", 502, "AM collection paging loop");
            var response = fetch(context, connection, budget, validated, kind, owner, pages.size(), evidence);
            pages.add(response); next = codec.nextPage(response.content(), response.resource());
        }
        return List.copyOf(pages);
    }
    private SparxOslcAmCodec.Page fetch(RepositoryContext context, Connection connection, SparxAmReadBudget budget,
            URI uri, String kind, String owner, int chainPages, SparxAmReadEvidence evidence) {
        budget.beforeResponse(chainPages);
        var response = transport.readPcs(context, connection, uri.toString());
        budget.received(response.content().length, codec.statementCount(response.content(), response.resource()), kind.equals("sp") ? Set.of() : codec.objectIdentities(response.content(), response.resource()));
        var page = new SparxOslcAmCodec.Page(response.resource(), response.etag(), response.content());
        evidence.add(kind, owner.equals("ROOT") ? owner : SparxMappingProfile.prefixedGuid(owner, Set.of("pk_", "el_", "at_", "op_")), page);
        return page;
    }
    private URI validateFeature(RepositoryContext context, Connection connection, URI resource, String kind, String owner) {
        SparxMappingProfile.prefixedGuid(owner, ownerPrefixes(kind));
        URI base = transport.validate(context, connection, null), uri = transport.validate(context, connection, resource.toString());
        if (!uri.getPath().equals(base.getPath() + kind + "/" + owner + "/"))
            throw new IntegrationProblem("SPARX_AM_RESOURCE", 400, "Feature page must stay on its validated owner endpoint");
        if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
            String name = URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8);
            if (!Set.of("oslc.paging", "oslc.pageSize", "page", "pageToken").contains(name))
                throw new IntegrationProblem("SPARX_AM_QUERY", 400, "Feature collection accepts only bounded paging parameters");
        }
        return uri;
    }
    private static Set<String> ownerPrefixes(String kind) {
        return switch (kind) {
            case "linkedresources" -> Set.of("pk_", "el_"); case "taggedvalues" -> Set.of("pk_", "el_", "at_", "op_");
            case "attributes", "operations" -> Set.of("el_"); case "parameters" -> Set.of("op_");
            default -> throw new IntegrationProblem("SPARX_AM_RESOURCE", 400, "Unsupported feature endpoint");
        };
    }

}
