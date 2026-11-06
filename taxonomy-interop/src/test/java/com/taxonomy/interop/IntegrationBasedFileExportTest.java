package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureCommand.CreateArchitectureElement;
import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.extension.api.integration.IntegrationConnectorExtension;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import com.taxonomy.interop.oslc.OslcTransport;
import com.taxonomy.interop.persistence.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.*;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The fixture is a non-vendor binary format. Host guarantees cannot depend on a Sparx profile name. */
class IntegrationBasedFileExportTest {
    @TempDir Path directory;
    private static final RepositoryContext CONTEXT = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
    private static final UUID CONNECTION = UUID.fromString("97166d68-1078-48e0-9e06-f24a046055a2");
    private static final String MODEL = "{CCBE0F5D-D4F2-32FB-8283-E7D990608A08}";
    private static final String ELEMENT = "{BE7FBE2E-E4C1-396F-966D-19B8A9A18CE6}";
    private static final byte[] TEMPLATE = "template-without-a-model-identity".getBytes(StandardCharsets.UTF_8);

    @Test void newProjectFreezesHostGuidsAndBinaryBasisBeforeReview() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE);
            assertEquals(MODEL, preview.document().metadata().get("identifier"));
            assertEquals(ELEMENT, preview.document().artifacts().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).findFirst().orElseThrow().id());
            assertEquals("Node", preview.document().artifacts().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).findFirst().orElseThrow().type());
            assertEquals(CONNECTION, preview.fileExport().identityNamespace());
            assertArrayEquals(TEMPLATE, fixture.store.locked(CONTEXT, CONNECTION, s -> s.base(preview.fileExport().baseFileId()).content()));
            assertFalse(fixture.json.write(preview).contains(Base64.getEncoder().encodeToString(TEMPLATE)));
            assertEquals(1, fixture.service.overview(CONTEXT, CONNECTION).fileBases().size());
            assertEquals(Set.of(FileExportMode.NEW_PROJECT), fixture.service.overview(CONTEXT, CONNECTION).fileExportModes());
        }
    }

    @Test void separateConnectionsReserveIndependentModelRootAndObjectIdentities() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            UUID operation = UUID.randomUUID();
            var first = fixture.preview(operation, TEMPLATE);
            var retry = fixture.preview(operation, TEMPLATE);
            assertEquals(first.document(), retry.document());
            String root = first.document().metadata().get("rootIdentifier");
            assertNotNull(root);
            assertTrue(root.matches("\\{[0-9A-F-]{36}\\}"));
            assertNotEquals(MODEL, root);
            UUID other = UUID.randomUUID(); fixture.create(other);
            var second = fixture.service.previewExport(CONTEXT, other,
                    new IntegrationService.BasedExportRequest(UUID.randomUUID(), fixture.state(), null,
                            FileExportMode.NEW_PROJECT, "application/example", null), TEMPLATE);
            assertNotEquals(first.document().metadata().get("identifier"), second.document().metadata().get("identifier"));
            assertNotEquals(root, second.document().metadata().get("rootIdentifier"));
            assertNotEquals(first.document().artifacts().getFirst().id(), second.document().artifacts().getFirst().id());
        }
    }

    @Test void legacyJsonExportCannotBypassTheExplicitBasisRequirement() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var failure = assertThrows(IntegrationProblem.class, () -> fixture.service.previewExport(CONTEXT, CONNECTION,
                    new IntegrationService.ExportRequest(UUID.randomUUID(), fixture.state(), null)));
            assertEquals("FILE_BASE_REQUIRED", failure.code());
            assertEquals(428, failure.status());
            assertEquals(0L, fixture.jdbc.queryForObject("select count(*) from interop_operation", Long.class));
        }
    }

    @Test void rejectingTheReviewedRequiredRootCannotSilentlyRecreateItInTheDeliveredFile() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE);
            String root = "SPECIFICATION:" + preview.document().metadata().get("rootIdentifier");
            var decisions = new HashMap<>(accept(preview).decisions());
            var rootChange = preview.changes().stream().filter(change -> change.externalId().equals(root)).findFirst().orElseThrow();
            decisions.put(rootChange.id(), Decision.REJECT);
            var failure = assertThrows(IntegrationProblem.class, () -> fixture.service.prepareFile(CONTEXT, CONNECTION,
                    new ReviewedChangeSet(preview.id(), preview.fingerprint(), decisions, "Do not export this root")));
            assertEquals("FILE_REQUIRED_STRUCTURE", failure.code());
            assertEquals(422, failure.status());
            assertEquals(OperationStatus.PREVIEWED, fixture.service.operation(CONTEXT, CONNECTION, preview.id()).status());
            assertEquals(0L, fixture.jdbc.queryForObject("select count(*) from interop_file where kind='RESULT'", Long.class));
        }
    }

    @Test void reusedOperationCannotChangeBasisOrModeAndBasisHandleCannotCrossItsOwner() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            UUID operation = UUID.randomUUID(); var preview = fixture.preview(operation, TEMPLATE);
            assertEquals(preview.id(), fixture.preview(operation, TEMPLATE).id());
            assertEquals("OPERATION_ID_REUSED", assertThrows(IntegrationProblem.class,
                    () -> fixture.preview(operation, "different template".getBytes(StandardCharsets.UTF_8))).code());
            var changedMode = new IntegrationService.BasedExportRequest(operation, fixture.state(), null, FileExportMode.UPDATE_COPY, "application/example", null);
            assertEquals("OPERATION_ID_REUSED", assertThrows(IntegrationProblem.class,
                    () -> fixture.service.previewExport(CONTEXT, CONNECTION, changedMode, TEMPLATE)).code());
            UUID other = UUID.randomUUID(); fixture.create(other);
            var reuse = new IntegrationService.BasedExportRequest(UUID.randomUUID(), fixture.state(), null, FileExportMode.NEW_PROJECT,
                    "application/example", preview.fileExport().baseFileId());
            assertEquals(404, assertThrows(IntegrationProblem.class,
                    () -> fixture.service.previewExport(CONTEXT, other, reuse, null)).status());
            var bob = RepositoryContext.workspace("repo", "workspace", "draft", "bob");
            assertEquals(404, assertThrows(IntegrationProblem.class,
                    () -> fixture.store.locked(bob, CONNECTION, s -> s.base(preview.fileExport().baseFileId()))).status());
        }
    }

    @Test void retainedBasisCanStartANewPreviewWithoutUploadingItsBytesAgain() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var first = fixture.preview(UUID.randomUUID(), TEMPLATE);
            var reuse = new IntegrationService.BasedExportRequest(UUID.randomUUID(), fixture.state(), null, FileExportMode.NEW_PROJECT,
                    "application/example", first.fileExport().baseFileId());
            var next = fixture.service.previewExport(CONTEXT, CONNECTION, reuse, null);
            assertEquals(first.fileExport().baseFileId(), next.fileExport().baseFileId());
            assertEquals(first.document().artifacts(), next.document().artifacts());
            assertEquals(1L, fixture.jdbc.queryForObject("select count(*) from interop_file", Long.class));
        }
    }

    @Test void changedStoredBytesCannotBeUsedForReviewOrDownloaded() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE);
            fixture.jdbc.update("update interop_file set content=? where id=?", new byte[]{1, 2, 3}, preview.fileExport().baseFileId().toString());
            assertEquals("FILE_CONTENT_CHANGED", assertThrows(IntegrationProblem.class,
                    () -> fixture.service.prepareFile(CONTEXT, CONNECTION, accept(preview))).code());
            assertEquals(OperationStatus.PREVIEWED, fixture.service.operation(CONTEXT, CONNECTION, preview.id()).status());
            assertEquals(0L, fixture.jdbc.queryForObject("select count(*) from interop_file where kind='RESULT'", Long.class));
            fixture.jdbc.update("update interop_file set content=? where id=?", TEMPLATE, preview.fileExport().baseFileId().toString());
            var completed = fixture.service.prepareFile(CONTEXT, CONNECTION, accept(preview));
            fixture.jdbc.update("update interop_file set content=? where id=?", new byte[]{4, 5, 6}, completed.fileExport().outputFileId().toString());
            assertEquals("FILE_CONTENT_CHANGED", assertThrows(IntegrationProblem.class,
                    () -> fixture.service.file(CONTEXT, CONNECTION, preview.id())).code());
        }
    }

    @Test void retainedArtifactBindingUsesValuesAcrossJsonMapOrderChanges() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE);
            var metadata = new TreeMap<>(preview.document().metadata());
            metadata.put("taxonomy.plugin.id", "example.plugin");
            metadata.put("taxonomy.plugin.version", "1");
            metadata.put("taxonomy.plugin.sha256", "a".repeat(64));
            var document = new ExchangeDocument(preview.document().profile(), preview.document().profileVersion(), null, false, "",
                    preview.document().artifacts(), preview.document().relations(), preview.document().placements(), metadata, List.of());
            var binding = IntegrationFileBinding.plugin(document);
            var keys = new ArrayList<>(binding.keySet()); Collections.reverse(keys);
            var reordered = new LinkedHashMap<String, String>(); keys.forEach(key -> reordered.put(key, binding.get(key)));
            assertNotEquals(fixture.json.write(binding), fixture.json.write(reordered));
            fixture.jdbc.update("update interop_operation set document_json=? where id=?", fixture.json.write(document), preview.id().toString());
            fixture.jdbc.update("update interop_file set binding_json=? where id=?", fixture.json.write(reordered), preview.fileExport().baseFileId().toString());
            fixture.store.locked(CONTEXT, CONNECTION, session -> {
                assertArrayEquals(TEMPLATE, session.base(preview.fileExport().baseFileId()).content());
                session.requireBaseBinding(preview.fileExport().baseFileId(), document); return null;
            });
            reordered.put("taxonomy.plugin.sha256", "b".repeat(64));
            fixture.jdbc.update("update interop_file set binding_json=? where id=?", fixture.json.write(reordered), preview.fileExport().baseFileId().toString());
            assertEquals("FILE_BINDING_CHANGED", assertThrows(IntegrationProblem.class, () -> fixture.store.locked(CONTEXT, CONNECTION,
                    session -> session.base(preview.fileExport().baseFileId()))).code());
        }
    }

    @Test void reviewedOutputSurvivesRestartAndPluginAbsenceWithoutJsonBinaryOrCheckpointPromotion() throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("files").toAbsolutePath() + ";shutdown=true";
        UUID operation; byte[] delivered;
        try (var fixture = new Fixture(url, true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE); operation = preview.id();
            var result = fixture.service.prepareFile(CONTEXT, CONNECTION, accept(preview));
            assertEquals(OperationStatus.COMPLETED, result.status());
            assertNotNull(result.fileExport().outputFileId());
            delivered = fixture.service.file(CONTEXT, CONNECTION, operation).content();
            assertNotNull(fixture.json.read(new String(delivered, StandardCharsets.UTF_8), ExchangeDocument.class));
            assertNull(fixture.store.checkpoint(CONTEXT, CONNECTION));
            assertNull(fixture.jdbc.queryForObject("select result_file_json from interop_operation where id=?", String.class, operation.toString()));
            assertEquals(2L, fixture.jdbc.queryForObject("select count(*) from interop_file", Long.class));
            assertEquals(0L, fixture.jdbc.queryForObject("select count(*) from interop_checkpoint", Long.class));
        }
        try (var restarted = new Fixture(url, false)) {
            assertArrayEquals(delivered, restarted.service.file(CONTEXT, CONNECTION, operation).content());
            assertEquals(OperationStatus.COMPLETED, restarted.service.operation(CONTEXT, CONNECTION, operation).status());
            assertTrue(restarted.service.overview(CONTEXT, CONNECTION).fileExportModes().isEmpty());
            assertEquals(1, restarted.service.overview(CONTEXT, CONNECTION).fileBases().size());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void completedResultBindingIsCheckedAfterRestartWithOrWithoutThePlugin(boolean installed) throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("result-binding").toAbsolutePath() + ";shutdown=true";
        UUID operation, resultFile; byte[] delivered;
        try (var fixture = new Fixture(url, true, true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE);
            var completed = fixture.service.prepareFile(CONTEXT, CONNECTION, accept(preview));
            assertEquals("a".repeat(64), completed.document().metadata().get("taxonomy.plugin.sha256"));
            operation = completed.id(); resultFile = completed.fileExport().outputFileId();
            delivered = fixture.service.file(CONTEXT, CONNECTION, operation).content();
        }
        try (var restarted = new Fixture(url, installed, true)) {
            assertArrayEquals(delivered, restarted.service.file(CONTEXT, CONNECTION, operation).content());
            String encoded = restarted.jdbc.queryForObject("select binding_json from interop_file where id=?", String.class, resultFile.toString());
            var binding = restarted.json.read(encoded, Map.class);
            var keys = new ArrayList<>(binding.keySet()); Collections.reverse(keys);
            var reordered = new LinkedHashMap<Object, Object>(); keys.forEach(key -> reordered.put(key, binding.get(key)));
            String reorderedJson = restarted.json.write(reordered);
            assertNotEquals(encoded, reorderedJson);
            restarted.jdbc.update("update interop_file set binding_json=? where id=?", reorderedJson, resultFile.toString());
            assertArrayEquals(delivered, restarted.service.file(CONTEXT, CONNECTION, operation).content(), "Binding equality uses map values");
            reordered.put("taxonomy.plugin.sha256", "b".repeat(64));
            restarted.jdbc.update("update interop_file set binding_json=? where id=?", restarted.json.write(reordered), resultFile.toString());
            assertEquals("FILE_BINDING_CHANGED", assertThrows(IntegrationProblem.class,
                    () -> restarted.service.file(CONTEXT, CONNECTION, operation)).code());
        }
    }

    @ParameterizedTest @MethodSource("invalidResultBindings")
    void missingOrInvalidResultBindingFailsClosedWithoutThePlugin(String binding, Class<? extends RuntimeException> failureType) throws Exception {
        String url = "jdbc:hsqldb:file:" + directory.resolve("invalid-result-binding").toAbsolutePath() + ";shutdown=true";
        UUID operation, resultFile;
        try (var fixture = new Fixture(url, true, true)) {
            fixture.create(CONNECTION);
            var preview = fixture.preview(UUID.randomUUID(), TEMPLATE);
            var completed = fixture.service.prepareFile(CONTEXT, CONNECTION, accept(preview));
            operation = completed.id(); resultFile = completed.fileExport().outputFileId();
        }
        try (var restarted = new Fixture(url, false, true)) {
            restarted.jdbc.update("update interop_file set binding_json=? where id=?", binding, resultFile.toString());
            var failure = assertThrows(failureType, () -> restarted.service.file(CONTEXT, CONNECTION, operation));
            if (failure instanceof IntegrationProblem problem) assertEquals("FILE_BINDING_CHANGED", problem.code());
            else assertEquals("Cannot decode versioned integration evidence", failure.getMessage());
        }
    }

    private static Stream<Arguments> invalidResultBindings() {
        return Stream.of(Arguments.of("null", IntegrationProblem.class), Arguments.of("{}", IntegrationProblem.class),
                Arguments.of("", IllegalStateException.class), Arguments.of("{", IllegalStateException.class),
                Arguments.of("[]", IllegalStateException.class));
    }

    @Test void updateCopyRequiresTheExactImportedBasisAndRejectingAnEditKeepsTheBasisValue() throws Exception {
        try (var fixture = new Fixture(memory(), true)) {
            fixture.create(CONNECTION);
            var original = document("Original");
            byte[] bytes = fixture.json.write(original).getBytes(StandardCharsets.UTF_8);
            String hash = com.taxonomy.exchange.ReqifExchangeCodec.digest(bytes);
            UUID imported = UUID.randomUUID();
            fixture.store.locked(CONTEXT, CONNECTION, s -> {
                var context = new IntegrationContext(CONNECTION, AuthorityMode.BIDIRECTIONAL, s.connection().externalScope(), fixture.state(), "alice", "example-binary", "2");
                var source = new ExchangeDocument(original.profile(), original.profileVersion(), hash, false, "",
                        original.artifacts(), List.of(), List.of(), original.metadata(), List.of());
                s.preview(imported, context, "INBOUND", "imported", source, List.of());
                s.mapping(imported, "ELEMENT:" + ELEMENT, "alpha", null, hash, source.artifacts().getFirst(), source.artifacts().getFirst(), false);
                s.applied(imported, fixture.state(), source, false); s.complete(imported, fixture.state(), hash, "source", true);
                return null;
            });
            var wrong = new IntegrationService.BasedExportRequest(UUID.randomUUID(), fixture.state(), hash, FileExportMode.UPDATE_COPY, "application/example", null);
            assertEquals("FILE_BASE_CHANGED", assertThrows(IntegrationProblem.class,
                    () -> fixture.service.previewExport(CONTEXT, CONNECTION, wrong, TEMPLATE)).code());
            assertEquals("FILE_EXPORT_NEW_CONNECTION_REQUIRED", assertThrows(IntegrationProblem.class,
                    () -> fixture.preview(UUID.randomUUID(), TEMPLATE)).code());
            var preview = fixture.service.previewExport(CONTEXT, CONNECTION,
                    new IntegrationService.BasedExportRequest(UUID.randomUUID(), fixture.state(), hash, FileExportMode.UPDATE_COPY, "application/example", null), bytes);
            assertEquals(Set.of(FileExportMode.UPDATE_COPY), fixture.service.overview(CONTEXT, CONNECTION).fileExportModes());
            var decisions = new HashMap<String, Decision>(); preview.changes().forEach(c -> decisions.put(c.id(), c.externalId().equals("ELEMENT:" + ELEMENT) ? Decision.REJECT : Decision.ACCEPT));
            fixture.service.prepareFile(CONTEXT, CONNECTION, new ReviewedChangeSet(preview.id(), preview.fingerprint(), decisions, "Keep the original element"));
            var file = fixture.service.file(CONTEXT, CONNECTION, preview.id());
            var result = fixture.json.read(new String(file.content(), StandardCharsets.UTF_8), ExchangeDocument.class);
            assertEquals("Original", result.artifacts().stream().filter(a -> a.id().equals(ELEMENT)).findFirst().orElseThrow().title());
            assertEquals(imported, fixture.store.checkpoint(CONTEXT, CONNECTION).operationId());
        }
    }

    private static ExchangeDocument document(String title) {
        return new ExchangeDocument("example-binary", "2", null, false, "", List.of(new Artifact(ELEMENT, ArtifactKind.ELEMENT,
                "Node", title, "", Map.of(), Map.of("canonicalType", "System"))), List.of(), List.of(), Map.of("identifier", MODEL), List.of());
    }
    private static ReviewedChangeSet accept(Operation operation) {
        var decisions = new HashMap<String, Decision>(); operation.changes().forEach(c -> decisions.put(c.id(), Decision.ACCEPT));
        return new ReviewedChangeSet(operation.id(), operation.fingerprint(), decisions, "Reviewed file output");
    }
    private static String memory() { return "jdbc:hsqldb:mem:based-" + UUID.randomUUID(); }

    private static final class Fixture implements AutoCloseable {
        final org.hibernate.SessionFactory factory;
        final JdbcTemplate jdbc;
        final IntegrationJson json = new IntegrationJson(JsonMapper.builder().build());
        final IntegrationStore store;
        final IntegrationService service;
        final IntegrationDomainAdapter domain;
        final WorkspaceArchitectureIntegrationPort.WorkspaceDocument workspace;
        Fixture(String url, boolean installed) throws Exception { this(url, installed, false); }
        Fixture(String url, boolean installed, boolean externalPlugin) throws Exception {
            var database = new JDBCDataSource(); database.setUrl(url); database.setUser("SA"); database.setPassword("");
            var configuration = new Configuration().setProperty("hibernate.hbm2ddl.auto", "update")
                    .setProperty("hibernate.show_sql", "false");
            configuration.getProperties().put("hibernate.connection.datasource", database);
            for (var entity : List.of(IntegrationConnectionEntity.class, IntegrationOperationEntity.class, ExternalIdentityMappingEntity.class,
                    IntegrationCheckpointEntity.class, IntegrationEventEntity.class, IntegrationPublicationEntity.class,
                    IntegrationPublishItemEntity.class, IntegrationPublishAttemptEntity.class, IntegrationFileEntity.class)) configuration.addAnnotatedClass(entity);
            factory = configuration.buildSessionFactory(); jdbc = new JdbcTemplate(database);
            store = new IntegrationStore(factory, new JpaTransactionManager(factory), json);
            ExchangeConnectorRegistry registry;
            if (externalPlugin) {
                var catalog = new PluginCatalog();
                if (installed) catalog.publish(new PluginDescriptor(new PluginIdentity("example.binary", "1.0.0", "a".repeat(64)),
                        ">=1.4.0 & <2.0.0", List.of(), Set.of("integration.read"), PluginMode.STARTUP), List.of(new BinaryConnector(json)));
                registry = new ExchangeConnectorRegistry(List.of(), catalog);
            } else registry = new ExchangeConnectorRegistry(installed ? List.of(new BinaryConnector(json)) : List.of());
            domain = new IntegrationDomainAdapter(null, json, registry);
            String dsl = new ArchitectureDslCommands().apply("", new CreateArchitectureElement("alpha", "System", Map.of("title", "Local title"))).dsl();
            workspace = new WorkspaceArchitectureIntegrationPort.WorkspaceDocument(new WorkspaceArchitectureIntegrationPort.State("WORKSPACE:workspace", null, 0), dsl);
            var editor = mock(WorkspaceArchitectureIntegrationPort.class);
            when(editor.read(any(), any())).thenReturn(workspace);
            when(editor.locked(any(), any())).thenAnswer(invocation -> ((Function<WorkspaceArchitectureIntegrationPort.WorkspaceDocument, ?>) invocation.getArgument(1)).apply(workspace));
            var repositories = mock(SystemRepositoryService.class); var memberships = mock(RepositoryMembershipService.class);
            var access = mock(WorkspaceAccessService.class); var repository = mock(SystemRepository.class);
            when(repositories.getRepository(anyString())).thenReturn(repository);
            when(memberships.canContribute(any(), anyString())).thenReturn(true); when(memberships.canRead(any(), anyString())).thenReturn(true);
            when(access.canUsePrivateWorkspace(any())).thenReturn(true);
            service = new IntegrationService(store, registry, domain, editor, new IntegrationDiff(json), json, repositories, memberships, mock(OslcTransport.class), access);
        }
        void create(UUID id) { store.create(CONTEXT, id, "USER:alice", "Binary fixture", "example-binary", "2", AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("EXAMPLE", "fixture", null), null, null); }
        InternalState state() { return domain.snapshot(CONTEXT, store.read(CONTEXT, CONNECTION), List.of(), workspace).state(); }
        Operation preview(UUID id, byte[] basis) {
            return service.previewExport(CONTEXT, CONNECTION, new IntegrationService.BasedExportRequest(id, state(), null,
                    FileExportMode.NEW_PROJECT, "application/example", null), basis);
        }
        @Override public void close() { factory.close(); }
    }

    private static final class BinaryConnector implements IntegrationConnectorExtension {
        private final IntegrationJson json;
        BinaryConnector(IntegrationJson json) { this.json = json; }
        @Override public IntegrationDescriptor descriptor() { return new IntegrationDescriptor("example-binary", "2", "Generic binary fixture",
                Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT, Capability.BASED_FILE_EXPORT_V1, Capability.ARCHITECTURE_MODEL, Capability.CANONICAL_PROJECTION_V1), Set.of("application/example")); }
        @Override public FileExportProfile fileExportProfile() { return new FileExportProfile(Set.of(FileExportMode.NEW_PROJECT, FileExportMode.UPDATE_COPY),
                ExternalIdentityFormat.UUID_BRACED_UPPER, Map.of("System", "Node"), Map.of("DEPENDS_ON", "Dependency"), "Requirement", "Package", "Diagram"); }
        @Override public ExchangeDocument previewInbound(InboundRequest request) {
            var parsed = json.read(new String(request.content(), StandardCharsets.UTF_8), ExchangeDocument.class);
            return new ExchangeDocument(parsed.profile(), parsed.profileVersion(), request.externalVersion(), false, "",
                    parsed.artifacts(), parsed.relations(), parsed.placements(), parsed.metadata(), parsed.losses());
        }
        @Override public void validateInboundSelection(OutboundRequest selection) { }
        @Override public ExchangeFile previewOutbound(OutboundRequest request) { throw new UnsupportedOperationException("A basis is mandatory"); }
        @Override public ExchangeFile previewOutbound(BasedOutboundRequest request) {
            var document = request.outbound().document();
            if (request.mode() == FileExportMode.NEW_PROJECT) {
                String root = document.metadata().get("rootIdentifier");
                if (document.artifacts().stream().noneMatch(artifact -> artifact.id().equals(root))) {
                    var artifacts = new ArrayList<>(document.artifacts());
                    artifacts.add(new Artifact(root, ArtifactKind.SPECIFICATION, "Package", "Model", "", Map.of(), Map.of()));
                    document = new ExchangeDocument(document.profile(), document.profileVersion(), document.externalVersion(), false,
                            document.source(), artifacts, document.relations(), document.placements(), document.metadata(), document.losses());
                }
            }
            return new ExchangeFile("application/example", "model.bin", json.write(document).getBytes(StandardCharsets.UTF_8), List.of());
        }
    }
}
