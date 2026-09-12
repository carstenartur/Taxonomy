package com.taxonomy.composition.dsl.controller;

import com.taxonomy.architecture.model.ArchitectureDslDocument;
import com.taxonomy.composition.dsl.service.DslDocumentOperationsFacade;
import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.versioning.controller.DslApiController;
import com.taxonomy.versioning.controller.DslReadWorkspaceContextResolver;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DslDocumentApiControllerContractTest {
    private final DslDocumentOperationsFacade documents = mock(DslDocumentOperationsFacade.class);
    private final DslOperationsFacade dslOps = mock(DslOperationsFacade.class);
    private final WorkspaceResolver workspace = mock(WorkspaceResolver.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final WorkspaceContext context = new WorkspaceContext("alice","workspace","review");
    private final ViewContext view = new ViewContext("head","review",Instant.EPOCH,true,false,false);
    private DslDocumentApiController controller;
    private MockMvc mvc;
    @BeforeEach
    void setUp() {
        var resolver = new DslReadWorkspaceContextResolver(workspace,state);
        controller = new DslDocumentApiController(documents,dslOps,workspace,resolver);
        mvc = MockMvcBuilders.standaloneSetup(controller,new DslApiController(dslOps,workspace,resolver)).build();
        when(workspace.resolveCurrentUsername()).thenReturn("alice");
        when(workspace.resolveCurrentContext()).thenReturn(context);
    }
    @Test
    void exportPreservesTextContentAndNamespaceDefaultAndOverride() throws Exception {
        when(documents.exportAll("default")).thenReturn("default DSL");
        when(documents.exportAll("custom")).thenReturn("custom DSL");
        mvc.perform(get("/api/dsl/export")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)).andExpect(content().string("default DSL"));
        mvc.perform(get("/api/dsl/export").param("namespace","custom")).andExpect(content().string("custom DSL"));
    }
    @Test
    void currentIncludesAllModelCollectionsAndExplicitWorkspaceView() throws Exception {
        when(documents.buildCanonicalModel()).thenReturn(new CanonicalArchitectureModel());
        when(dslOps.resolveWorkspaceBranch("alice")).thenReturn("review");
        when(dslOps.getViewContext("alice","review",context)).thenReturn(view);
        mvc.perform(get("/api/dsl/current")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.elements").isArray()).andExpect(jsonPath("$.relations").isArray())
                .andExpect(jsonPath("$.requirements").isArray()).andExpect(jsonPath("$.mappings").isArray())
                .andExpect(jsonPath("$.views").isArray()).andExpect(jsonPath("$.evidence").isArray())
                .andExpect(jsonPath("$.viewContext.basedOnCommit").value("head"));
        verify(state).ensureWorkspaceState("alice");
    }
    @Test
    void materializePreservesBodyParametersDefaultBranchAndInvalidEnvelope() throws Exception {
        when(documents.materialize("dsl",null,null,null)).thenReturn(result(true));
        mvc.perform(post("/api/dsl/materialize").contentType(MediaType.TEXT_PLAIN).content("dsl"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.warnings[0]").value("warning")).andExpect(jsonPath("$.relationsCreated").value(2))
                .andExpect(jsonPath("$.hypothesesCreated").value(1)).andExpect(jsonPath("$.documentId").value(42));
        verify(dslOps).getViewContext("draft");
        when(documents.materialize("invalid","file","review","commit")).thenReturn(result(false));
        mvc.perform(post("/api/dsl/materialize").content("invalid").param("path","file").param("branch","review").param("commitId","commit"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.errors[0]").value("invalid"));
        verify(dslOps).getViewContext("review");
        mvc.perform(post("/api/dsl/materialize")).andExpect(status().isBadRequest());
    }
    @Test
    void incrementalKeepsOptionalBeforeRequiredAfterAndDraftDefaults() throws Exception {
        when(documents.materializeIncremental(null,2L)).thenReturn(result(false));
        mvc.perform(post("/api/dsl/materialize-incremental").param("afterDocId","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors").doesNotExist()).andExpect(jsonPath("$.documentId").value(42));
        verify(dslOps).getViewContext("draft");
        var doc = new ArchitectureDslDocument();
        when(documents.findDocumentById(2L)).thenReturn(Optional.of(doc));
        mvc.perform(post("/api/dsl/materialize-incremental").param("afterDocId","2")).andExpect(status().isOk());
        verify(dslOps,times(2)).getViewContext("draft");
        doc.setBranch("review");
        when(documents.materializeIncremental(1L,2L)).thenReturn(result(true));
        mvc.perform(post("/api/dsl/materialize-incremental").param("beforeDocId","1").param("afterDocId","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationsCreated").value(2));
        verify(dslOps).getViewContext("review");
        when(documents.materializeIncremental(null,3L)).thenThrow(new IllegalArgumentException("missing document"));
        mvc.perform(post("/api/dsl/materialize-incremental").param("afterDocId","3"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("missing document"));
        mvc.perform(post("/api/dsl/materialize-incremental")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/dsl/materialize-incremental").param("afterDocId","invalid")).andExpect(status().isBadRequest());
    }
    @Test
    void historyKeepsDefaultAndExplicitBranchArchiveEnrichmentAndUnavailableEnvelope() throws Exception {
        when(dslOps.getViewContext("alice","draft",context)).thenReturn(view);
        mvc.perform(get("/api/dsl/history")).andExpect(status().isOk()).andExpect(jsonPath("$.commits").isEmpty())
                .andExpect(jsonPath("$.currentBranch").value("draft")).andExpect(jsonPath("$.headCommit").value("head"));
        when(dslOps.getDslHistory("review",context)).thenReturn(List.of(new DslCommit("head","alice",Instant.EPOCH,"message"),new DslCommit("old","bob",Instant.EPOCH,"older")));
        when(dslOps.getViewContext("alice","review",context)).thenReturn(view);
        when(documents.findDocumentIdByCommitId("head")).thenReturn(Optional.of(42L));
        mvc.perform(get("/api/dsl/history").param("branch","review")).andExpect(status().isOk())
                .andExpect(jsonPath("$.commits[0].documentId").value(42)).andExpect(jsonPath("$.commits[0].commitId").value("head"))
                .andExpect(jsonPath("$.commits[0].author").value("alice")).andExpect(jsonPath("$.commits[0].message").value("message"))
                .andExpect(jsonPath("$.commits[0].timestamp").exists()).andExpect(jsonPath("$.commits[0].branch").value("review"))
                .andExpect(jsonPath("$.commits[1].documentId").isEmpty());
        when(dslOps.getDslHistory("review",context)).thenThrow(new IOException("private content"));
        mvc.perform(get("/api/dsl/history").param("branch","review")).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"errorCode\":\"HISTORY_LOAD_FAILED\",\"commits\":[],\"currentBranch\":\"review\"}"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }
    @Test
    void diffEndpointsKeepStructuralAndSemanticEnvelopesAndBadRequests() throws Exception {
        var diff = new ModelDiff(List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        when(documents.diffBetween("1","2")).thenReturn(diff);
        mvc.perform(get("/api/dsl/diff/1/2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChanges").value(0)).andExpect(jsonPath("$.isEmpty").value(true))
                .andExpect(jsonPath("$.addedElements").value(0)).andExpect(jsonPath("$.removedElements").value(0))
                .andExpect(jsonPath("$.changedElements").value(0)).andExpect(jsonPath("$.addedRelations").value(0))
                .andExpect(jsonPath("$.removedRelations").value(0)).andExpect(jsonPath("$.changedRelations").value(0))
                .andExpect(jsonPath("$.details.addedElements").isArray()).andExpect(jsonPath("$.semanticChanges").isArray())
                .andExpect(jsonPath("$.semanticSummary").exists());
        mvc.perform(get("/api/dsl/diff/semantic/1/2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalChanges").value(0))
                .andExpect(jsonPath("$.semanticChangeCount").value(0)).andExpect(jsonPath("$.changeTypeCounts").isMap());
        when(documents.diffBetween("bad","2")).thenThrow(new NumberFormatException("bad id"));
        for(String path:List.of("/api/dsl/diff/bad/2","/api/dsl/diff/semantic/bad/2")) {
            mvc.perform(get(path)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("bad id"));
        }
    }
    @Test
    void documentsKeepEmptyAndNonemptyArchiveResults() throws Exception {
        when(documents.listDocuments()).thenReturn(List.of());
        assertThat(controller.listDocuments().getBody()).isEmpty();
        mvc.perform(get("/api/dsl/documents")).andExpect(status().isOk()).andExpect(content().json("[]"));
        var doc = new ArchitectureDslDocument();doc.setId(42L);doc.setBranch("review");doc.setRawContent("dsl");
        when(documents.listDocuments()).thenReturn(List.of(doc));
        mvc.perform(get("/api/dsl/documents")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].branch").value("review")).andExpect(jsonPath("$[0].rawContent").value("dsl"));
    }
    @Test
    void nonemptyModelAndDiffRetainValuesAndSharedCurrentFallback() throws Exception {
        var element = new com.taxonomy.dsl.model.ArchitectureElement("BP","Capability","Business Processes",null,null);
        var model = new CanonicalArchitectureModel(); model.getElements().add(element);
        when(documents.buildCanonicalModel()).thenReturn(model);
        doThrow(new IllegalStateException("unavailable")).when(state).ensureWorkspaceState("alice");
        when(dslOps.resolveWorkspaceBranch(WorkspaceContext.SHARED.username())).thenReturn("draft");
        when(dslOps.getViewContext(WorkspaceContext.SHARED.username(),"draft",WorkspaceContext.SHARED)).thenReturn(view);
        mvc.perform(get("/api/dsl/current")).andExpect(status().isOk())
                .andExpect(jsonPath("$.elements[0].id").value("BP")).andExpect(jsonPath("$.viewContext.basedOnCommit").value("head"));
        var diff = new ModelDiff(List.of(element),List.of(),List.of(),List.of(),List.of(),List.of());
        when(documents.diffBetween("1","2")).thenReturn(diff);
        mvc.perform(get("/api/dsl/diff/1/2")).andExpect(status().isOk()).andExpect(jsonPath("$.totalChanges").value(1))
                .andExpect(jsonPath("$.isEmpty").value(false)).andExpect(jsonPath("$.details.addedElements[0].id").value("BP"))
                .andExpect(jsonPath("$.semanticChanges").isNotEmpty());
        mvc.perform(get("/api/dsl/diff/semantic/1/2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.addedElementCount").value(1)).andExpect(jsonPath("$.semanticChangeCount").value(1));
    }

    @Test
    void realDispatchPreservesBadRequestForMixedUppercaseShortMalformedAndOverflowIds() throws Exception {
        var materialize = mock(DslMaterializeService.class);
        var realDocuments = new DslDocumentOperationsFacade(mock(com.taxonomy.dsl.export.TaxDslExportService.class),
                materialize,mock(com.taxonomy.architecture.repository.ArchitectureDslDocumentRepository.class),dslOps);
        var resolver = new DslReadWorkspaceContextResolver(workspace,state);
        var routedMvc = MockMvcBuilders.standaloneSetup(new DslDocumentApiController(realDocuments,dslOps,workspace,resolver),
                new DslApiController(dslOps,workspace,resolver)).build();
        for (String[] pair : List.of(new String[]{"a".repeat(40),"2"}, new String[]{"1","b".repeat(40)},
                new String[]{"A".repeat(40),"b".repeat(40)},new String[]{"abc","def"},
                new String[]{"malformed","2"},new String[]{"9223372036854775808","2"})) {
            for (String prefix : List.of("/api/dsl/diff/","/api/dsl/diff/semantic/")) {
                routedMvc.perform(get(prefix+pair[0]+"/"+pair[1])).andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.error").isString());
            }
        }
        verifyNoInteractions(materialize,dslOps);
    }

    private DslMaterializeService.MaterializeResult result(boolean valid) {
        return new DslMaterializeService.MaterializeResult(valid,valid?List.of():List.of("invalid"),List.of("warning"),2,1,42L);
    }
    @Test
    void materializeIncrementalCoversSuccessWithDocumentBranchAndBadRequest() {
        DslMaterializeService.MaterializeResult result =
                new DslMaterializeService.MaterializeResult(true, List.of(), List.of("warning"), 2, 1, 42L);
        com.taxonomy.architecture.model.ArchitectureDslDocument document =
                new com.taxonomy.architecture.model.ArchitectureDslDocument();
        document.setBranch("review");
        when(documents.materializeIncremental(1L, 2L)).thenReturn(result);
        when(documents.findDocumentById(2L)).thenReturn(Optional.of(document));

        var success = controller.materializeIncremental(1L, 2L);
        assertThat(success.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(success.getBody()).containsEntry("relationsCreated", 2).containsEntry("hypothesesCreated", 1);
        verify(dslOps).getViewContext("review");

        reset(documents);
        when(documents.materializeIncremental(1L, 2L)).thenThrow(new IllegalArgumentException("missing document"));
        var failure = controller.materializeIncremental(1L, 2L);
        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(failure.getBody()).containsEntry("error", "missing document");
    }

}
