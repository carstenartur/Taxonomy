package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReformulationTypedQuestionParserTest {
    final ObjectMapper json=new ObjectMapper();
    NodeSynthesisInput input(String schema) {
        var old=ReformulationResponseParserTest.input();var b=old.baseline();
        var context=new HashMap<>(b.frozenContext());context.put("reformulationSchemaVersion",schema);
        return new NodeSynthesisInput(new ReformulationBaseline(b.scope(),b.sourceVersionId(),b.originalText(),b.originalTextHash(),b.snapshotId(),b.snapshotPayload(),context,b.language(),b.algorithmVersion()),old.nodeId(),null,"P",List.of(),List.of(),List.of(),Map.of(),List.of(),List.of(),"preserve");
    }
    String response() {
        return """
            {"summary":"Selected capture design","statementProposals":[],"preservedStatementIds":[],"preservedQuestionIds":[],"uncoveredSourceRefs":[],"conflictCandidates":[],"questionProposals":[
              {"subject":"time","dimension":"channel","scope":"P","wording":"Capture channel?","rationale":"Not in original","affectedStatementIds":[],"sourceSpans":[],"nodeIds":["P"],"edgeIds":[],"answerSchema":{"kind":"MULTIPLE_CHOICE","options":["Terminal","Browser","Custom","Later"],"unit":null,"minimum":null,"maximum":null,"optionMeanings":{"Custom":"OTHER","Later":"OPEN"},"incompatibleOptions":[["Terminal","Browser"]],"applicability":[]},"prerequisites":[],"consequences":"Select variant"},
              {"subject":"time","dimension":"offline","scope":"P","wording":"Offline terminal capture?","rationale":"Only selected terminal variant","affectedStatementIds":[],"sourceSpans":[],"nodeIds":["P"],"edgeIds":[],"answerSchema":{"kind":"BOOLEAN","options":[],"unit":null,"minimum":null,"maximum":null,"optionMeanings":{},"incompatibleOptions":[],"applicability":[{"questionId":"new-question:0","anyOf":["Terminal"]}]},"prerequisites":["new-question:0"],"consequences":"Clarify selected variant"}
            ]}
            """;
    }
    @Test void typedV2ConditionsResolveToApplicationQuestionIdsAndPreserveOptionMeanings() {
        var result=new ReformulationResponseParser(json).parse(response(),input("reformulation-response-v2"));
        var channel=result.questionProposals().getFirst();var offline=result.questionProposals().get(1);
        assertThat(channel.answerSchema().optionMeanings()).containsEntry("Custom",DecisionQuestion.AnswerSchema.OptionMeaning.OTHER);
        assertThat(offline.answerSchema().applicability().getFirst().questionId()).isEqualTo(channel.id());
        assertThat(offline.answerSchema().applicability().getFirst().anyOf()).containsExactly("Terminal");
    }
    @Test void legacySchemaRemainsStrictAndTypedConditionsRejectInventedReferencesOrChoices() {
        var parser=new ReformulationResponseParser(json);
        assertThatThrownBy(()->parser.parse(response(),input("reformulation-response-v1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(response().replace("\"anyOf\":[\"Terminal\"]","\"anyOf\":[\"Invented\"]"),input("reformulation-response-v2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(response().replace("\"questionId\":\"new-question:0\"","\"questionId\":\"ghost\""),input("reformulation-response-v2"))).isInstanceOf(IllegalArgumentException.class);
    }
}
