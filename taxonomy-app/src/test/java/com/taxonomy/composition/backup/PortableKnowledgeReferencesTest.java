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
    @Test void hypothesesShareOneBoundAcrossAllVisitedDocumentsAndPayloads()throws Exception {
        var refs=new PortableKnowledgeReferences();
        var payload=PortableRows.json().createObjectNode();var hypothesis=payload.putArray("provisionalRelations").addObject();
        for(long id=1;id<=100_000;id++) {
            if(id%2==0){hypothesis.put("hypothesisId",id);refs.payload(payload);}
            else refs.hypothesis(Long.toString(id));
        }
        hypothesis.put("hypothesisId",1);refs.payload(payload);
        refs.hypothesis("2");
        assertThat(refs.hypotheses).hasSize(100_000);
        assertThatThrownBy(()->refs.hypothesis("100001")).isInstanceOf(java.io.IOException.class)
                .hasMessage("Hypothesis reference limit exceeded");
        hypothesis.put("hypothesisId",100_001);
        assertThatThrownBy(()->refs.payload(payload)).isInstanceOf(java.io.IOException.class)
                .hasMessage("Hypothesis reference limit exceeded");
        assertThat(refs.hypotheses).hasSize(100_000);
    }
    @Test void documentHypothesesUseExactPositiveLongIdentities()throws Exception {
        var refs=new PortableKnowledgeReferences();
        refs.hypothesis(Long.toString(Long.MAX_VALUE));
        assertThat(refs.hypotheses).containsExactly(new SourceRecordId("knowledge.hypothesis","9223372036854775807"));
        for(String invalid:java.util.List.of("","0","01","-1","+1"," 1","1 ","1e3","9223372036854775808","100000000000000000000"))
            assertThatThrownBy(()->refs.hypothesis(invalid)).as("invalid identity %s",invalid)
                    .isInstanceOf(java.io.IOException.class).hasMessage("Invalid hypothesis reference");
    }
}
