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
abstract class ReformulationWorkflowFixture {
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
    String originalText=ORIGINAL;
    boolean withBoundary;
    static final String ORIGINAL = "Arbeitszeiterfassung\n  <img src=x onerror=alert(1)>";

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
        return projects.createRequirement(project.id(), new CreateRequirementRequest(key,"Capture time",originalText,
                RequirementStatus.APPROVED,50,Criticality.HIGH,RequirementType.FUNCTIONAL,ReviewStatus.CONFIRMED,
                "architect","original",null),"architect",context);
    }
    String snapshot(RequirementView req) {
        var job = analyses.createOrReuseJob(project.id(),List.of(req.id()),null,25,UUID.randomUUID().toString(),"architect",context);
        var node = new TaxonomyNodeDto(); node.setCode("BP-1"); node.setNameEn("Time capture");
        node.setDescriptionEn("Frozen catalogue description");
        var peer = new TaxonomyNodeDto(); peer.setCode("BP-2"); peer.setNameEn("Independent billing");
        var root = new TaxonomyNodeDto(); root.setCode("BP"); root.setNameEn("Business process"); root.setChildren(List.of(node,peer));
        var result = new AnalysisResult(Map.of("BP-1",45,"BP-2",40),List.of(root)); result.setStatus("PARTIAL");
        if(withBoundary) {
            node.setDescriptionEn("Frozen <img src=x onerror=alert(1)> node detail");
            var edge=new com.taxonomy.dto.RequirementRelationshipView();edge.setSourceCode("BP-1");edge.setTargetCode("BP-2");
            edge.setRelationType("FLOW");edge.setPresenceReason("Frozen <b>directed boundary</b>");
            var view=new com.taxonomy.dto.RequirementArchitectureView();view.setIncludedRelationships(List.of(edge));result.setArchitectureView(view);
        }
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
    @Autowired ReformulationService reformulations;
    java.util.function.UnaryOperator<com.taxonomy.reformulation.ReformulationDocument> documentTransform=java.util.function.UnaryOperator.identity();
    java.util.function.UnaryOperator<List<com.taxonomy.reformulation.DecisionQuestion>> questionTransform=java.util.function.UnaryOperator.identity();
    com.taxonomy.portfolio.reformulation.ReformulationDtos.Proposal seed() throws Exception {
        var p=reformulations.create(project.id(),requirement.id(),new ReformulationDtos.CreateRequest(requirement.currentVersionId(),snapshot,"de"),"architect",context);
        var run=reformulations.beginRun(project.id(),requirement.id(),p.id(),1,"TEST","test","v1","v1","frozen", "architect",context);
        var statements=List.of(statement("capture","Arbeitsbeginn und Ende erfassen.","BP-1"),statement("independent","Unabhängige Abrechnung.","BP-2"));
        var questions=List.of(question("channel","SINGLE_CHOICE",List.of("Browser","Terminal","Other","Still open"),"BP-1"),
            question("multiple","MULTIPLE_CHOICE",List.of("Browser","Terminal","Other"),"BP-1"),question("text","TEXT",List.of(),"BP-1"),
            question("number","NUMBER",List.of(),"BP-1"),question("boolean","BOOLEAN",List.of(),"BP-1"),
            question("correction","SINGLE_CHOICE",List.of("Correction","Not needed"),"BP-1"),question("global","TEXT",List.of(),"GLOBAL"));
        var sections=List.of(new com.taxonomy.reformulation.Section("BP","BP","Process","Parent",List.of("BP-1","BP-2"),List.of(),List.of()),
            new com.taxonomy.reformulation.Section("BP-1","BP","Capture","Capture",List.of(),List.of("capture"),List.of("channel")),
            new com.taxonomy.reformulation.Section("BP-2","BP","Independent","Independent",List.of(),List.of("independent"),List.of()));
        reformulations.finishRun(project.id(),requirement.id(),p.id(),run.id(),documentTransform.apply(new com.taxonomy.reformulation.ReformulationDocument("Arbeitsbeginn und Ende erfassen.\n\nUnabhängige Abrechnung.",sections,statements,questionTransform.apply(questions),new com.taxonomy.reformulation.ValidationReport(List.of()),List.of())),null,"architect",context);
        return reformulations.get(project.id(),requirement.id(),p.id(),"architect",context);
    }
    com.taxonomy.reformulation.Statement statement(String id,String text,String node) {
        return new com.taxonomy.reformulation.Statement(id,text,List.of(),com.taxonomy.reformulation.Statement.Provenance.MODEL_ADDITION,List.of(node),List.of(),null,com.taxonomy.reformulation.Statement.EditingOrigin.MODEL,"UNREVIEWED");
    }
    com.taxonomy.reformulation.DecisionQuestion question(String id,String kind,List<String> options,String scope) {
        return new com.taxonomy.reformulation.DecisionQuestion(id,new com.taxonomy.reformulation.DecisionQuestion.Key("time",id,scope),"Question "+id,
            List.of(new com.taxonomy.reformulation.DecisionQuestion.Discovery("BP-1","Capture","Original leaves decision open",List.of(),List.of("BP-1"),List.of())),
            List.of("capture"),new com.taxonomy.reformulation.DecisionQuestion.AnswerSchema(com.taxonomy.reformulation.DecisionQuestion.AnswerSchema.Kind.valueOf(kind),options,kind.equals("NUMBER")?"seconds":null,kind.equals("NUMBER")?0d:null,kind.equals("NUMBER")?60d:null),List.of(),List.of(),"Update capture design",com.taxonomy.reformulation.DecisionQuestion.State.OPEN);
    }
}
