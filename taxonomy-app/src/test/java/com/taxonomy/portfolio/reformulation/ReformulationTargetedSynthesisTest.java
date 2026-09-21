package com.taxonomy.portfolio.reformulation;

import com.taxonomy.composition.reformulation.ReformulationExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;

@SpringBootTest(properties={"llm.mock=false","llm.provider=CUSTOM_OPENAI","custom.llm.url=http://localhost:9999/v1/chat/completions","custom.llm.model=reformulation-test","custom.llm.api.key="})
@AutoConfigureMockMvc @WithMockUser(username="architect",roles="ARCHITECT")
class ReformulationTargetedSynthesisTest extends ReformulationWorkflowFixture {
    @Autowired RestTemplate transport;
    @Autowired ReformulationExecutionService execution;
    @Test void localSynthesisCallsOnlyAffectedSectionsAndKeepsIndependentBranchByteStable() throws Exception {
        var p=seed();
        var answered=reformulations.answer(project.id(),requirement.id(),p.id(),2,new ReformulationDtos.AnswerRequest("channel","ANSWER",List.of("Terminal"),"","Selected variant"),"architect",context);
        var calls=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server=MockRestServiceServer.bindTo(transport).build();
        server.expect(manyTimes(),anything()).andRespond(request->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String body=((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString();
            String prompt=json.readTree(body).at("/messages/0/content").asText();
            if(prompt.contains("RECONCILIATION_DATA_JSON"))return withSuccess(json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",json.writeValueAsString(Map.of("affectedSectionIds",List.of(),"sourceResolutions",List.of(),"findings",List.of()))))))),MediaType.APPLICATION_JSON).createResponse(request);
            var input=json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n")+16));calls.add(input.path("nodeId").asText());
            assertThat(input.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
            var statements=new LinkedHashSet<String>();var questions=new LinkedHashSet<String>();
            input.path("directContributions").forEach(s->statements.add(s.path("id").asText()));input.path("openDecisions").forEach(q->questions.add(q.path("id").asText()));
            input.path("children").forEach(c->{c.path("statementProposals").forEach(s->statements.add(s.path("id").asText()));c.path("questionProposals").forEach(q->questions.add(q.path("id").asText()));});
            String content=json.writeValueAsString(Map.of("summary","Updated "+input.path("nodeId").asText(),"statementProposals",List.of(),"preservedStatementIds",statements,"questionProposals",List.of(),"preservedQuestionIds",questions,"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
            return withSuccess(json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))))),MediaType.APPLICATION_JSON).createResponse(request);
        });
        try {
            var started=execution.start(project.id(),requirement.id(),p.id(),3,"architect",context);
            await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(()->assertThat(reformulations.runs(project.id(),requirement.id(),p.id(),"architect",context).getLast().status()).isIn("COMPLETED","PARTIAL","FAILED"));
            var run=reformulations.runs(project.id(),requirement.id(),p.id(),"architect",context).getLast();
            assertThat(run.failureCode()).isNull();assertThat(run.status()).isEqualTo("COMPLETED");
            assertThat(calls).containsExactly("BP-1","BP");
            var after=reformulations.get(project.id(),requirement.id(),p.id(),"architect",context).currentRevision();
            assertThat(after.sections().get(2)).isEqualTo(answered.currentRevision().sections().get(2));
            assertThat(after.statements().stream().filter(s->s.id().equals("independent")).findFirst().orElseThrow()).isEqualTo(p.currentRevision().statements().get(1));
            assertThat(after.text()).contains("Updated BP-1","Unabhängige Abrechnung.");
        } finally {server.reset();}
    }
}
