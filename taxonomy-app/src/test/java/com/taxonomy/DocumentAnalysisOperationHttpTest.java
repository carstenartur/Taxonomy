package com.taxonomy;

import com.taxonomy.backup.PrincipalId;
import com.taxonomy.composition.analysis.AnalysisAdapterObservationController;
import com.taxonomy.dto.DocumentParseResult;
import com.taxonomy.provenance.config.DocumentImportLimits;
import com.taxonomy.provenance.controller.DocumentImportController;
import com.taxonomy.provenance.service.*;
import com.taxonomy.security.service.PrincipalIdentityService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DocumentAnalysisOperationHttpTest {
    @Test void duplicateUploadPollsAndCancellationFencesLatePhysicalResult() throws Exception {
        try (var fixture = new Fixture(); var pool = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            when(fixture.analysis.extractWithAi(anyString(),anyString())).thenAnswer(call -> {
                entered.countDown(); assertTrue(release.await(15,TimeUnit.SECONDS)); return List.of();
            });
            var original = pool.submit(() -> {
                fixture.login();
                try { return fixture.mvc.perform(multipart("/api/documents/extract-ai").file(fixture.file)
                        .header("Idempotency-Key","active-command")).andReturn().getResponse(); }
                finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                var accepted = fixture.mvc.perform(multipart("/api/documents/extract-ai").file(fixture.file)
                        .header("Idempotency-Key","active-command")).andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.state").value("RUNNING")).andReturn().getResponse();
                String location = accepted.getHeader("Content-Location");
                assertNotNull(location);
                fixture.mvc.perform(get(location)).andExpect(status().isAccepted());
                fixture.mvc.perform(post(location+"/cancel")).andExpect(status().isConflict()).andExpect(jsonPath("$.state").value("CANCELLED"));
                release.countDown();
                assertEquals(409, original.get(10,TimeUnit.SECONDS).getStatus());
                fixture.mvc.perform(get(location)).andExpect(status().isConflict()).andExpect(jsonPath("$.state").value("CANCELLED"));
                verify(fixture.analysis,times(1)).extractWithAi(anyString(),anyString());
            } finally { release.countDown(); }
        }
    }

    @Test void nativeMappingReplaysOfflineAndSourceAndPrincipalGuardObservation() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.analysis.mapRegulationToArchitecture(anyString())).thenReturn(List.of());
            var result = fixture.mvc.perform(multipart("/api/documents/map-regulation").file(fixture.file))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.matches").isArray()).andReturn().getResponse();
            String id = result.getHeader("X-Analysis-Operation-Id");
            String location = result.getHeader("Content-Location"); assertNotNull(id); assertNotNull(location);
            when(fixture.analysis.providerAvailable()).thenReturn(false);
            var repeat = fixture.mvc.perform(multipart("/api/documents/map-regulation").file(fixture.file))
                    .andExpect(status().isOk()).andExpect(header().string("X-Analysis-Operation-Id",id)).andReturn().getResponse();
            assertEquals(result.getContentAsString(),repeat.getContentAsString());
            fixture.mvc.perform(get(location)).andExpect(status().isOk()).andExpect(content().json(result.getContentAsString()));
            fixture.commit.set("b".repeat(40));
            fixture.mvc.perform(get(location)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_SOURCE"));
            fixture.actor.set(PrincipalId.create());
            fixture.mvc.perform(get(location)).andExpect(status().isNotFound());
            fixture.mvc.perform(get("/api/analysis-adapters/"+UUID.randomUUID())).andExpect(status().isNotFound());
            fixture.mvc.perform(multipart("/api/documents/map-regulation").file(fixture.file))
                    .andExpect(status().isOk()).andExpect(header().doesNotExist("X-Analysis-Operation-Id"));
            verify(fixture.analysis,times(1)).mapRegulationToArchitecture(anyString());
        }
    }

    @Test void inaccessibleSourceAfterDispatchRetainsUnknownOutcomeAcrossIdenticalRetry() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.analysis.mapRegulationToArchitecture(anyString())).thenAnswer(call -> {
                fixture.commit.set(null); return List.of();
            });
            fixture.mvc.perform(multipart("/api/documents/map-regulation").file(fixture.file)).andExpect(status().isConflict());
            fixture.commit.set("a".repeat(40));
            fixture.mvc.perform(multipart("/api/documents/map-regulation").file(fixture.file))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("OUTCOME_UNKNOWN"));
            verify(fixture.analysis,times(1)).mapRegulationToArchitecture(anyString());
        }
    }

    static final class Fixture implements AutoCloseable {
        final ClusterAnalysisDatabaseContract db = ClusterAnalysisDatabaseContract.hsql();
        final AtomicReference<PrincipalId> actor = new AtomicReference<>(PrincipalId.create());
        final AtomicReference<String> commit = new AtomicReference<>("a".repeat(40));
        final DocumentAnalysisService analysis = mock(DocumentAnalysisService.class);
        final MockMultipartFile file = new MockMultipartFile("file","notice.pdf","application/pdf",new byte[]{1});
        final org.springframework.test.web.servlet.MockMvc mvc;

        Fixture() throws Exception {
            var workspace = mock(WorkspaceResolver.class); var git = mock(WorkspaceDslReadPort.class);
            var handle = mock(WorkspaceDslReadPort.RepositoryRead.class); var identities = mock(PrincipalIdentityService.class);
            when(workspace.resolveCurrentRepositoryContextForRead()).thenReturn(RepositoryContext.centralRead("repo","main","alice"));
            when(git.openRead(any())).thenReturn(handle);
            when(handle.currentHead()).thenAnswer(call -> commit.get());
            when(identities.require(any())).thenAnswer(call -> actor.get());
            var operations = db.adapterGateway(workspace,git,identities);
            var parser = mock(DocumentParserService.class); var parsed = new DocumentParseResult();
            parsed.setRawTextPreview("A transport requirement"); parsed.setFileName("notice.pdf"); parsed.setTotalPages(1);
            when(parser.parse(any())).thenReturn(parsed); when(parser.computeContentHash(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn("a".repeat(64));
            when(analysis.providerAvailable()).thenReturn(true);
            var controller = new DocumentImportController(parser,mock(SourceProvenanceService.class),
                    mock(DocumentProvenanceCommandService.class),analysis,new DocumentImportLimits(),operations);
            mvc = MockMvcBuilders.standaloneSetup(controller,new AnalysisAdapterObservationController(operations)).build();
            login();
        }
        void login() { SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice","unused","ROLE_USER")); }
        public void close() { SecurityContextHolder.clearContext(); db.close(); }
    }
}
