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
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username="architect", roles="ARCHITECT")
class ReformulationIsolationTest {
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
    @Test void missingProviderPersistsHonestFailedRunWithoutChangingOriginalDraft() throws Exception {
        String id=create().path("id").asText();
        mvc.perform(get(base()+"/"+id+"/synthesis-runs")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("FAILED"))
            .andExpect(jsonPath("$[0].failureCode").value("PROVIDER_NOT_CONFIGURED"));
        mvc.perform(get(base()+"/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.currentRevision.number").value(1));
    }
    @Test void savesTwoImmutableRevisionsWithoutChangingRequirementOrAnalysis() throws Exception {
        var before = projects.getRequirement(project.id(),requirement.id(),"architect",context);
        var repositoryContext = resolver.resolveCurrentRepositoryContext();
        var architectureBefore = architecture.read(repositoryContext,null);
        var proposal = create(); String id = proposal.path("id").asText();
        assertThat(proposal.at("/baseline/originalText").asText()).isEqualTo(ORIGINAL);
        assertThat(proposal.at("/baseline/sourceVersionId").asLong()).isEqualTo(requirement.currentVersionId());
        assertThat(proposal.at("/baseline/snapshotPayload").asText()).contains("Frozen catalogue description");
        for (int revision=2; revision<=3; revision++) {
            mvc.perform(post(base()+"/"+id+"/revisions").with(csrf()).header("If-Match","\""+(revision-1)+"\"")
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("text","Draft "+revision,"rationale","Human edit"))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.currentRevision.number").value(revision));
        }
        // Separate requests/service transactions force DB reload, not an in-memory proposal map.
        mvc.perform(get(base()+"/"+id+"/revisions/2")).andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Draft 2"));
        mvc.perform(get(base()+"/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.currentRevision.text").value("Draft 3"));
        assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context)).isEqualTo(before);
        assertThat(projects.listRequirementVersions(project.id(),requirement.id(),"architect",context)).hasSize(1);
        assertThat(architecture.read(repositoryContext,null)).isEqualTo(architectureBefore);
        mvc.perform(post(base()+"/"+id+"/revisions").with(csrf()).header("If-Match","\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"stale\",\"rationale\":\"stale\"}"))
                .andExpect(status().isPreconditionFailed());
    }
    @Test void rejectsForeignSnapshotAndMismatchedSourceVersion() throws Exception {
        var other = createRequirement("OTHER"); String foreignSnapshot = snapshot(other);
        mvc.perform(post(base()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(foreignSnapshot, requirement.currentVersionId()))).andExpect(status().isConflict());
        mvc.perform(post(base()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(snapshot,other.currentVersionId()))).andExpect(status().isNotFound());
        var next = projects.addRequirementVersion(project.id(),requirement.id(),new CreateRequirementVersionRequest("New source","change",null),"architect",context);
        mvc.perform(post(base()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(request(snapshot,next.id()))).andExpect(status().isConflict());
        // Selecting a historical version explicitly is legal and never switches current version.
        create();
        assertThat(projects.getRequirement(project.id(),requirement.id(),"architect",context).currentVersionId()).isEqualTo(next.id());
    }
    @Test void databaseRejectsSnapshotAndSourceVersionMismatch() throws Exception {
        String id = create().path("id").asText();
        var next = projects.addRequirementVersion(project.id(),requirement.id(),
                new CreateRequirementVersionRequest("Second source","change",null),"architect",context);
        assertThatThrownBy(() -> jdbc.update("update reformulation_proposal set source_version_id=? where id=?",next.id(),id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update reformulation_proposal set scope_key=? where id=?","foreign",id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void cannotReadOrWriteProposalInForeignWorkspaceBranchOrRequirement() throws Exception {
        String id = create().path("id").asText();
        var other = createRequirement("OTHER");
        mvc.perform(get("/api/projects/"+project.id()+"/requirements/"+other.id()+"/reformulations/"+id)).andExpect(status().isNotFound());
        for (var foreign : List.of(new WorkspaceContext("architect","foreign-workspace","main",context.repositoryId()),
                new WorkspaceContext("architect",context.workspaceId(),"foreign-branch",context.repositoryId()),
                new WorkspaceContext("architect",context.workspaceId(),context.currentBranch(),"foreign-repository"))) {
            select(foreign);
            mvc.perform(get(base()+"/"+id)).andExpect(status().isNotFound());
            mvc.perform(post(base()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(request(snapshot,requirement.currentVersionId()))).andExpect(status().isNotFound());
            mvc.perform(post(base()+"/"+id+"/revisions").with(csrf()).header("If-Match","\"1\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"attack\",\"rationale\":\"attack\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
