package com.taxonomy.composition.architecture;

import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AnalysisSnapshotAdoptionControllerTest {
    @Test void inaccessibleRetainedSnapshotIsAnHttpNotFound() throws Exception {
        var service = mock(AnalysisSnapshotAdoptionService.class);
        var resolver = mock(WorkspaceResolver.class);
        when(service.preview(any(),any())).thenThrow(com.taxonomy.portfolio.service.PortfolioException.notFound("Snapshot unavailable"));
        var mvc = standaloneSetup(new AnalysisSnapshotAdoptionController(service,resolver)).build();
        var context = new Context("repo-a","copy-a","draft",null,0,"alice","PRIVATE_WORKSPACE");
        var metadata = new Metadata(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Adopt frozen source");
        String body = JsonMapper.builder().build().writeValueAsString(new AnalysisSnapshotAdoptionService.Request(context,metadata,
                new AnalysisSnapshotAdoptionService.SourceRef("repo-a","__shared__","draft",1L,2L,3L,"snapshot"),null));
        mvc.perform(post("/api/architecture/editor/snapshot-adoptions/preview").contentType("application/json").content(body)
                .header(HttpHeaders.IF_MATCH,"\"workspace-revision-0\"")).andExpect(status().isNotFound());
    }

    @Test void exactRevisionIfMatchIsRequiredAndIfNoneMatchCannotSubstitute() throws Exception {
        var service = mock(AnalysisSnapshotAdoptionService.class);
        var resolver = mock(WorkspaceResolver.class);
        var mvc = standaloneSetup(new AnalysisSnapshotAdoptionController(service,resolver)).build();
        var context = new Context("repo-a","copy-a","draft",null,0,"alice","PRIVATE_WORKSPACE");
        var metadata = new Metadata(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Adopt frozen source");
        var request = new AnalysisSnapshotAdoptionService.Request(context,metadata,
                new AnalysisSnapshotAdoptionService.SourceRef("repo-a","__shared__","draft",1L,2L,3L,"snapshot"),null);
        String body = JsonMapper.builder().build().writeValueAsString(request);
        for (String path : java.util.List.of("/api/architecture/editor/snapshot-adoptions/preview","/api/architecture/editor/snapshot-adoptions")) {
            mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().is(428));
            mvc.perform(post(path).contentType("application/json").content(body).header(HttpHeaders.IF_MATCH,"\"workspace-revision-1\""))
                    .andExpect(status().isBadRequest());
            mvc.perform(post(path).contentType("application/json").content(body).header(HttpHeaders.IF_MATCH,"\"workspace-revision-0\"")
                    .header(HttpHeaders.IF_NONE_MATCH,"*")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service,resolver);
    }
}
