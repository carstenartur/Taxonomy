package com.taxonomy.exchange.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;

/** EA XMI 2.1 semantic subset. Parsing and writing share the transport-neutral exchange values. */
public final class SparxXmiCodec {
    public ExchangeDocument read(byte[] content, String version, boolean completeScope) {
        return new SparxXmiReader().read(content, version, completeScope);
    }
    public byte[] write(ExchangeDocument document) { return new SparxXmiWriter().write(document); }
}
