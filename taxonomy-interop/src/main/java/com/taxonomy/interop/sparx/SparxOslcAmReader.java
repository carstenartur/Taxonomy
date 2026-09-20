package com.taxonomy.interop.sparx;

import com.taxonomy.exchange.ExchangeXml;
import com.taxonomy.exchange.sparx.SparxOslcAmCodec;
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
    public SparxOslcAmReader(OslcTransport transport) { this.transport = transport; }

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
}
