package com.taxonomy.portfolio.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.diagram.*;
import com.taxonomy.export.*;
import com.taxonomy.export.service.CanonicalDiagramExportService;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Real serializers behind the authorized snapshot boundary, without an analysis collaborator. */
class ArchitectureVisioHandoffAcceptanceTest {
    private static final WorkspaceContext CONTEXT = new WorkspaceContext("alice", "workspace-a", "reviewed", "repository-a");
    private static final Instant CREATED = Instant.parse("2026-09-08T10:00:00Z");
    private static final JsonMapper JSON = new JsonMapper();

    @Test void bundlePreservesExactAuthorityAndSafeHumanDecisions() throws Exception {
        var source = new FrozenWorkbench(projection());
        var service = service(source);
        var artifact = service.exportVisioBundle(42L, "snapshot-965", "alice", CONTEXT);
        JsonNode manifest = JSON.readTree(entry(artifact.content(), "manifest.json"));
        JsonNode authority = manifest.at("/handoff/authority");
        assertEquals("repository-a", authority.at("/taxonomy.repositoryId/value").asText());
        assertEquals("workspace-a", authority.at("/taxonomy.workspaceId/value").asText());
        assertEquals("reviewed", authority.at("/taxonomy.branch/value").asText());
        assertEquals("abcdef1234567890", authority.at("/taxonomy.authoritativeCommit/value").asText());
        assertEquals("snapshot-965", authority.at("/taxonomy.snapshotId/value").asText());
        assertEquals("901", authority.at("/taxonomy.requirementVersionId/value").asText());
        assertEquals("catalogue-fingerprint", authority.at("/taxonomy.taxonomyFingerprint/value").asText());
        assertEquals(CREATED.toString(), authority.at("/taxonomy.generatedAt/value").asText());
        assertEquals("SOURCE_SNAPSHOT_CREATED_AT", authority.at("/taxonomy.timestampPolicy/value").asText());
        assertEquals(artifact.canonicalGraphSha256(), authority.at("/taxonomy.canonicalGraphSha256/value").asText());
        assertEquals(VisioHandoffProfile.ID, artifact.exporterProfile());
        assertEquals("application/zip", artifact.mediaType());
        assertTrue(artifact.fileName().endsWith(".visio.zip"));
        String data = manifest.toString();
        assertTrue(data.contains("CONFIRMED"));
        assertTrue(data.contains("Reviewed rationale"));
        assertTrue(data.contains("Approved evidence reference"));
        assertFalse(data.contains("PRIVATE_PROVIDER_SENTINEL"));
        assertFalse(data.contains("PRIVATE_REQUIREMENT_SENTINEL"));
        assertFalse(data.contains("PRIVATE_WARNING_SENTINEL"));
        assertEquals(1, source.loads);
    }

    @Test void visioArchiMateAndBundleShareCanonicalSemanticAuthority() throws Exception {
        var source = new FrozenWorkbench(projection()); var service = service(source);
        var archimate = service.exportArchiMate(42L, "snapshot-965", "alice", CONTEXT);
        var vsdx = service.exportVisio(42L, "snapshot-965", "alice", CONTEXT);
        var bundle = service.exportVisioBundle(42L, "snapshot-965", "alice", CONTEXT);
        assertEquals(archimate.canonicalGraphSha256(), vsdx.canonicalGraphSha256());
        assertEquals(vsdx.canonicalGraphSha256(), bundle.canonicalGraphSha256());
        assertArrayEquals(vsdx.content(), entry(bundle.content(), "diagram.vsdx"));
        assertEquals(vsdx.artifactSha256(), VisioHandoffProfile.sha256(vsdx.content()));
        assertArrayEquals(bundle.content(), service.exportVisioBundle(42L, "snapshot-965", "alice", CONTEXT).content());
        assertEquals(4, source.loads);
    }

    @Test void unavailableHistoricalFieldsAreNamedAndNeverReplacedWithCurrentValues() {
        Projection p = projection();
        Projection legacy = new Projection(p.projectId(), p.projectKey(), p.projectTitle(), p.requirementId(), p.requirementKey(),
                p.requirementTitle(), p.requirementText(), p.snapshotId(), p.snapshotStatus(), p.snapshotCreatedAt(), p.provider(),
                p.modelName(), p.workspaceId(), p.branchName(), p.commitSha(), p.diagram(), p.scene(), p.elements(), p.relations(), p.warnings());
        var metadata = VisioSnapshotMetadata.from(legacy, legacy.diagram(), "graph-hash");
        assertFalse(metadata.document().containsKey("taxonomy.requirementVersionId"));
        assertTrue(metadata.losses().stream().anyMatch(loss -> loss.field().equals("requirementVersionId") && loss.kind().equals("OMITTED")));
        assertTrue(metadata.losses().stream().anyMatch(loss -> loss.field().equals("selectionProfileFingerprint")));
    }

    @Test void otherRepositoryAndUnexpectedSnapshotCannotProduceAnArtifact() {
        var service = service(new FrozenWorkbench(projection()));
        assertThrows(PortfolioException.class, () -> service.exportVisioBundle(42L, "snapshot-965", "alice",
                new WorkspaceContext("alice", "workspace-a", "reviewed", "repository-b")));
        assertThrows(PortfolioException.class, () -> service.exportVisioBundle(42L, "other-snapshot", "alice", CONTEXT));
    }

    private static ArchitectureSnapshotExportService service(FrozenWorkbench source) {
        return new ArchitectureSnapshotExportService(source, new CanonicalDiagramExportService(
                new VisioDiagramService(), new VisioPackageBuilder(), new ArchiMateDiagramService(), new ArchiMateXmlExporter()));
    }

    private static Projection projection() {
        DiagramModel graph = new DiagramModel("Reviewed architecture", List.of(
                new DiagramNode("CP-1", "Same name", "Capabilities", .9, true, 0, 2, true, null, false),
                new DiagramNode("CS-1", "Same name", "Core Services", .6, false, 1, 2, false, null, false)),
                List.of(new DiagramEdge("r-1", "CP-1", "CS-1", "SUPPORTS", .7, "trace")), new DiagramLayout("LR", false));
        var element = new ElementMetadata("CP-1", "Same name", "CP", 90, .9, .8, "HUMAN", "CP/CP-1",
                "PRIVATE_PROVIDER_SENTINEL", true, ReviewStatus.CONFIRMED, ActionStatus.REUSE,
                "Approved evidence reference", "alice", CREATED, "Reviewed rationale");
        var relation = new RelationMetadata("CP-1", "CS-1", "SUPPORTS", "HUMAN", "trace", .7, .9,
                "PRIVATE_PROVIDER_SENTINEL", ReviewStatus.CONFIRMED, "alice", CREATED, "Reviewed relationship");
        return new Projection(42L, "P42", "Project", 90L, "R90", "Requirement", "PRIVATE_REQUIREMENT_SENTINEL",
                "snapshot-965", AnalysisStatus.SUCCESS, CREATED, "provider", "model", "workspace-a", "reviewed",
                "abcdef1234567890", graph, null, Map.of("CP-1", element), Map.of(relation.signature(), relation),
                List.of("PRIVATE_WARNING_SENTINEL"), new SnapshotProvenance(901L, "catalogue-fingerprint", "repository-a"));
    }

    private static final class FrozenWorkbench extends ArchitectureWorkbenchService {
        private final Projection value;
        int loads;
        FrozenWorkbench(Projection value) { super(null, null, null, null, null, null); this.value = value; }
        @Override public Projection load(Long projectId, String snapshotId, String username, WorkspaceContext context) {
            loads++;
            if (!CONTEXT.equals(context) || !"alice".equals(username) || !Long.valueOf(42).equals(projectId)) {
                throw PortfolioException.conflict("Unauthorized coordinates");
            }
            return value;
        }
    }

    private static byte[] entry(byte[] zip, String name) throws Exception {
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) if (entry.getName().equals(name)) return input.readAllBytes();
        }
        throw new AssertionError("Missing package entry " + name);
    }
}
