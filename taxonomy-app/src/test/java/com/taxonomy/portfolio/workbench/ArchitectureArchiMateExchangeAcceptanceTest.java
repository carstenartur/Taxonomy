package com.taxonomy.portfolio.workbench;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.archimate.exchange.ArchiMateXmlExporter;

import com.taxonomy.archimate.ArchiMateProperty;
import com.taxonomy.diagram.*;
import com.taxonomy.export.*;
import com.taxonomy.export.service.CanonicalDiagramExportService;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.workbench.ArchitectureWorkbenchDtos.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the real snapshot export, metadata whitelist, ZIP manifest and profile reader together. */
class ArchitectureArchiMateExchangeAcceptanceTest {
    @Test
    void bundleBytesRemainStableAcrossProcessesAndTimeZones(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        byte[] expected = fixtureBundle().content();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String zone : List.of("UTC", "Europe/Berlin", "Pacific/Honolulu", "Pacific/Kiritimati")) {
            var output = directory.resolve(zone.replace('/', '-') + ".zip");
            var log = directory.resolve(zone.replace('/', '-') + ".log");
            var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Duser.timezone=" + zone, "-cp", classpath, getClass().getName(), output.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); fail("ArchiMate bundle fixture process did not finish: " + zone);
            }
            assertEquals(0, process.exitValue(), () -> "Fixture process failed: " + log);
            assertArrayEquals(expected, java.nio.file.Files.readAllBytes(output), zone);
        }
    }

    private static ArchitectureSnapshotExportService.Artifact fixtureBundle() {
        var service = new ArchitectureSnapshotExportService(new FixedWorkbench(projection()),
                new CanonicalDiagramExportService(null, null, new ArchiMateDiagramService(), new ArchiMateXmlExporter()));
        return service.exportArchiMateBundle(42L, "snapshot", "alice",
                new WorkspaceContext("alice", "workspace", "branch", "repository"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) throw new IllegalArgumentException("Expected one output ZIP path");
        java.nio.file.Files.write(java.nio.file.Path.of(args[0]), fixtureBundle().content());
    }

    @Test
    void packageContainsTheExactSnapshotProfileAndMachineReadableLossReport() throws Exception {
        Projection projection = projection();
        var workbench = new FixedWorkbench(projection);
        var service = new ArchitectureSnapshotExportService(workbench,
                new CanonicalDiagramExportService(null, null, new ArchiMateDiagramService(), new ArchiMateXmlExporter()));
        var context = new WorkspaceContext("alice", "workspace", "branch", "repository");
        var artifact = service.exportArchiMateBundle(42L, "snapshot", "alice", context);
        assertEquals(1, workbench.loads);
        assertEquals("snapshot", artifact.snapshotId());
        assertEquals("commit-immutable", artifact.commitSha());
        assertEquals(ArchiMateExchangeProfile.VERSION, artifact.exporterProfile());
        assertEquals("application/zip", artifact.mediaType());
        Map<String, byte[]> files = unzip(artifact.content());
        assertEquals(Set.of("model.archimate.xml", "mapping-profile.tsv", "manifest.json"), files.keySet());
        assertArrayEquals(ArchiMateExchangeProfile.resourceBytes(), files.get("mapping-profile.tsv"));
        var reader = new ArchiMateExchangeReader();
        var imported = reader.read(files.get("model.archimate.xml"));
        assertEquals(projection.diagram(), reader.toDiagram(imported));
        assertEquals(ArchiMateProperty.text("99"), imported.properties().get("taxonomy.requirementVersionId"));
        assertEquals(ArchiMateProperty.text("catalogue-frozen"), imported.properties().get("taxonomy.taxonomyFingerprint"));
        assertEquals(ArchiMateProperty.text("repository"), imported.properties().get("taxonomy.repositoryId"));
        assertEquals(ArchiMateProperty.text(artifact.canonicalGraphSha256()), imported.properties().get("taxonomy.canonicalGraphSha256"));
        assertEquals(ArchiMateProperty.text("Human rationale"), imported.elements().getFirst().properties().get("taxonomy.decisionRationale"));
        String manifest = new String(files.get("manifest.json"), StandardCharsets.UTF_8);
        String xml = new String(files.get("model.archimate.xml"), StandardCharsets.UTF_8);
        var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(manifest);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(mapper.valueToTree(imported.properties()), json.get("provenance"));
        assertEquals(mapper.valueToTree(imported.losses()), json.get("losses"));
        assertEquals(sha256(files.get("model.archimate.xml")), json.get("xmlSha256").asString());
        assertTrue(json.get("experimental").asBoolean());
        assertTrue(manifest.contains("selectionProfileFingerprint"));
        assertFalse(xml.contains("PRIVATE_PROVIDER"));
        assertFalse(manifest.contains("PRIVATE_PROVIDER"));
        assertArrayEquals(artifact.content(), service.exportArchiMateBundle(42L, "snapshot", "alice", context).content());
    }

    private static Projection projection() {
        var graph = new DiagramModel("Immutable architecture", List.of(
                new DiagramNode("CP", "Duplicate name", "Capabilities", .98765, true, 1),
                new DiagramNode("CR", "Duplicate name", "Core Services", .87654, false, 2)),
                List.of(new DiagramEdge("relation", "CR", "CP", "FULFILLS", .76543, "trace")), new DiagramLayout("LR", true));
        var metadata = new ElementMetadata("CP", "Duplicate name", "CP", 98, .98765, .8, "HUMAN", "CP",
                "PRIVATE_PROVIDER_EXPLANATION", false, null, null, "Evidence document 7", "alice",
                Instant.parse("2026-09-01T12:00:00Z"), "Human rationale");
        return new Projection(42L, "PRJ", "Project", 7L, "REQ", "Requirement", "PRIVATE_PROVIDER_PROMPT",
                "snapshot", AnalysisStatus.SUCCESS, Instant.parse("2026-09-01T11:00:00Z"), "PRIVATE_PROVIDER", "PRIVATE_PROVIDER_MODEL",
                "workspace", "branch", "commit-immutable", graph, null, Map.of("CP", metadata), Map.of(), List.of("PRIVATE_PROVIDER_WARNING"),
                new SnapshotProvenance(99L, "catalogue-frozen", "repository"));
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final class FixedWorkbench extends ArchitectureWorkbenchService {
        private final Projection projection;
        private int loads;
        FixedWorkbench(Projection projection) {
            super(null, null, null, null, null, null);
            this.projection = projection;
        }
        @Override public Projection load(Long projectId, String snapshotId, String username, WorkspaceContext context) {
            assertEquals(42L, projectId);
            assertEquals("snapshot", snapshotId);
            assertEquals("alice", username);
            assertEquals("repository", context.repositoryId());
            loads++;
            return projection;
        }
    }
}
