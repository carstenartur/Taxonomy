package com.taxonomy.reformulation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReformulationContractTest {
    private final ReformulationBaseline.Scope scope = new ReformulationBaseline.Scope("repo","workspace","main",1,2);
    private ReformulationBaseline.Source source(ReformulationBaseline.Scope s) {
        return new ReformulationBaseline.Source(s,3,"  Ausschließlich Terminal.\n2 Sekunden.  ");
    }
    private ReformulationBaseline.Snapshot snapshot(ReformulationBaseline.Scope s,long version) {
        return new ReformulationBaseline.Snapshot(s,"snapshot",version,"{\"tree\":\"historical description\"}");
    }
    @Test void freezesExactWhitespaceSourceVersionAndDefensivelyCopiesContext() {
        var context = new HashMap<String,String>(); context.put("catalogue","description content");
        var baseline = ReformulationBaseline.freeze(source(scope),snapshot(scope,3),context,"de","v1");
        context.put("catalogue","modified after freeze");
        assertEquals("  Ausschließlich Terminal.\n2 Sekunden.  ",baseline.originalText());
        assertEquals(3,baseline.sourceVersionId());
        assertEquals("description content",baseline.frozenContext().get("catalogue"));
        assertThrows(UnsupportedOperationException.class,()->baseline.frozenContext().put("catalogue","attack"));
        assertEquals("fd85e9275ce5c38068ef5ac21ade0d2f46ae21db0c093e2f5f2c4bc0b90c07ef",baseline.originalTextHash());
    }
    @Test void rejectsDifferentSnapshotVersionOrTenantEvenWhenIdsCollide() {
        assertThrows(IllegalArgumentException.class,()->ReformulationBaseline.freeze(source(scope),snapshot(scope,4),Map.of(),"de","v1"));
        for(var foreign : List.of(new ReformulationBaseline.Scope("other","workspace","main",1,2),
                new ReformulationBaseline.Scope("repo","other","main",1,2),
                new ReformulationBaseline.Scope("repo","workspace","other",1,2),
                new ReformulationBaseline.Scope("repo","workspace","main",9,2),
                new ReformulationBaseline.Scope("repo","workspace","main",1,9))) {
            assertThrows(IllegalArgumentException.class,()->ReformulationBaseline.freeze(source(scope),snapshot(foreign,3),Map.of(),"de","v1"));
        }
    }
    @Test void rejectsMissingAuthorityAndUnknownLanguage() {
        assertThrows(IllegalArgumentException.class,()->new ReformulationBaseline.Scope("","workspace","main",1,2));
        assertThrows(IllegalArgumentException.class,()->new ReformulationBaseline.Scope("repo","workspace","",1,2));
        assertThrows(IllegalArgumentException.class,()->ReformulationBaseline.freeze(source(scope),snapshot(scope,3),Map.of(),"xx","v1"));
    }
}
