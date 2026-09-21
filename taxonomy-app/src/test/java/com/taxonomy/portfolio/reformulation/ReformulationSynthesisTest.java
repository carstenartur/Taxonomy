package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.portfolio.dto.PortfolioDtos.*;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.*;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP/database contract: proposals cannot rewrite original or active architecture. */
@SpringBootTest(properties={"llm.mock=false","llm.provider=CUSTOM_OPENAI","custom.llm.url=http://localhost:9999/v1/chat/completions","custom.llm.model=reformulation-test","custom.llm.api.key="})
@AutoConfigureMockMvc
@WithMockUser(username="architect", roles="ARCHITECT")
class ReformulationSynthesisTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectPortfolioService projects;
    @Autowired PortfolioAnalysisPersistenceService analyses;
    @Autowired SystemRepositoryService repositories;
    @Autowired WorkspaceManager workspaces;
    @Autowired WorkspaceArchitectureReadPort architecture;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean WorkspaceResolver resolver;
    WorkspaceContext context;
    ProjectView project;
    RequirementView requirement;
    String snapshot;
    static final String ORIGINAL = "Ausschließlich Terminal, keine Browseroberfläche. Frist: 2 Sekunden.";

    @BeforeEach void fixture() {
        var workspace = workspaces.createWorkspace("architect", "Reformulation " + UUID.randomUUID(), "Isolation fixture");
        workspace = workspaces.provisionWorkspaceRepository("architect", workspace.getWorkspaceId());
        context = new WorkspaceContext("architect", workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
        select(context);
        project = projects.createProject(new CreateProjectRequest("P", "Project", "Frozen project context", ProjectStatus.ACTIVE,
                null,null,null,null), "architect", context);
        requirement = createRequirement("R");
        snapshot = snapshot(requirement);
    }
    void select(WorkspaceContext selected) {
        when(resolver.resolveCurrentUsername()).thenReturn("architect");
        when(resolver.resolveCurrentContext()).thenReturn(selected);
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(selected.workspaceId()==null
                ? RepositoryContext.centralRead(selected.repositoryId(), selected.currentBranch(), "architect")
                : RepositoryContext.workspace(selected.repositoryId(),selected.workspaceId(),selected.currentBranch(),"architect"));
    }
    RequirementView createRequirement(String key) {
        return projects.createRequirement(project.id(), new CreateRequirementRequest(key,"Capture time",ORIGINAL,
                RequirementStatus.APPROVED,50,Criticality.HIGH,RequirementType.FUNCTIONAL,ReviewStatus.CONFIRMED,
                "architect","original",null),"architect",context);
    }
    String snapshot(RequirementView req) {
        var job = analyses.createOrReuseJob(project.id(),List.of(req.id()),null,25,UUID.randomUUID().toString(),"architect",context);
        var node = new TaxonomyNodeDto(); node.setCode("BP-1"); node.setNameEn("Time capture");
        node.setDescriptionEn("Frozen catalogue description");
        var result = new AnalysisResult(Map.of("BP-1",45),List.of(node)); result.setStatus("PARTIAL");
        String id = UUID.randomUUID().toString();
        analyses.persistSnapshot(job.items().getFirst().id(),job.id(),project.id(),PortfolioScope.key("architect",context),
                id,"session-"+id,result,null,null,null,null,null,"prompt-fingerprint","catalogue-fingerprint","architect",context,1);
        return id;
    }
    String base() { return "/api/projects/"+project.id()+"/requirements/"+requirement.id()+"/reformulations"; }
    String request(String selectedSnapshot, long version) throws Exception {
        return json.writeValueAsString(Map.of("snapshotId",selectedSnapshot,"sourceVersionId",version,"language","de"));
    }
    JsonNode create() throws Exception {
        return json.readTree(mvc.perform(post(base()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(snapshot, requirement.currentVersionId())))
                .andExpect(status().isAccepted()).andExpect(header().string("ETag","\"1\""))
                .andReturn().getResponse().getContentAsString());
    }
    @Autowired org.springframework.web.client.RestTemplate restTemplate;
    @Autowired ReformulationService reformulations;

    @Test void createDispatchesRealGatewaySynthesisAndPreservesOriginalAndStructureOnManualEdit() throws Exception {
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();
        var before=projects.getRequirement(project.id(),requirement.id(),"architect",context);
        var architectureBefore=architecture.read(resolver.resolveCurrentRepositoryContext(),null);
        var seen=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(4),org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
            .andRespond(request -> {
                assertThat(request.getHeaders().getFirst("Authorization")).isNull();
                assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                String body=((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString();
                String prompt=json.readTree(body).at("/messages/0/content").asText();seen.add(prompt);
                assertThat(prompt).contains(ORIGINAL);
                if(!prompt.contains("RECONCILIATION_DATA_JSON"))assertThat(prompt).contains("Frozen catalogue description");
                if(prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                    String content=json.writeValueAsString(Map.of("affectedSectionIds",List.of(),"sourceResolutions",List.of(),"findings",List.of()));
                    return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))))),MediaType.APPLICATION_JSON).createResponse(request);
                }
                var input=json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n")+16));
                var ids=new ArrayList<String>();input.path("directContributions").forEach(s->ids.add(s.path("id").asText()));
                if(seen.size()==3) {
                    var questionIds=new ArrayList<String>();input.path("openDecisions").forEach(q->questionIds.add(q.path("id").asText()));
                    String content=json.writeValueAsString(Map.of("summary","Second synthesis without new wording", "statementProposals",List.of(),
                        "preservedStatementIds",ids,"questionProposals",List.of(),"preservedQuestionIds",questionIds,"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
                    return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))))),MediaType.APPLICATION_JSON).createResponse(request);
                }
                var affectedIds=new ArrayList<>(ids);affectedIds.add("new-statement:0");
                var statement=Map.of("wording","Das System erfasst Arbeitsbeginn und Arbeitsende am Terminal.",
                    "provenance","MODEL_ADDITION","sourceSpans",List.of(),"architectureLinks",List.of("BP-1"),
                    "questionDependencies",List.of("new-question:0"),"conditionalValidity","Korrekturverfahren noch offen");
                var question=Map.ofEntries(Map.entry("subject","time"),Map.entry("dimension","correction"),Map.entry("scope","BP-1"),
                    Map.entry("wording","Wie werden Fehleingaben korrigiert?"),Map.entry("rationale","Im Original nicht festgelegt"),
                    Map.entry("affectedStatementIds",affectedIds),Map.entry("sourceSpans",List.of()),Map.entry("nodeIds",List.of("BP-1")),
                    Map.entry("edgeIds",List.of()),Map.entry("answerSchema",json.readTree("{\"kind\":\"SINGLE_CHOICE\",\"options\":[\"Korrektur am Terminal\",\"Keine Korrektur erforderlich\",\"Offen\"],\"unit\":null,\"minimum\":null,\"maximum\":null}")),
                    Map.entry("prerequisites",List.of()),Map.entry("consequences","Korrekturprozess konkretisieren"));
                String content=json.writeValueAsString(Map.of("summary","Arbeitszeiten werden ausschließlich am Terminal erfasst.",
                    "statementProposals",List.of(statement),"preservedStatementIds",ids,"questionProposals",List.of(question),
                    "preservedQuestionIds",List.of(),"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
                return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                    json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))))),MediaType.APPLICATION_JSON).createResponse(request);
            });
        var proposal=create();String id=proposal.path("id").asText();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->
            assertThat(reformulations.runs(project.id(),requirement.id(),id,"architect",context)).extracting(ReformulationDtos.Run::status).containsExactly("COMPLETED"));
        mvc.perform(get(base()+"/"+id+"/synthesis-runs")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].promptContent").isString())
            .andExpect(jsonPath("$[0].reconcileContext.reconcilePrompt").isString());
        var generated=reformulations.get(project.id(),requirement.id(),id,"architect",context);
        assertThat(generated.currentRevision().text()).contains("keine Browseroberfläche","2 Sekunden","Das System erfasst Arbeitsbeginn und Arbeitsende am Terminal.");
        assertThat(generated.currentRevision().questions()).hasSize(1);
        assertThat(generated.currentRevision().sections()).isNotEmpty();
        assertThat(generated.currentRevision().text()).contains("Arbeitszeiten werden ausschließlich am Terminal erfasst.","[MODEL_ADDITION]","Schnittstellen");
        assertThat(generated.baseline().frozenContext()).containsKeys("reformulationPrompt","reformulationSchemaVersion");
        mvc.perform(post(base()+"/"+id+"/synthesis-runs").with(csrf()).header("If-Match","\"2\"")).andExpect(status().isAccepted());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->
            assertThat(reformulations.runs(project.id(),requirement.id(),id,"architect",context)).extracting(ReformulationDtos.Run::status).containsExactly("COMPLETED","COMPLETED"));
        var regenerated=reformulations.get(project.id(),requirement.id(),id,"architect",context);
        assertThat(regenerated.currentRevision().number()).isEqualTo(3);
        assertThat(regenerated.currentRevision().statements()).containsAll(generated.currentRevision().statements());
        assertThat(regenerated.currentRevision().questions()).isEqualTo(generated.currentRevision().questions());
        var publishedStatementIds=regenerated.currentRevision().statements().stream().map(com.taxonomy.reformulation.Statement::id).toList();
        regenerated.currentRevision().questions().forEach(q->assertThat(publishedStatementIds).containsAll(q.affectedStatementIds()));
        var edited=reformulations.saveDraft(project.id(),requirement.id(),id,3,new ReformulationDtos.SaveDraftRequest("Human wording","Manual edit"),"architect",context);
        assertThat(edited.currentRevision().questions()).isEqualTo(generated.currentRevision().questions());
        assertThat(edited.currentRevision().statements()).containsAll(generated.currentRevision().statements());
        assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context)).isEqualTo(before);
        assertThat(projects.listRequirementVersions(project.id(),requirement.id(),"architect",context)).hasSize(1);
        assertThat(architecture.read(resolver.resolveCurrentRepositoryContext(),null)).isEqualTo(architectureBefore);
        assertThat(seen).hasSize(4);server.verify();
    }
    @Test void lateModelResultIsRetainedAsCandidateAndCannotOverwriteManualDraft() throws Exception {
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        server.expect(org.springframework.test.web.client.ExpectedCount.twice(),org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
            .andRespond(request -> {
                entered.countDown();
                try {assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                String prompt=json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString()).at("/messages/0/content").asText();
                if(prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                    return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content", "{\"affectedSectionIds\":[],\"sourceResolutions\":[],\"findings\":[]}"))))),MediaType.APPLICATION_JSON).createResponse(request);
                }
                var input=json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n")+16));
                var ids=new ArrayList<String>();input.path("directContributions").forEach(n->ids.add(n.path("id").asText()));
                String content=json.writeValueAsString(Map.of("summary","Late summary", "statementProposals",List.of(),"preservedStatementIds",ids,
                    "questionProposals",List.of(),"preservedQuestionIds",List.of(),"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
                return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))))),MediaType.APPLICATION_JSON).createResponse(request);
            });
        String id=create().path("id").asText();
        try {
            assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            reformulations.saveDraft(project.id(),requirement.id(),id,1,new ReformulationDtos.SaveDraftRequest("Protected human text","Human revision while generation runs"),"architect",context);
        } finally {release.countDown();}
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->
            assertThat(reformulations.runs(project.id(),requirement.id(),id,"architect",context)).extracting(ReformulationDtos.Run::status).containsExactly("PARTIAL"));
        var run=reformulations.runs(project.id(),requirement.id(),id,"architect",context).getFirst();
        assertThat(run.failureCode()).isEqualTo("MANUAL_DRAFT_PROTECTED");assertThat(run.candidate()).isNotNull();assertThat(run.resultRevision()).isNull();
        assertThat(reformulations.get(project.id(),requirement.id(),id,"architect",context).currentRevision().text()).isEqualTo("Protected human text");
        mvc.perform(post(base()+"/"+id+"/synthesis-runs").with(csrf())).andExpect(status().isPreconditionRequired());
        mvc.perform(post(base()+"/"+id+"/synthesis-runs").with(csrf()).header("If-Match","\"1\"")).andExpect(status().isPreconditionFailed());
        select(new WorkspaceContext("architect","foreign-workspace",context.currentBranch(),context.repositoryId()));
        mvc.perform(get(base()+"/"+id+"/synthesis-runs")).andExpect(status().isNotFound());
        server.verify();
    }

    @Test void publicationHoldsInvalidQuestionReferencesAsCandidate() {
        var proposal=reformulations.create(project.id(),requirement.id(),new ReformulationDtos.CreateRequest(requirement.currentVersionId(),snapshot,"de"),"architect",context);
        var run=reformulations.beginRun(project.id(),requirement.id(),proposal.id(),1,"CUSTOM_OPENAI","test","prompt-v1","schema-v1","frozen prompt","architect",context);
        var question=new com.taxonomy.reformulation.DecisionQuestion("q-orphan",new com.taxonomy.reformulation.DecisionQuestion.Key("subject","dimension","scope"),"Question?",List.of(),
            List.of("missing-statement"),new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),
            List.of(),List.of(),"Missing evidence",com.taxonomy.reformulation.DecisionQuestion.State.OPEN);
        var document=new com.taxonomy.reformulation.ReformulationDocument("Candidate",List.of(),List.of(),List.of(question),new com.taxonomy.reformulation.ValidationReport(List.of()),List.of());
        reformulations.finishRun(project.id(),requirement.id(),proposal.id(),run.id(),document,null,"architect",context);
        var persisted=reformulations.runs(project.id(),requirement.id(),proposal.id(),"architect",context).getFirst();
        assertThat(persisted.status()).isEqualTo("PARTIAL");
        assertThat(persisted.failureCode()).isEqualTo("INVALID_REFERENCE_CLOSURE");
        assertThat(persisted.candidate()).isEqualTo(document);assertThat(persisted.resultRevision()).isNull();
        assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().number()).isEqualTo(1);
    }

    @Test void sourceResolvedDecisionAndNewDiscoveryPublishWithStablePriorIdentityAndFrozenRunPrompt() throws Exception {
        var proposal=reformulations.create(project.id(),requirement.id(),new ReformulationDtos.CreateRequest(requirement.currentVersionId(),snapshot,"de"),"architect",context);
        assertThat(proposal.baseline().frozenContext()).doesNotContainKey("reconcilePrompt"); // Existing offer: per-run fallback must be persisted.
        var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(restTemplate).build();
        var seen=new java.util.concurrent.atomic.AtomicInteger();var questionId=new java.util.concurrent.atomic.AtomicReference<String>();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(4),org.springframework.test.web.client.match.MockRestRequestMatchers.anything()).andRespond(request->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String prompt=json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString()).at("/messages/0/content").asText();
            assertThat(prompt).contains(ORIGINAL).doesNotContain("workspaceDsl","snapshotPayload");int call=seen.incrementAndGet();String content;
            var run=reformulations.runs(project.id(),requirement.id(),proposal.id(),"architect",context).getLast();
            assertThat(run.reconcileContext()).containsKeys("reconcilePrompt","reconcilePromptVersion","reconcileSchemaVersion");
            if(prompt.contains("RECONCILIATION_DATA_JSON\n")) {
                assertThat(prompt).startsWith(run.reconcileContext().get("reconcilePrompt"));
                var input=json.readTree(prompt.substring(prompt.indexOf("RECONCILIATION_DATA_JSON\n")+"RECONCILIATION_DATA_JSON\n".length()));
                assertThat(input.path("questions")).hasSize(1);String q=input.path("questions").get(0).path("id").asText();
                if(questionId.get()==null)questionId.set(q);else assertThat(q).isEqualTo(questionId.get());
                content=json.writeValueAsString(Map.of("affectedSectionIds",List.of(),"findings",List.of(),"sourceResolutions",List.of(Map.of("questionId",q,"values",List.of("Terminal"),"rationale","Explicit terminal-only original", "sourceSpans",List.of(Map.of("start",0,"end",ORIGINAL.length(),"exactText",ORIGINAL))))));
            }else {
                var input=json.readTree(prompt.substring(prompt.indexOf("INPUT_DATA_JSON\n")+16));
                var ids=new ArrayList<String>();input.path("directContributions").forEach(v->ids.add(v.path("id").asText()));
                var qids=new ArrayList<String>();input.path("openDecisions").forEach(v->qids.add(v.path("id").asText()));
                var question=Map.ofEntries(Map.entry("subject","time"),Map.entry("dimension","capture"),Map.entry("scope","global"),
                    Map.entry("wording",call==1?"Capture method?":"Capture channel discovery?"),Map.entry("rationale","Capture dependency discovered "+call),
                    Map.entry("affectedStatementIds",ids),Map.entry("sourceSpans",List.of()),Map.entry("nodeIds",List.of("BP-1")),Map.entry("edgeIds",List.of()),
                    Map.entry("answerSchema",json.readTree("{\"kind\":\"SINGLE_CHOICE\",\"options\":[\"Terminal\",\"Browser\"],\"unit\":null,\"minimum\":null,\"maximum\":null}")),Map.entry("prerequisites",List.of()),Map.entry("consequences","Capture design"));
                content=json.writeValueAsString(Map.of("summary","Capture exclusively at a terminal", "statementProposals",List.of(),"preservedStatementIds",ids,"questionProposals",List.of(question),"preservedQuestionIds",qids,"uncoveredSourceRefs",List.of(),"conflictCandidates",List.of()));
            }
            return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content",content))))),MediaType.APPLICATION_JSON).createResponse(request);
        });
        for(long revision=1;revision<=2;revision++) {
            mvc.perform(post(base()+"/"+proposal.id()+"/synthesis-runs").with(csrf()).header("If-Match","\""+revision+"\"")).andExpect(status().isAccepted());
            long expected=revision+1;
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().number()).isEqualTo(expected));
        }
        var finalRevision=reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision();
        assertThat(finalRevision.answers()).isEmpty();
        assertThat(finalRevision.questions()).singleElement().satisfies(q->{assertThat(q.id()).isEqualTo(questionId.get());assertThat(q.state()).isEqualTo(com.taxonomy.reformulation.DecisionQuestion.State.ANSWERED);assertThat(q.sourceResolutions()).hasSize(1);assertThat(q.discoveries()).hasSize(2);assertThat(q.aliases()).hasSize(1);});
        assertThat(finalRevision.text()).contains("Capture exclusively at a terminal",ORIGINAL);
        assertThat(reformulations.revision(project.id(),requirement.id(),proposal.id(),2,"architect",context).questions().getFirst().discoveries()).hasSize(1);
        assertThat(projects.listRequirementVersions(project.id(),requirement.id(),"architect",context)).hasSize(1);
        server.verify();
    }

    @Test void publicationRejectsUnverifiedAliasAndLossOfPriorQuestion() {
        var proposal=reformulations.create(project.id(),requirement.id(),new ReformulationDtos.CreateRequest(requirement.currentVersionId(),snapshot,"de"),"architect",context);
        var q=new com.taxonomy.reformulation.DecisionQuestion("prior",new com.taxonomy.reformulation.DecisionQuestion.Key("time","correction","global"),"Correction?",List.of(),List.of(),new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.Kind.TEXT,List.of(),null,null,null),List.of(),List.of(),"Correction process",com.taxonomy.reformulation.DecisionQuestion.State.OPEN);
        var initial=new com.taxonomy.reformulation.ReformulationDocument(ORIGINAL,List.of(),List.of(),List.of(q),new com.taxonomy.reformulation.ValidationReport(List.of()),List.of());
        var run=reformulations.beginRun(project.id(),requirement.id(),proposal.id(),1,"CUSTOM_OPENAI","test","v1","v1","frozen","architect",context);
        reformulations.finishRun(project.id(),requirement.id(),proposal.id(),run.id(),initial,null,"architect",context);
        assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().number()).isEqualTo(2);
        var forged=new com.taxonomy.reformulation.DecisionQuestion("replacement",q.key(),q.wording(),q.discoveries(),q.affectedStatementIds(),q.answerSchema(),q.prerequisites(),q.dependentQuestionIds(),q.consequences(),q.state(),List.of("prior"),List.of(),List.of());
        for(var questions:List.of(List.of(forged),List.<com.taxonomy.reformulation.DecisionQuestion>of())) {
            var candidate=new com.taxonomy.reformulation.ReformulationDocument(ORIGINAL,List.of(),List.of(),questions,new com.taxonomy.reformulation.ValidationReport(List.of()),List.of());
            var attempt=reformulations.beginRun(project.id(),requirement.id(),proposal.id(),2,"CUSTOM_OPENAI","test","v1","v1","frozen","architect",context);
            reformulations.finishRun(project.id(),requirement.id(),proposal.id(),attempt.id(),candidate,null,"architect",context);
            var persisted=reformulations.runs(project.id(),requirement.id(),proposal.id(),"architect",context).stream().filter(r->r.id().equals(attempt.id())).findFirst().orElseThrow();
            assertThat(persisted.failureCode()).isEqualTo("INVALID_REFERENCE_CLOSURE");assertThat(persisted.candidate()).isEqualTo(candidate);
            assertThat(reformulations.get(project.id(),requirement.id(),proposal.id(),"architect",context).currentRevision().questions()).containsExactly(q);
        }
    }

}
