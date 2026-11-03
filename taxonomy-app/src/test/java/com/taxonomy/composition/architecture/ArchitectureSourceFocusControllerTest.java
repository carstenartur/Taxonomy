package com.taxonomy.composition.architecture;

import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ArchitectureSourceFocusControllerTest {
    private final ArchitectureSourceFocusService service = mock(ArchitectureSourceFocusService.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private MockMvc mvc;
    private final String request = """
      {"source":{"kind":"ANALYSIS_DRAFT","repositoryId":"repo","workspaceScopeKey":"copy","branch":"draft","version":4},
       "sourceId":"CP-1000","candidateLimit":8,"useAi":false,"language":"en"}
      """;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ArchitectureSourceFocusController(service,resolver)).build();
    }
    @Test void readOnlyResponseUsesSourceIdentityAndHashWithoutEditorRevisionHeaders() throws Exception {
        var source = new ArchitectureSourceFocusDtos.Source("ANALYSIS_DRAFT","repo","copy","draft",4L,null,null,null,null);
        when(service.investigate(any(),any())).thenReturn(new ArchitectureSourceFocusDtos.Response(source,"CP-1000","ab".repeat(32),"LOCAL_TEXT_UNASSESSED","NO_CANDIDATES",0,0,List.of()));
        mvc.perform(post("/api/architecture/focus/suggestions").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("X-Taxonomy-Semantic-Revision"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.source.kind").value("ANALYSIS_DRAFT"))
                .andExpect(jsonPath("$.source.version").value(4))
                .andExpect(jsonPath("$.context").doesNotExist());
    }
    @Test void invalidPinsOrOptionsNeverReachSourceOrProviderWork() throws Exception {
        for (String invalid : List.of(request.replace("\"version\":4","\"version\":-1"),request.replace("\"candidateLimit\":8","\"candidateLimit\":25"),request.replace("ANALYSIS_DRAFT","WORKSPACE_REVISION"))) {
            mvc.perform(post("/api/architecture/focus/suggestions").contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service,resolver);
    }
}
