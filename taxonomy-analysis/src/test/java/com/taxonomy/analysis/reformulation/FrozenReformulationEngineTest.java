package com.taxonomy.analysis.reformulation;

import com.taxonomy.analysis.service.*;
import com.taxonomy.reformulation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenReformulationEngineTest {
    @Test void usesFrozenSnapshotAndSendsFullOriginalWithDirectParentsAndThreeTerminalContributions() {
        var json=new ObjectMapper();var base=WalkUpReformulationTest.baseline();
        var context=Map.of("catalogue","[{\"code\":\"P\",\"descriptionEn\":\"Parent detail\",\"children\":[{\"code\":\"Q\",\"descriptionEn\":\"Q detail\",\"children\":[{\"code\":\"A\",\"descriptionEn\":\"A detail\"},{\"code\":\"B\",\"descriptionEn\":\"B detail\"}]},{\"code\":\"C\",\"descriptionEn\":\"C detail\"}]}]",
            "workspaceDsl","FORBIDDEN live workspace replacement", "relationMappings","[]", "snapshotDetail","ARCHIVE_ONLY_UNRELATED_DETAIL", "promptTemplatesAtCapture","ARCHIVE_ONLY_SCORING_INSTRUCTIONS");
        var baseline=new ReformulationBaseline(base.scope(),1,base.originalText(),base.originalTextHash(),"snap","{\"rawScores\":{\"P\":40,\"Q\":30,\"A\":10,\"B\":20,\"C\":30}}",context,"de","reformulation-v1");
        var prompts=new ArrayList<String>();var registry=mock(LlmGatewayRegistry.class);var config=mock(LlmProviderConfig.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.OPENAI);when(config.isProviderConfigured(LlmProvider.OPENAI)).thenReturn(true);when(config.getApiKey(LlmProvider.OPENAI)).thenReturn("test");
        var gateway=new LlmGateway() {
            public String providerName(){return "test";}public String extractResponseText(String raw){return raw;}
            public String sendHttpRequest(String prompt,String key) {
                prompts.add(prompt);var input=json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n")+16));
                var ids=new TreeSet<String>();var qids=new TreeSet<String>();
                input.path("directContributions").forEach(s->ids.add(s.path("id").asText()));
                input.path("children").forEach(c->{c.path("statementProposals").forEach(s->ids.add(s.path("id").asText()));c.path("questionProposals").forEach(q->qids.add(q.path("id").asText()));});
                return json.writeValueAsString(Map.of("summary","Useful summary", "statementProposals",List.of(),"preservedStatementIds",ids,"questionProposals",List.of(),"preservedQuestionIds",qids,"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
            }
        };
        when(registry.getGateway(LlmProvider.OPENAI)).thenReturn(gateway);
        var result=new FrozenReformulationEngine(new NodeReformulationService(registry,config,json),json).synthesize(baseline,List.of(),List.of());
        assertThat(prompts).hasSize(2).allSatisfy(p->assertThat(p).contains(base.originalText()).doesNotContain("FORBIDDEN live workspace replacement","ARCHIVE_ONLY_UNRELATED_DETAIL","ARCHIVE_ONLY_SCORING_INSTRUCTIONS","snapshotPayload","\"catalogue\""));
        assertThat(prompts.getFirst()).contains("A detail","B detail","Q detail");
        assertThat(prompts.getLast()).contains("C detail","Parent detail","Useful summary");
        var firstInput=json.readTree(prompts.getFirst().substring(prompts.getFirst().indexOf("INPUT_DATA_JSON\n")+16));
        assertThat(firstInput.path("sourceAnchors").size()).isGreaterThanOrEqualTo(3);
        assertThat(firstInput.path("sourceAnchors").toString()).contains("keine Browseroberfläche","2 Sekunden");
        assertThat(result.text()).contains("keine Browseroberfläche","2 Sekunden");
        assertThat(result.statements()).extracting(Statement::id).doesNotHaveDuplicates();
        assertThat(result.sections()).hasSize(2);
    }
    @Test void resynthesisPreservesPriorStatementObjectsAndTheirQuestionReferences() {
        var statement=new Statement("s-prior","Previously proposed detail",List.of(),Statement.Provenance.MODEL_ADDITION,
                List.of(),List.of("q-prior"),"Conditional prior wording",Statement.EditingOrigin.MODEL,"UNREVIEWED");
        var question=new DecisionQuestion("q-prior",new DecisionQuestion.Key("capture","correction","global"),"Correction?",List.of(),
                List.of(statement.id()),new DecisionQuestion.AnswerSchema(DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),
                List.of(),List.of(),"Impacts prior detail",DecisionQuestion.State.OPEN);
        var service=mock(NodeReformulationService.class);
        when(service.synthesize(any())).thenAnswer(call->{
            NodeSynthesisInput input=call.getArgument(0);
            return new NodeSynthesisResult(input.nodeId(),"Second summary",List.of(),input.directContributions().stream().map(Statement::id).toList(),
                    List.of(),input.openDecisions().stream().map(DecisionQuestion::id).toList(),List.of(),List.of());
        });
        var result=new FrozenReformulationEngine(service,new ObjectMapper()).synthesize(WalkUpReformulationTest.baseline(),List.of(statement),List.of(),List.of(question));
        assertThat(result.statements()).contains(statement);
        assertThat(result.questions()).containsExactly(question);
        assertThat(result.sections().getFirst().statementIds()).contains(statement.id());
        assertThat(result.nodeResults().getFirst().statementProposals()).contains(statement);
        assertThat(result.statements().stream().map(Statement::id).toList()).containsAll(result.questions().getFirst().affectedStatementIds());
    }

}
