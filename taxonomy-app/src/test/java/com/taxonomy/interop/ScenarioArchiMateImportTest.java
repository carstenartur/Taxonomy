package com.taxonomy.interop;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.dsl.command.ArchitectureSemanticPatch;
import com.taxonomy.editor.persistence.EditorJournal;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.ReqifExchangeCodec;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationService.CreateConnection;
import com.taxonomy.interop.IntegrationService.PreviewRequest;
import com.taxonomy.interop.IntegrationService.ExportRequest;
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
import java.util.Locale;
import java.util.Map;
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
        var sourceReview = new TreeMap<String, String>();
        for (var relation : decoded.relations()) {
            var properties = taxonomyProperties(relation.attributes(), relation.extensions());
            assertEquals("PROPOSED", properties.get("taxonomy.reviewStatus"),
                    relation.id() + " must retain the frozen source's historical review");
            assertFalse(relation.extensions().entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("definition:")
                            && entry.getValue().equals("Taxonomy.status")),
                    "This unchanged fixture exercises the legacy review-status fallback, not an explicit canonical status");
            sourceReview.put(relation.id(), properties.get("taxonomy.reviewStatus").toLowerCase(Locale.ROOT));
        }

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
        var expectedStatuses = new TreeMap<String, String>();
        decoded.relations().forEach(relation -> expectedStatuses.put(
                identities.get("ELEMENT:" + relation.source()) + "|" + relation.extensions().get("canonicalType")
                        + "|" + identities.get("ELEMENT:" + relation.target()), sourceReview.get(relation.id())));
        model.getRelations().forEach(relation -> assertEquals(expectedStatuses.get(
                relation.getSourceId() + "|" + relation.getRelationType() + "|" + relation.getTargetId()),
                relation.getStatus(), "A source proposal cannot become an accepted native relationship"));

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

        // A fresh connection must recover the actual native graph and its evidence, rather than
        // reusing the inbound operation's mappings or a fabricated Sparx-only fixture.
        UUID outgoing = integrations.create(context, new CreateConnection(UUID.randomUUID(), "Complete scenario Sparx v3",
                SparxMappingProfile.PROFILE, AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("Sparx delivery", "complete-captured-model", null), null, null, "3")).id();
        assertNotEquals(connection, outgoing);
        var export = integrations.previewExport(context, outgoing, new ExportRequest(UUID.randomUUID(),
                integrations.overview(context, outgoing).current(), null));
        assertTrue(export.document().completeScope(), "The native export must cover the complete workspace");
        assertEquals(36, export.document().relations().size());
        var exportDecisions = new TreeMap<String, Decision>();
        export.changes().forEach(change -> exportDecisions.put(change.id(), Decision.ACCEPT));
        var delivery = integrations.prepareFile(context, outgoing, new ReviewedChangeSet(export.id(), export.fingerprint(),
                exportDecisions, "Deliver every original typed endpoint, proposed status and retained source property"));
        assertEquals(OperationStatus.COMPLETED, delivery.status());
        assertTrue(delivery.resultDocument().completeScope(), "The completed file retains the complete export scope");
        byte[] xmi = integrations.file(context, outgoing, delivery.id()).content();
        var sparx = new SparxXmiCodec("3");
        var delivered = sparx.read(xmi, "captured-sparx-v3", true);
        var rewritten = sparx.read(sparx.write(delivered), "rewritten-sparx-v3", true);
        assertEquals(delivered.artifacts(), rewritten.artifacts());
        assertEquals(delivered.relations(), rewritten.relations());
        assertSparxParity(decoded, delivered);
        assertSparxParity(decoded, rewritten);
        assertNull(integrations.overview(context, outgoing).checkpoint(), "File delivery is not remote acknowledgement");
        var evidence = java.nio.file.Path.of("target", "scenario-import-regression");
        java.nio.file.Files.createDirectories(evidence);
        java.nio.file.Files.write(evidence.resolve("architecture.sparx.xmi"), xmi);
        var proof = new java.util.LinkedHashMap<String, Object>();
        proof.put("sourceSha256", SOURCE_SHA256); proof.put("sourceBytes", source.length);
        proof.put("sourceElements", 50); proof.put("sourceRelations", decoded.relations().size());
        proof.put("canonicalProposedRelations", model.getRelations().stream().filter(relation -> "proposed".equals(relation.getStatus())).count());
        proof.put("deliveredProposedRelations", delivered.relations().stream().filter(relation -> "proposed".equals(relation.extensions().get("taxonomy:status"))).count());
        proof.put("importCommit", applied.resultCommit()); proof.put("deliveryOperation", delivery.id().toString());
        proof.put("sparxProfileVersion", "3");
        proof.put("sparxSha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(xmi)));
        java.nio.file.Files.writeString(evidence.resolve("proof.json"), json.write(proof), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertSparxParity(ExchangeDocument source, ExchangeDocument delivered) throws Exception {
        var originalByTitle = new TreeMap<String, Artifact>();
        var deliveredByTitle = new TreeMap<String, Artifact>();
        var sourceLabels = new TreeMap<String, String>();
        var deliveredLabels = new TreeMap<String, String>();
        for (var node : source.artifacts()) if (node.kind() == ArtifactKind.ELEMENT) {
            assertNull(originalByTitle.put(node.title(), node)); sourceLabels.put(node.id(), node.title());
        }
        for (var node : delivered.artifacts()) {
            assertNull(deliveredByTitle.put(node.title(), node)); deliveredLabels.put(node.id(), node.title());
        }
        assertEquals(50, delivered.artifacts().size());
        assertEquals(originalByTitle.keySet(), deliveredByTitle.keySet());
        for (var entry : originalByTitle.entrySet()) {
            var expected = entry.getValue(); var actual = deliveredByTitle.get(entry.getKey());
            assertEquals(expected.extensions().get("canonicalType"), actual.extensions().get("canonicalType"));
            assertTaxonomyProperties(expected.attributes(), expected.extensions(), actual.attributes(), entry.getKey());
        }
        var originalRelations = new TreeMap<String, Relation>();
        var deliveredRelations = new TreeMap<String, Relation>();
        for (var relation : source.relations()) assertNull(originalRelations.put(relationKey(relation, sourceLabels), relation));
        for (var relation : delivered.relations()) assertNull(deliveredRelations.put(relationKey(relation, deliveredLabels), relation));
        assertEquals(36, delivered.relations().size());
        assertEquals(originalRelations.keySet(), deliveredRelations.keySet());
        for (var entry : originalRelations.entrySet()) {
            var expected = entry.getValue(); var actual = deliveredRelations.get(entry.getKey());
            assertEquals("Source -> Destination", actual.extensions().get("direction"));
            assertTaxonomyProperties(expected.attributes(), expected.extensions(), actual.attributes(), entry.getKey());
            assertEquals("PROPOSED", actual.attributes().get("tag:taxonomy.reviewStatus"));
            assertEquals("proposed", actual.attributes().get("tag:taxonomy.status"), entry.getKey());
            assertEquals("proposed", actual.extensions().get("taxonomy:status"), entry.getKey());
        }
        source.metadata().forEach((name, value) -> {
            if (name.startsWith("taxonomy.")) assertEquals(value, delivered.metadata().get(name), name);
        });
    }

    private static String relationKey(Relation relation, Map<String, String> labels) {
        return labels.get(relation.source()) + "|" + relation.extensions().get("canonicalType") + "|" + labels.get(relation.target());
    }

    private static void assertTaxonomyProperties(Map<String, String> properties, Map<String, String> definitions,
                                                  Map<String, String> delivered, String identity) throws Exception {
        for (var property : taxonomyProperties(properties, definitions).entrySet()) {
            String name = property.getKey().equals("taxonomy.id") ? "taxonomy.sourceId" : property.getKey();
            assertEquals(property.getValue(), delivered.get("tag:" + name), "Retained " + name + " on " + identity);
        }
    }

    private static Map<String, String> taxonomyProperties(Map<String, String> properties, Map<String, String> definitions) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var values = new TreeMap<String, String>();
        for (var property : properties.entrySet()) {
            String name = definitions.get("definition:" + property.getKey());
            if (name == null || !name.startsWith("taxonomy.")) continue;
            var parsed = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    property.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var text = parsed.getElementsByTagNameNS(ArchiMateExchangeCodec.NS, "value");
            assertEquals(1, text.getLength(), name);
            assertNull(values.put(name, text.item(0).getTextContent()), "Duplicate source property " + name);
        }
        return values;
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
