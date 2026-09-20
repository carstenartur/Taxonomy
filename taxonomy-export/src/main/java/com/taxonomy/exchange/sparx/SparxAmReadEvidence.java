package com.taxonomy.exchange.sparx;

import com.taxonomy.exchange.ReqifExchangeCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Complete sanitized read fingerprint. Callers pass only secured transport responses. */
public final class SparxAmReadEvidence {
    private record Key(String kind, String owner, String uri) {}
    private final SortedMap<Key,String> records = new TreeMap<>(Comparator.comparing(Key::kind).thenComparing(Key::owner).thenComparing(Key::uri));
    public void add(String kind, String owner, SparxOslcAmCodec.Page page) {
        Key key = new Key(kind, owner, page.resource().normalize().toASCIIString());
        String value = field(page.etag() == null ? "<none>" : page.etag()) + field(ReqifExchangeCodec.digest(page.content()));
        if (records.putIfAbsent(key, value) != null)
            throw com.taxonomy.exchange.ExchangeXml.invalid("DUPLICATE_IDENTITY", "Duplicate collection evidence");
    }
    public String version() {
        StringBuilder value = new StringBuilder(); records.forEach((key, evidence) -> value.append(field(key.kind())).append(field(key.owner())).append(field(key.uri())).append(field(evidence)));
        return ReqifExchangeCodec.digest(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String field(String value) { return value.length() + ":" + value; }
}
