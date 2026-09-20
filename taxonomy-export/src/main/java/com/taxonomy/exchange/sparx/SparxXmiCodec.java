package com.taxonomy.exchange.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.ExchangeDocument;

/** EA XMI 2.1 semantic subset. Parsing and writing share the transport-neutral exchange values. */
public final class SparxXmiCodec {
    private final String profileVersion;
    public SparxXmiCodec() { this("1"); }
    public SparxXmiCodec(String version) { profileVersion = SparxMappingProfile.version(version); }
    public ExchangeDocument read(byte[] content, String version, boolean completeScope) {
        return profileVersion.equals("2") ? new SparxXmiV2Reader().read(content, version, completeScope) : new SparxXmiReader().read(content, version, completeScope);
    }
    public byte[] write(ExchangeDocument document) { return new SparxXmiWriter(profileVersion).write(document); }
}
