package com.taxonomy.composition.architecture;

import com.taxonomy.analysis.cluster.ClusterAnalysisObservation;
import com.taxonomy.analysis.recovery.*;
import com.taxonomy.analysis.session.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.*;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real persistence regression: focus reads must not run the mutable recovery/expiry lifecycle. */
class ArchitectureSourceFocusContinuationPersistenceTest {
    @ParameterizedTest @ValueSource(strings={"accepted","unknown-element","wrong-operation"})
    void acceptedAndRejectedInvestigationsLeaveExpiredRunAndAttemptEvidenceUnchanged(String scenario) {
        var json = JsonMapper.builder().build();
        try (var db = new Database(json)) {
            var editor = mock(ArchitectureEditorService.class); var llm = mock(LlmService.class); var budget = mock(AiPromptBudgetPolicy.class);
            var drafts = mock(AnalysisWorkingDraftService.class); var operations = mock(ClusterAnalysisObservation.class);
            var snapshots = mock(PortfolioAnalysisPersistenceService.class); var workspaceRows = mock(UserWorkspaceRepository.class);
            var repositories = mock(SystemRepositoryService.class); var membership = mock(RepositoryMembershipService.class);
            var workspaces = mock(WorkspaceManager.class); var catalogue = mock(TaxonomyService.class);
            var workspace = new UserWorkspace(); workspace.setWorkspaceId("copy"); workspace.setUsername("alice"); workspace.setSourceRepositoryId("repo"); workspace.setCurrentBranch("draft");
            when(workspaceRows.findByWorkspaceId("copy")).thenReturn(Optional.of(workspace)); when(workspaces.getWorkspaceById("copy")).thenReturn(workspace);
            var payload = json.createObjectNode();
            payload.set("analysisReference",json.createObjectNode().put("workspaceId","copy").put("operationId",scenario.equals("wrong-operation") ? "other" : "operation")
                    .put("continuationId",db.id).put("recoveryVersion",0L));
            when(drafts.read("alice","copy")).thenReturn(Optional.of(new AnalysisDraftDtos.AnalysisDraftView("copy","draft",payload,4L,Instant.EPOCH)));
            var source = new ArchitectureSourceFocusDtos.Source("ANALYSIS_DRAFT","repo","copy","draft",4L,null,null,null,null);
            var request = new ArchitectureSourceFocusDtos.Request(source,scenario.equals("unknown-element") ? "missing" : "CP-1000",8,false,"en");
            var before = db.state(); db.statements.clear();
            db.transaction(em -> {
                var continuations = new AnalysisContinuationService(new AnalysisContinuationStore(em,json),catalogue,llm,workspaces,json);
                var focus = new ArchitectureSourceFocusService(new ArchitectureFocusSuggestionService(editor,llm,budget),drafts,operations,
                        continuations,snapshots,workspaceRows,repositories,membership,json);
                if (scenario.equals("accepted")) assertThat(focus.investigate(RepositoryContext.workspace("repo","copy","draft","alice"),request).suggestions()).isNotEmpty();
                else assertThatThrownBy(() -> focus.investigate(RepositoryContext.workspace("repo","copy","draft","alice"),request))
                        .isInstanceOfSatisfying(com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem.class,
                                error -> assertThat(error.code()).isEqualTo(scenario.equals("unknown-element") ? "NOT_FOUND" : "SOURCE_IDENTITY_MISMATCH"));
                // Commit also for the rejected outer request: a nested read transaction must have no writes to publish.
                return null;
            });
            assertThat(db.state()).isEqualTo(before);
            assertThat(db.statements).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).matches("(?s).*\\b(update|insert|delete)\\b.*") || sql.toLowerCase(Locale.ROOT).contains("for update"));
            verifyNoInteractions(editor,llm,budget,operations,catalogue);
        }
    }
    static final class Database implements AutoCloseable {
        final String id=UUID.randomUUID().toString(), token=UUID.randomUUID().toString();
        final List<String> statements=new ArrayList<>(); final SessionFactory factory;
        Database(JsonMapper json) {
            factory = new Configuration().addAnnotatedClass(AnalysisContinuationRun.class).addAnnotatedClass(AnalysisQuestionCheckpoint.class)
                    .setProperty("hibernate.connection.driver_class","org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url","jdbc:hsqldb:mem:focus-evidence-"+id)
                    .setProperty("hibernate.hbm2ddl.auto","create-drop").setProperty("hibernate.search.enabled","false")
                    .setStatementInspector(sql -> { statements.add(sql); return sql; }).buildSessionFactory();
            transaction(em -> {
                var first=new RequirementElementView(); first.setNodeCode("CP-1000"); first.setTaxonomySheet("CP"); first.setTitle("Retained capability");
                var second=new RequirementElementView(); second.setNodeCode("UA-1000"); second.setTaxonomySheet("UA"); second.setTitle("Retained application");
                var graph=new RequirementArchitectureView(); graph.setIncludedElements(List.of(first,second));
                var result=new AnalysisResult(); result.setArchitectureView(graph); result.setStatus("PARTIAL"); result.setAnalysisOperationId("operation");
                var request=new AnalysisRequest("Retained requirement");
                var run=BeanUtils.instantiateClass(AnalysisContinuationRun.class);
                set(run,"id",id); set(run,"username","alice"); set(run,"workspaceId","copy"); set(run,"repositoryId","repo"); set(run,"branchName","draft");
                set(run,"inputHash","0".repeat(64)); set(run,"requestJson",json.writeValueAsString(request)); set(run,"resultJson",json.writeValueAsString(result));
                set(run,"state","RUNNING"); set(run,"claimToken",token); set(run,"claimUntil",1L); set(run,"clusterOperationId","operation"); em.persist(run);
                var question=BeanUtils.instantiateClass(AnalysisQuestionCheckpoint.class);
                set(question,"id",id+":"+"1".repeat(64)); set(question,"run",run); set(question,"questionKey","1".repeat(64)); set(question,"inputHash","0".repeat(64));
                set(question,"provider","MOCK"); set(question,"nodeCodes",json.writeValueAsString(List.of("CP-1000")));
                set(question,"state","ATTEMPT"); set(question,"attempts",1); set(question,"attemptOwner",token); em.persist(question); return null;
            });
        }
        static void set(Object target,String field,Object value) { ReflectionTestUtils.setField(target,field,value); }
        <T> T transaction(Function<EntityManager,T> action) {
            try (var em=factory.createEntityManager()) { em.getTransaction().begin();
                try { var result=action.apply(em); em.getTransaction().commit(); return result; }
                catch (RuntimeException|Error error) { if(em.getTransaction().isActive()) em.getTransaction().rollback(); throw error; }
            }
        }
        List<Object> state() {
            return transaction(em -> {
                var result=new ArrayList<Object>();
                result.addAll(Arrays.asList(em.createQuery("select r.version,r.state,r.claimToken,r.claimUntil,r.updatedAt,r.requestJson,r.resultJson from AnalysisContinuationRun r where r.id=:id",Object[].class).setParameter("id",id).getSingleResult()));
                result.addAll(Arrays.asList(em.createQuery("select q.state,q.attempts,q.error,q.attemptOwner,q.prompt,q.detailJson from AnalysisQuestionCheckpoint q where q.run.id=:id",Object[].class).setParameter("id",id).getSingleResult()));
                return result;
            });
        }
        public void close() { factory.close(); }
    }
}
