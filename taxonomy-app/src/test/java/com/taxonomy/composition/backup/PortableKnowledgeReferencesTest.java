package com.taxonomy.composition.backup;

import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.backup.SourceRecordId;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Authored identifiers isolate the reference protocol from any particular shipped catalogue. */
class PortableKnowledgeReferencesTest {
    @Test void closesTypedCurrentDraftEvidenceAndIgnoresDiagnosticDisplayLabels()throws Exception {
        var refs=new PortableKnowledgeReferences();
        refs.payload(PortableRows.json().readTree("""
            {"scores":{"FIXTURE-A":70},"reasons":{"FIXTURE-A":"because"},"evaluatedNodes":["FIXTURE-B"],
             "architectureView":{"includedElements":[{"nodeCode":"FIXTURE-C","parentNodeCode":"FIXTURE-ROOT"}]},
             "provisionalRelations":[{"hypothesisId":17,"sourceCode":"FIXTURE-A","targetCode":"FIXTURE-D"}],
             "diagnostics":{"nodeCode":"DISPLAY LABEL"}}
            """));
        assertThat(refs.codes).containsExactly("FIXTURE-A","FIXTURE-B","FIXTURE-C","FIXTURE-D","FIXTURE-ROOT");
        assertThat(refs.hypotheses).containsExactly(new SourceRecordId("knowledge.hypothesis","17"));
    }
    @Test void malformedCodeAndHypothesisContractsFailClosed() {
        var refs=new PortableKnowledgeReferences();
        assertThatThrownBy(()->refs.payload(PortableRows.json().readTree("{\"scores\":[]}"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->refs.payload(PortableRows.json().readTree("{\"evaluatedNodes\":[17]}"))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(()->refs.payload(PortableRows.json().readTree("{\"provisionalRelations\":[{\"hypothesisId\":0}]}"))).isInstanceOf(java.io.IOException.class);
    }
}
