package com.taxonomy.interop.sparx;

import com.taxonomy.interop.IntegrationProblem;
import java.util.function.LongSupplier;

/** One aggregate operation budget, including discovery and every feature collection. */
public final class SparxAmReadBudget {
    public record Limits(int roots, int pages, int responses, long bytes, long statements, int objects, long nanos) {
        public Limits {
            if (roots < 1 || roots > 250 || pages < 1 || pages > 20 || responses < 1 || responses > 1024
                    || bytes < 1 || bytes > 16777216 || statements < 1 || statements > 100000
                    || objects < 1 || objects > 10000 || nanos < 1 || nanos > 30000000000L)
                throw new IllegalArgumentException("Read limits must stay within the declared v2 bounds");
        }
        public static Limits defaults() { return new Limits(250, 20, 1024, 16777216, 100000, 10000, 30000000000L); }
    }
    private final Limits limits;
    private final LongSupplier clock;
    private final long start;
    private int responses;
    private final java.util.Set<String> objects = new java.util.HashSet<>();
    private long bytes, statements;
    public SparxAmReadBudget(Limits limits, LongSupplier clock) { this.limits = limits; this.clock = clock; this.start = clock.getAsLong(); }
    public void checkTime() { if (clock.getAsLong() - start >= limits.nanos()) throw failure("REMOTE_TIMEOUT"); }
    public void beforeResponse(int chainPages) {
        checkTime();
        if (chainPages >= limits.pages()) throw failure("SPARX_AM_PAGE_LIMIT");
        if (responses >= limits.responses()) throw failure("SPARX_AM_REQUEST_LIMIT");
        responses++;
    }
    public void received(long responseBytes, long rdfStatements, java.util.Set<String> identifiedObjects) {
        checkTime(); bytes += responseBytes; statements += rdfStatements; objects.addAll(identifiedObjects);
        if (bytes > limits.bytes()) throw failure("REMOTE_RESPONSE_LIMIT");
        if (statements > limits.statements() || objects.size() > limits.objects()) throw failure("ITEM_LIMIT");
    }
    public void roots(int count) { if (count > limits.roots()) throw failure("SPARX_AM_ENRICHMENT_LIMIT"); }
    public int responseCount() { return responses; }
    public long byteCount() { return bytes; }
    private static IntegrationProblem failure(String code) { return new IntegrationProblem(code, code.equals("REMOTE_TIMEOUT") ? 504 : 502, "Complete AM read exceeded its aggregate bound; no partial preview is available"); }
}
