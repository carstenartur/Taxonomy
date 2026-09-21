package com.taxonomy.analysis.reformulation;

import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ReformulationResponseParserTest {
    static final String EMPTY="{\"summary\":\"Zusammenfassung\",\"statementProposals\":[],\"preservedStatementIds\":[],\"questionProposals\":[],\"preservedQuestionIds\":[],\"uncoveredSourceRefs\":[],\"conflictCandidates\":[]}";
    static NodeSynthesisInput input() {
        return new NodeSynthesisInput(WalkUpReformulationTest.baseline(),"P",null,"description",List.of(),List.of(),List.of(),Map.of(),List.of(),List.of(),"preserve");
    }
    final ReformulationResponseParser parser=new ReformulationResponseParser(new ObjectMapper());
    @Test void rejectsTruncationTrailingContentMissingFieldsUnknownFieldsAndInventedIds() {
        for(String response:List.of(EMPTY.substring(0,40),EMPTY+" {}","{\"summary\":\"x\"}",EMPTY.replace("\"summary\"","\"intrusion\""),EMPTY.replace("\"preservedStatementIds\":[]","\"preservedStatementIds\":[\"invented\"]")))
            assertThatThrownBy(()->parser.parse(response,input())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsDuplicateJsonKeysAndForgedSourceAndNumericProvenance() {
        assertThatThrownBy(()->parser.parse(EMPTY.replace("\"summary\":", "\"summary\":\"first\",\"summary\":"),input())).isInstanceOf(IllegalArgumentException.class);
        String statement="{\"wording\":\"Mandatory retention 10 years\",\"provenance\":\"ORIGINAL\",\"sourceSpans\":[],\"architectureLinks\":[],\"questionDependencies\":[],\"conditionalValidity\":null}";
        assertThatThrownBy(()->parser.parse(EMPTY.replace("\"statementProposals\":[]","\"statementProposals\":["+statement+"]"),input())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(EMPTY.replace("\"uncoveredSourceRefs\":[]","\"uncoveredSourceRefs\":[{\"start\":0,\"end\":4,\"exactText\":\"Fake\"}]"),input())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void assignsIdsAndRetainsQuestionAnswerContract() {
        String response=EMPTY.replace("\"questionProposals\":[]","\"questionProposals\":[{\"subject\":\"time\",\"dimension\":\"correction\",\"scope\":\"P\",\"wording\":\"Wie werden Fehleingaben korrigiert?\",\"rationale\":\"Original lässt Korrektur offen\",\"affectedStatementIds\":[],\"sourceSpans\":[],\"nodeIds\":[\"P\"],\"edgeIds\":[],\"answerSchema\":{\"kind\":\"SINGLE_CHOICE\",\"options\":[\"Korrektur\",\"Keine Korrektur erforderlich\",\"Offen\"],\"unit\":null,\"minimum\":null,\"maximum\":null},\"prerequisites\":[],\"consequences\":\"Korrekturprozess festlegen\"}]");
        var result=parser.parse(response,input());
        assertThat(result.questionProposals()).hasSize(1);
        assertThat(result.questionProposals().getFirst().id()).startsWith("q-");
        assertThat(result.questionProposals().getFirst().answerSchema().options()).contains("Keine Korrektur erforderlich");
        assertThatThrownBy(()->parser.parse(response.replace("\"subject\":","\"id\":\"model-id\",\"subject\":"),input())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void linksSameResponseStatementsAndQuestionsViaLocalIndexesAndAllowsFrozenBoundaryEndpoints() {
        var in=new NodeSynthesisInput(input().baseline(),"P",null,"P",List.of(),List.of(),List.of(),Map.of("edge-1","{\"sourceCode\":\"P\",\"targetCode\":\"EXTERNAL\"}"),List.of(),List.of(),"preserve");
        String response=EMPTY.replace("\"statementProposals\":[]","\"statementProposals\":[{\"wording\":\"Korrektur nach gewähltem Verfahren\",\"provenance\":\"MODEL_ADDITION\",\"sourceSpans\":[],\"architectureLinks\":[\"EXTERNAL\",\"edge-1\"],\"questionDependencies\":[\"new-question:0\"],\"conditionalValidity\":\"Offene Entscheidung\"}]")
            .replace("\"questionProposals\":[]","\"questionProposals\":[{\"subject\":\"time\",\"dimension\":\"correction\",\"scope\":\"P\",\"wording\":\"Wie werden Fehleingaben korrigiert?\",\"rationale\":\"Offen\",\"affectedStatementIds\":[\"new-statement:0\"],\"sourceSpans\":[],\"nodeIds\":[\"EXTERNAL\"],\"edgeIds\":[\"edge-1\"],\"answerSchema\":{\"kind\":\"TEXT\",\"options\":[],\"unit\":null,\"minimum\":null,\"maximum\":null},\"prerequisites\":[],\"consequences\":\"Korrekturprozess festlegen\"}]");
        var result=parser.parse(response,in);
        assertThat(result.statementProposals().getFirst().questionDependencies()).containsExactly(result.questionProposals().getFirst().id());
        assertThat(result.questionProposals().getFirst().affectedStatementIds()).containsExactly(result.statementProposals().getFirst().id());
        assertThatThrownBy(()->parser.parse(response.replace("new-statement:0","new-statement:1"),in)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsChildLossAndRequiresBothStatementAndQuestionPreservation() {
        var s=new Statement("s-child","Child exact wording",List.of(),Statement.Provenance.MODEL_ADDITION,List.of("P"),List.of(),null,Statement.EditingOrigin.MODEL,"UNREVIEWED");
        var q=new DecisionQuestion("q-child",new DecisionQuestion.Key("s","d","P"),"Question?",List.of(),List.of(),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),"impact",DecisionQuestion.State.OPEN);
        var child=new NodeSynthesisResult("C","short summary",List.of(s),List.of(),List.of(q),List.of(),List.of(),List.of());
        var in=new NodeSynthesisInput(input().baseline(),"P",null,"parent",List.of(),List.of(),List.of(child),Map.of(),List.of(),List.of(),"preserve");
        assertThatThrownBy(()->parser.parse(EMPTY,in)).isInstanceOf(IllegalArgumentException.class);
        var result=parser.parse(EMPTY.replace("\"preservedStatementIds\":[]","\"preservedStatementIds\":[\"s-child\"]").replace("\"preservedQuestionIds\":[]","\"preservedQuestionIds\":[\"q-child\"]"),in);
        assertThat(result.preservedStatementIds()).containsExactly("s-child");
        assertThat(new ReformulationPromptBuilder(new ObjectMapper()).build(in,null)).contains(in.baseline().originalText(),"Child exact wording","q-child","short summary");
    }
}
