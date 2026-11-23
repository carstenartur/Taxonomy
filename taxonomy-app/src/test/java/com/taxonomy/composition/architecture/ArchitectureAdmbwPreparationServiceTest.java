package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.admbw.AdmbwPreparation;
import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.ArchitectureCommandPort.*;
import com.taxonomy.extension.api.integration.AdmbwQeaExport;
import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.plugin.ExtensionCatalog;
import com.taxonomy.extension.api.plugin.ExtensionLease;
import com.taxonomy.extension.api.plugin.ExtensionUnavailableException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real workspace journal/transaction/checkpoint; native SQLite validation is owned by plugin tests. */
@SpringBootTest
@WithMockUser(username = "alice", roles = {"USER", "ARCHITECT", "ADMIN"})
@Tag("ci-app-shard-spring-context-a")
class ArchitectureAdmbwPreparationServiceTest extends LifecycleTestSupport {
    private static final String ROOT = "{10000000-0000-0000-0000-000000000001}";
    private static final String CAPABILITY = "{10000000-0000-0000-0000-000000000002}";
    private static final String SUBJECT = "{10000000-0000-0000-0000-000000000003}";
    private static final byte[] BYTES = "QEA byte identity tested independently of plugin parsing".getBytes(StandardCharsets.UTF_8);
    private static final String TEXT = "The training portal shall distribute approved learning materials between sites.";
    private ExtensionCatalog catalog;
    private AdmbwQeaExport inspector;
    private ArchitectureAdmbwPreparationService service;
    private String basisHash;
    @Autowired ObjectMapper json;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void inspector() throws Exception {
        basisHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
        catalog = mock(ExtensionCatalog.class);
        inspector = mock(AdmbwQeaExport.class);
        ExtensionLease<IntegrationConnectorExtension> lease = mock(ExtensionLease.class);
        when(catalog.acquire(AdmbwQeaExport.KEY, IntegrationConnectorExtension.class)).thenReturn(lease);
        when(lease.extension()).thenReturn(inspector);
        when(inspector.supportsBasisInspection()).thenReturn(true);
        when(inspector.inspectAdmbwBasis(any())).thenReturn(new AdmbwQeaExport.Basis(basisHash,
                "{10000000-0000-0000-0000-000000000004}", List.of(new AdmbwQeaExport.Root(ROOT, "Example model")),
                List.of(new AdmbwQeaExport.Reference(CAPABILITY, "Capability", "Cross-site training", "Native capability",
                                List.of("Example model", "Capabilities"), ROOT, null),
                        new AdmbwQeaExport.Reference(SUBJECT, "OperationalArchitecture", "Training", "Native training subject",
                                List.of("Example model", "Operational architectures"), ROOT, null))));
        service = new ArchitectureAdmbwPreparationService(editor, lifecycle, catalog, transactions);
    }

    @Test
    void previewDoesNotWriteAndReviewedAcceptanceCreatesOneVersionBoundDelivery() throws Exception {
        var context = workspace("admbw-preview");
        var request = request(context, "PREPARE");
        var before = capture(context);
        var preview = service.preview(context, request, BYTES);
        assertUnchanged(context, before);
        assertThat(preview.requirementVersion()).isEqualTo(1);
        assertThat(preview.requirementContentHash()).hasSize(64);
        assertThat(preview.assessment().capabilityToService().issues()).isEmpty();
        assertThat(preview.assessment().requirementDerivation().issues()).isEmpty();
        rejected("ADMBW_REVIEW_REQUIRED", () -> service.accept(context, reviewed(request, preview.fingerprint(), false, true), BYTES));
        rejected("ADMBW_REVIEW_REQUIRED", () -> service.accept(context, reviewed(request, preview.fingerprint(), true, false), BYTES));
        assertUnchanged(context, before);
        var accepted = service.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES);
        assertThat(accepted.state()).isEqualTo("CHECKPOINTED");
        assertThat(accepted.context().revision()).isEqualTo(request.context().revision() + 1);
        var blocks = ArchitectureSemanticPatch.index(editor.read(context, accepted.context().commit()).dsl());
        assertThat(blocks).containsKey("view:" + preview.viewId());
        assertThat(blocks.get("requirement:" + preview.requirementId()).property("text")).isEqualTo(TEXT);
        assertThat(blocks.get("element:recorder").property("x-source-evidence")).isEqualTo("Original authored model");
        assertThat(blocks.get("relation:recorder DEPENDS_ON exporter").property("status")).isEqualTo("accepted");
    }

    @Test
    void changedBasisAndPreviewLeaveTheWholeOperationUnchanged() throws Exception {
        var context = workspace("admbw-basis");
        var request = request(context, "BASIS");
        var preview = service.preview(context, request, BYTES);
        var before = capture(context);
        rejected("ADMBW_BASIS_CHANGED", () -> service.accept(context, reviewed(request, preview.fingerprint(), true, true), new byte[]{1, 2, 3}));
        rejected("PREVIEW_CHANGED", () -> service.accept(context, reviewed(request, "0".repeat(64), true, true), BYTES));
        assertUnchanged(context, before);
    }

    @Test
    void previewAndLockedAcceptanceUseTheResolvedCatalogueRules() throws Exception {
        var context = workspace("admbw-catalogue");
        var request = request(context, "RULES");
        var preview = service.preview(context, request, BYTES);
        var restricted = new com.taxonomy.dsl.model.CatalogueDefinition("restricted", false, List.of(
                new com.taxonomy.dsl.model.CatalogueDefinition.Root("Services", "CR", "CoreService"),
                new com.taxonomy.dsl.model.CatalogueDefinition.Root("Capabilities", "CP", "Capability")), List.of());
        service.setCatalogueDefinitionResolver(model -> restricted);
        var before = capture(context);
        rejected("INCOMPATIBLE_RELATION", () -> service.preview(context, request, BYTES));
        rejected("INCOMPATIBLE_RELATION", () -> service.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES));
        assertUnchanged(context, before);
    }

    @Test
    void sourceRevisionChangesInvalidateAcceptanceWithoutPartialAssignments() throws Exception {
        var context = workspace("admbw-stale");
        var request = request(context, "STALE");
        var preview = service.preview(context, request, BYTES);
        edit(context, "dashboard", "Concurrent architecture edit");
        var before = capture(context);
        assertThatThrownBy(() -> service.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES))
                .isInstanceOf(com.taxonomy.editor.persistence.EditorJournal.RevisionConflict.class);
        assertUnchanged(context, before);
    }

    @Test
    void lostResponseRetryReturnsOriginalCheckpointEvenAfterANewerEditAndPluginUnload() throws Exception {
        var context = workspace("admbw-retry");
        var request = request(context, "RETRY");
        var preview = service.preview(context, request, BYTES);
        var reviewed = reviewed(request, preview.fingerprint(), true, true);
        var first = service.accept(context, reviewed, BYTES);
        edit(context, "dashboard", "Later edit must survive the receipt retry");
        var before = capture(context);
        when(catalog.acquire(AdmbwQeaExport.KEY, IntegrationConnectorExtension.class))
                .thenThrow(new ExtensionUnavailableException(AdmbwQeaExport.KEY));
        var recovered = service.recover(context, request.metadata().commandId(), false);
        assertThat(recovered.publication().checkpoint().commitId()).isEqualTo(first.checkpoint().commitId());
        assertThat(recovered.delivery().basisSha256()).isEqualTo(basisHash);
        assertThat(recovered.delivery().viewId()).isEqualTo(preview.viewId());
        assertThat(recovered.delivery().rootPackageGuid()).isEqualToIgnoringCase(request.selection().rootPackageGuid());
        var other = workspace("outside-receipt");
        rejected("NOT_FOUND", () -> service.recover(other, request.metadata().commandId(), true));
        var replay = service.accept(context, reviewed, BYTES);
        assertThat(replay.state()).isEqualTo("CHECKPOINTED");
        assertThat(replay.context()).isEqualTo(first.context());
        assertThat(replay.checkpoint().commitId()).isEqualTo(first.checkpoint().commitId());
        assertUnchanged(context, before);
        assertThat(editor.read(context, null).dsl()).contains("Later edit must survive");
    }

    @Test
    void restoreReadsPendingReceiptAndResumesOnlyTheOriginalPublicationWithoutApplyingAgain() throws Exception {
        var context = workspace("admbw-pending-restore");
        var request = request(context, "RESTORE");
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        var writer = new com.taxonomy.editor.ArchitectureCheckpointWriter() {
            @Override public Result write(com.taxonomy.workspace.storage.DslGitRepository repository, String scope, String branch,
                    com.taxonomy.editor.persistence.EditorJournal.Checkpoint intent) throws java.io.IOException {
                if (failOnce.getAndSet(false)) throw new java.io.IOException("Publication outage");
                return super.write(repository, scope, branch, intent);
            }
        };
        var recoveringEditor = new com.taxonomy.editor.ArchitectureEditorService(git, journal, writer);
        var recoveringLifecycle = new ArchitectureLifecycleService(recoveringEditor, projects, portfolio, workspaces,
                repositories, membership, transactions);
        var recoveringService = new ArchitectureAdmbwPreparationService(recoveringEditor, recoveringLifecycle, catalog, transactions);
        var preview = recoveringService.preview(context, request, BYTES);
        var pending = recoveringService.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES);
        assertThat(pending.state()).isEqualTo("CHECKPOINT_PENDING");
        var before = capture(context);
        var id = request.metadata().commandId();
        assertThat(recoveringService.recover(context, id, false).publication().state()).isEqualTo("CHECKPOINT_PENDING");
        rejected("NOT_FOUND", () -> recoveringEditor.resumeCheckpoint(context, java.util.UUID.randomUUID().toString()));
        assertUnchanged(context, before);
        var result = recoveringService.recover(context, id, true);
        assertThat(result.publication().state()).isEqualTo("CHECKPOINTED");
        assertThat(result.publication().context().revision()).isEqualTo(pending.context().revision());
        assertThat(journal.read(context).operations()).isEqualTo(before.journal().operations());
        assertThat(recoveringService.recover(context, id, true).publication().checkpoint().commitId())
                .isEqualTo(result.publication().checkpoint().commitId());
    }

    @Test
    void reusingAcceptedCommandIdentityWithDifferentChoicesIsRejected() throws Exception {
        var context = workspace("admbw-command");
        var request = request(context, "COMMAND");
        var preview = service.preview(context, request, BYTES);
        service.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES);
        var before = capture(context);
        rejected("COMMAND_ID_REUSED", () -> service.accept(context, reviewed(request, "1".repeat(64), true, true), BYTES));
        assertUnchanged(context, before);
    }

    @Test
    void freeTextRecordDelimitersCannotAliasDifferentReviewedChoicesOnRetry() throws Exception {
        var context = workspace("admbw-delimiter");
        var original = request(context, "DELIMITER", TEXT + " foo, evidenceQuote=bar");
        var s = original.selection();
        var first = new AdmbwPreparation.Selection(s.projectCode(), s.rootPackageGuid(), s.serviceId(), s.capabilityGuid(),
                s.requirementId(), s.requirementType(), s.subjectGuid(), "Driver", "foo, evidenceQuote=bar");
        var second = new AdmbwPreparation.Selection(s.projectCode(), s.rootPackageGuid(), s.serviceId(), s.capabilityGuid(),
                s.requirementId(), s.requirementType(), s.subjectGuid(), "Driver, evidenceQuote=foo", "bar");
        assertThat(first).isNotEqualTo(second);
        assertThat(first.toString()).isEqualTo(second.toString());
        var request = new ArchitectureAdmbwPreparationService.Request(original.context(), original.metadata(), first,
                basisHash, null, false, false);
        var preview = service.preview(context, request, BYTES);
        service.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES);
        var different = new ArchitectureAdmbwPreparationService.Request(original.context(), original.metadata(), second,
                basisHash, preview.fingerprint(), true, true);
        var before = capture(context);
        rejected("COMMAND_ID_REUSED", () -> service.accept(context, different, BYTES));
        assertUnchanged(context, before);
    }

    @Test
    void anotherWorkspaceCannotUseTheReviewedRequestAndMissingPluginFailsClosed() throws Exception {
        var context = workspace("admbw-tenant");
        var request = request(context, "TENANT");
        var other = workspace("admbw-other");
        var before = capture(other);
        rejected("CONTEXT_CHANGED", () -> service.preview(other, request, BYTES));
        assertUnchanged(other, before);
        when(inspector.supportsBasisInspection()).thenReturn(false);
        rejected("ADMBW_INSPECTOR_UNAVAILABLE", () -> service.preview(context, request, BYTES));
    }

    @Test
    void multipartHttpRequiresExactRevisionAndPublishesNoStorePreviewWithoutMutation() throws Exception {
        var context = workspace("admbw-http");
        var request = request(context, "HTTP");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        var mvc = standaloneSetup(new ArchitectureAdmbwPreparationController(service, new LifecycleHttpContext(resolver), json))
                .setControllerAdvice(new ArchitectureLifecycleExceptionHandler()).build();
        var input = new MockMultipartFile("request", "request.json", "application/json", json.writeValueAsBytes(request));
        var basis = new MockMultipartFile("basis", "basis.qea", AdmbwQeaExport.MEDIA_TYPE, BYTES);
        var before = capture(context);
        mvc.perform(multipart("/api/architecture/editor/admbw/preview").file(input).file(basis))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(multipart("/api/architecture/editor/admbw/preview").file(input).file(basis)
                        .header("If-Match", "\"workspace-revision-9999\""))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/architecture/editor/admbw/preview").file(input).file(basis)
                        .header("If-Match", "\"workspace-revision-" + request.context().revision() + "\""))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.basisSha256").value(basisHash)).andExpect(jsonPath("$.requirementVersion").value(1));
        mvc.perform(multipart("/api/architecture/editor/admbw/preview")
                        .file(new MockMultipartFile("request", "review.json", "application/json", new byte[65537])).file(basis))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The ADMBw review exceeds 64 KiB"));
        mvc.perform(multipart("/api/architecture/editor/admbw/preview")
                        .file(new MockMultipartFile("request", "review.json", "application/json", "null".getBytes(StandardCharsets.UTF_8))).file(basis))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("The ADMBw review must be an object"));
        verify(resolver, never()).resolveCurrentRepositoryContext();
        assertUnchanged(context, before);
    }

    @Test
    void preparedOutputDisclosesAnUnboundNativePartWithoutPreventingIterativeCheckpoints() throws Exception {
        var context = workspace("admbw-native-issue");
        var request = request(context, "PART");
        var preview = service.preview(context, request, BYTES);
        service.accept(context, reviewed(request, preview.fingerprint(), true, true), BYTES);
        var existing = editor.read(context, null);
        var planning = com.taxonomy.dsl.planning.PlanningInformation.decode(
                ArchitectureSemanticPatch.index(existing.dsl()).get("requirement:" + preview.requirementId()).getExtensions()).getFirst();
        editor.execute(context, new Command(existing.context(), metadata("Add a planned realization role"),
                new SemanticCommand(new CreateArchitectureElement("unbound-native-role", "Component",
                        Map.of("title", "Planned realization role", "description", "Planned native role awaiting an owned EA Part binding", "x-admbw-type", "ResourceRole")))));
        var values = new java.util.HashMap<>(planning.values());
        values.put("realizer", "unbound-native-role");
        editor.execute(context, new Command(editor.read(context, null).context(), metadata("Review planned role before its native owner is bound"),
                new SemanticCommand(new com.taxonomy.dsl.command.ArchitectureCommand.SetRequirementPlanning(preview.requirementId(),
                        new com.taxonomy.dsl.planning.PlanningEntry(planning.id(), planning.profile(), planning.version(), "MANUAL", values)))));
        create(context, null, "PARTNEXT", "REQ-2", TEXT);
        var next = requirement(lifecycle.overview(context, null, null), "PARTNEXT", "REQ-2");
        var selected = request.selection();
        var nextSelection = new AdmbwPreparation.Selection(selected.projectCode(), selected.rootPackageGuid(), selected.serviceId(),
                selected.capabilityGuid(), next.id(), selected.requirementType(), selected.subjectGuid(), selected.constraintText(), selected.evidenceQuote());
        var nextRequest = new ArchitectureAdmbwPreparationService.Request(editor.read(context, null).context(), metadata("Review next requirement with a visible unresolved native role"),
                nextSelection, basisHash, null, false, false);
        var nextPreview = service.preview(context, nextRequest, BYTES);
        assertThat(nextPreview.assessment().requirementDerivation().issues()).isEmpty();
        assertThat(nextPreview.qeaIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("ADMBW_QEA_PART_OWNER");
            assertThat(issue.object()).isEqualTo("element:unbound-native-role");
        });
        assertThat(service.accept(context, reviewed(nextRequest, nextPreview.fingerprint(), true, true), BYTES).state()).isEqualTo("CHECKPOINTED");
        assertThat(preview.qeaIssues()).isEmpty();
    }

    private ArchitectureAdmbwPreparationService.Request request(RepositoryContext context, String project) throws Exception {
        return request(context, project, TEXT);
    }

    private ArchitectureAdmbwPreparationService.Request request(RepositoryContext context, String project, String text) throws Exception {
        create(context, null, project, "REQ-1", text);
        editor.execute(context, new Command(editor.read(context, null).context(), metadata("Add selected authored service"),
                new SemanticCommand(new CreateArchitectureElement("training-service", "CoreService",
                        Map.of("title", "Training distribution service", "description", "Distributes approved training materials")))));
        var requirement = lifecycle.overview(context, null, null).requirements().getFirst();
        var selection = new AdmbwPreparation.Selection(project, ROOT, "training-service", CAPABILITY, requirement.id(),
                "FunctionalRequirement", SUBJECT, "Training sites need consistent approved learning material.", "distribute approved learning materials between sites");
        return new ArchitectureAdmbwPreparationService.Request(editor.read(context, null).context(),
                metadata("Reviewed service contribution and operative driver against the exact training requirement and example references"),
                selection, basisHash, null, false, false);
    }

    private static ArchitectureAdmbwPreparationService.Request reviewed(ArchitectureAdmbwPreparationService.Request original,
            String fingerprint, boolean relationship, boolean derivation) {
        return new ArchitectureAdmbwPreparationService.Request(original.context(), original.metadata(), original.selection(),
                original.basisSha256(), fingerprint, relationship, derivation);
    }
}
