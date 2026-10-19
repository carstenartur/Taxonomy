package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationService.CreateConnection;
import com.taxonomy.interop.IntegrationService.PreviewRequest;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The complete captured scenario graph must fit the unchanged integration JSON request deadline. */
@SpringBootTest
class ScenarioArchiMateImportTest {
    private static final String SOURCE = "/scenarios/pr1251-complete-architecture.archimate.xml.gz";
    private static final int SOURCE_BYTES = 1_826_654;
    private static final String SOURCE_SHA256 = "14bd36e03c95f1504c02efe9ecf884846a3d13670e14aa171c4fbddf46c879a2";
    @Autowired IntegrationService integrations;
    @Autowired ArchitectureRepositoryProvisioningService repositories;
    @Autowired RepositoryWorkspaceService workspaces;
    @Autowired DslGitRepositoryFactory git;
    @Autowired EditorJournal journal;
    @Autowired IntegrationJson json;

    @Test void fullCapturedArchitectureImportsWithinTheOriginalDeadlineWithoutLosingEvidence() throws Exception {
        byte[] source = capturedSource();
        var decoded = new ArchiMateExchangeCodec().read(source, ReqifExchangeCodec.digest(source), false);
        assertEquals(52, decoded.artifacts().size());
        assertEquals(50, decoded.artifacts().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT).count());
        assertEquals(36, decoded.relations().size());

        String actor = "scenario-import-" + UUID.randomUUID().toString().substring(0, 8);
        var repository = repositories.createRepository("Complete scenario exchange", actor, "Captured realistic architecture",
                RepositoryVisibility.PRIVATE, actor, "draft");
        var workspace = workspaces.createWorkingCopy(actor, repository.getRepositoryId(), "draft", "Complete import", "");
        var context = RepositoryContext.workspace(repository.getRepositoryId(), workspace.getWorkspaceId(),
                workspace.getCurrentBranch(), actor);
        UUID connection = integrations.create(context, new CreateConnection(UUID.randomUUID(), "Complete scenario import",
                ArchiMateExchangeCodec.PROFILE, AuthorityMode.IMPORT_COPY,
                new ExternalScope("Scenario snapshot", "complete-captured-model", null), null, null)).id();
        String beforeHead = git.resolveRepository(context).getHeadCommit(context.branch());
        var expected = integrations.overview(context, connection).current();
        var preview = integrations.preview(context, connection, new PreviewRequest(UUID.randomUUID(), expected,
                "application/archimate+xml", false), source);
        assertEquals(decoded.artifacts(), preview.document().artifacts());
        assertEquals(decoded.relations(), preview.document().relations());
        assertNull(journal.read(context));
        assertEquals(beforeHead, git.resolveRepository(context).getHeadCommit(context.branch()));

        // Match the scenario's explicit review: preserve every supported semantic element and
        // relationship; reject only view/layout candidates instead of fabricating accepted views.
        var decisions = new TreeMap<String, Decision>();
        preview.changes().forEach(change -> decisions.put(change.id(), change.after() != null
                && (change.after().kind() == ArtifactKind.VIEW || change.after().kind() == ArtifactKind.PLACEMENT)
                ? Decision.REJECT : Decision.ACCEPT));
        assertEquals(36, preview.changes().stream().filter(change -> change.after() != null
                && change.after().kind() == ArtifactKind.RELATION && decisions.get(change.id()) == Decision.ACCEPT).count());
        var review = new ReviewedChangeSet(preview.id(), preview.fingerprint(), decisions,
                "Retain the complete supported scenario graph and every published property; reject generated layouts");

        long started = System.nanoTime();
        var applied = assertTimeout(Duration.ofSeconds(30), () -> {
            try { return integrations.apply(context, connection, review); }
            finally { System.out.println("Full captured ArchiMate apply milliseconds: "
                    + (System.nanoTime() - started) / 1_000_000); }
        },
                "The original integration JSON deadline applies to the full graph, not a reduced fixture");
        assertEquals(OperationStatus.COMPLETED, applied.status());
        assertNotEquals(beforeHead, applied.resultCommit());
        assertEquals(applied, integrations.apply(context, connection, review));
        assertEquals(1, journal.read(context).operations().size());
        assertEquals(1, journal.checkpoints(context).size());
        assertEquals(applied.resultCommit(), journal.checkpoints(context).getFirst().commitId());
        assertEquals(applied.id(), integrations.overview(context, connection).checkpoint().operationId());

        String dsl = git.resolveRepository(context).getDslAtHead(context.branch());
        var model = new ArchitectureDslCommands().model(dsl);
        assertEquals(50, model.getElements().size()); assertEquals(36, model.getRelations().size());
        var identities = new TreeMap<String, String>();
        integrations.identities(context, connection).forEach(identity -> identities.put(identity.externalId(), identity.businessIdentity()));
        var actualNodes = new TreeSet<String>(); model.getElements().forEach(node -> actualNodes.add(node.getId()));
        var expectedNodes = new TreeSet<String>(); decoded.artifacts().stream().filter(a -> a.kind() == ArtifactKind.ELEMENT)
                .forEach(node -> expectedNodes.add(identities.get("ELEMENT:" + node.id())));
        assertEquals(expectedNodes, actualNodes);
        var actualRelations = new TreeSet<String>(); model.getRelations().forEach(relation -> actualRelations.add(
                relation.getSourceId() + "|" + relation.getRelationType() + "|" + relation.getTargetId()));
        var expectedRelations = new TreeSet<String>(); decoded.relations().forEach(relation -> expectedRelations.add(
                identities.get("ELEMENT:" + relation.source()) + "|" + relation.extensions().get("canonicalType")
                        + "|" + identities.get("ELEMENT:" + relation.target())));
        assertEquals(expectedRelations, actualRelations);

        // The operation retains the full original decoded file, including rejected layouts.
        assertEquals(preview.document(), integrations.operation(context, connection, preview.id()).document());
        // Accepted canonical evidence retains every field, not just node/relation counts.
        var retainedBlock = ArchitectureSemanticPatch.index(dsl).get("exchange:integration-" + connection);
        assertNotNull(retainedBlock);
        var retained = json.read(retainedBlock.property("source"), ExchangeDocument.class);
        var result = applied.resultDocument();
        assertEquals(new ExchangeDocument(result.profile(), result.profileVersion(), result.externalVersion(),
                result.completeScope(), "", result.artifacts(), result.relations(), result.placements(),
                result.metadata(), result.losses()), retained);
    }

    private static byte[] capturedSource() throws Exception {
        byte[] compressed;
        try (var input = ScenarioArchiMateImportTest.class.getResourceAsStream(SOURCE)) {
            assertNotNull(input, "Lossless captured fixture missing"); compressed = input.readAllBytes();
        }
        assertArrayEquals(new byte[4], Arrays.copyOfRange(compressed, 4, 8), "GZIP mtime must remain deterministic");
        byte[] source;
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) { source = input.readAllBytes(); }
        assertEquals(SOURCE_BYTES, source.length);
        assertEquals(SOURCE_SHA256, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source)));
        return source;
    }
}
