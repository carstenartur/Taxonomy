package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.taxonomy.plugins.sparx.QeaFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public connector operations over real SQLite files; native rows are independently inspected. */
class SparxQeaIdentityPreservationTest {
    private static final UUID NAMESPACE = UUID.fromString("d7a9a0f3-8ef7-4e55-9558-9e5008a54d91");
    private static final String NEW_ELEMENT = "{A1234567-0000-0000-0000-000000000003}";
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"Action, PDATA1, BEHAVIOUR", "Action, Classifier_guid, CLASSIFIER",
            "Object, Classifier_guid, CLASSIFIER", "Object, PDATA1, INSTANCE_OF",
            "Part, PDATA1, INSTANCE_OF", "Port, PDATA1, INSTANCE_OF",
            "Part, PDATA3, REUSAGE", "Port, PDATA3, REUSAGE"})
    void aNewGuidCannotCaptureAQualifiedUnselectedImplicitReference(String type, String field, String relationType) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type=?, " + field + "=? WHERE Object_ID=11",
                    type, "  " + NEW_ELEMENT.toLowerCase(Locale.ROOT) + "  ");
            byte[] basis = fixture.bytes();
            var before = read(basis);
            assertTrue(before.relations().stream().anyMatch(r -> r.source().equals(B)
                    && r.target().equals(NEW_ELEMENT) && r.type().equals(relationType)));
            assertTrue(before.losses().stream().anyMatch(l -> l.code().equals("QEA_UNRESOLVED_REFERENCE")));
            var added = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, "Component", "Added", "",
                    Map.of(), Map.of("canonicalType", "Component"));
            var failure = assertThrows(QeaFailure.class, () -> write(basis, List.of(added), List.of()));
            assertTrue(failure.getMessage().startsWith("QEA_DUPLICATE_IDENTITY:"), failure.getMessage());
            assertArrayEquals(basis, fixture.bytes(), "Rejecting GUID capture must leave the supplied file unchanged");
        }
    }

    @ParameterizedTest
    @CsvSource({"Component, PDATA1", "Component, PDATA3", "Component, Classifier_guid",
            "Object, PDATA3", "Action, PDATA3"})
    void guidLookingTextOutsideQualifiedFieldsDoesNotReserveAnIdentity(String type, String field) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type=?, " + field + "=? WHERE Object_ID=11", type, NEW_ELEMENT);
            byte[] basis = fixture.bytes();
            var added = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, "Component", "Added", "",
                    Map.of(), Map.of("canonicalType", "Component"));
            byte[] output = write(basis, List.of(added), List.of());
            assertEquals("Added", scalar(output, "SELECT Name FROM t_object WHERE ea_guid='" + NEW_ELEMENT + "'"));
            assertEquals(NEW_ELEMENT, scalar(output, "SELECT " + field + " FROM t_object WHERE Object_ID=11"));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"vendor-specific reference", "0", ""})
    void nonGuidValuesInQualifiedFieldsStayOpaqueAndDoNotBlockAnUnrelatedAddition(String value) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type='Part', PDATA1=? WHERE Object_ID=11", value);
            byte[] basis = fixture.bytes();
            var added = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, "Component", "Added", "",
                    Map.of(), Map.of("canonicalType", "Component"));
            byte[] output = write(basis, List.of(added), List.of());
            assertEquals(value, scalar(output, "SELECT PDATA1 FROM t_object WHERE Object_ID=11"));
            assertEquals("Added", scalar(output, "SELECT Name FROM t_object WHERE ea_guid='" + NEW_ELEMENT + "'"));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test
    void aQualifiedReferenceToAnExistingGuidDoesNotBlockUpdatingThatObject() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type='Part', PDATA1=? WHERE Object_ID=11", A);
            byte[] basis = fixture.bytes();
            var renamed = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Renamed", "First note",
                    Map.of(), Map.of("canonicalType", "Component"));
            byte[] output = write(basis, List.of(renamed), List.of());
            assertEquals("Renamed", scalar(output, "SELECT Name FROM t_object WHERE Object_ID=10"));
            assertEquals(A, scalar(output, "SELECT PDATA1 FROM t_object WHERE Object_ID=11"));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @ParameterizedTest
    @CsvSource({"existing, object_id, 11", "existing, diagram_id, 31",
            "frozen-alias, object_id, 11", "frozen-alias, diagram_id, 31",
            "new, object_id, 11", "new, diagram_id, 31"})
    void rawDiagramOccurrenceIdentityCannotBeSilentlyIgnored(String selection, String field, String value) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
            fixture.sql("UPDATE t_diagramobjects SET Instance_ID=Object_ID+60");
            byte[] basis = fixture.bytes();
            var original = read(basis).placements().stream()
                    .filter(p -> p.containerId().equals(VIEW) && p.artifactId().equals(A)).findFirst().orElseThrow();
            var attributes = new HashMap<>(original.attributes());
            attributes.put("ea:t_diagramobjects:" + field, value);
            if (selection.equals("new")) attributes.put("ea:t_diagramobjects:instance_id", "90");
            var changed = new Placement(selection.equals("existing") ? original.id() : "occurrence:" + selection,
                    VIEW, null, A, original.position(), attributes);
            var failure = assertThrows(QeaFailure.class, () -> write(basis, List.of(), List.of(changed)));
            assertTrue(failure.getMessage().startsWith("QEA_IDENTITY_CONFLICT:"), failure.getMessage());
            assertArrayEquals(basis, fixture.bytes(), "Rejected occurrence identity must not mutate the supplied file");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedOccurrenceIdentityIsAnActualNoOp(boolean frozenAlias) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
            fixture.sql("UPDATE t_diagramobjects SET Instance_ID=Object_ID+60");
            byte[] basis = fixture.bytes();
            var original = read(basis).placements().stream()
                    .filter(p -> p.containerId().equals(VIEW) && p.artifactId().equals(A)).findFirst().orElseThrow();
            var unchanged = new Placement(frozenAlias ? "occurrence:alias" : original.id(), VIEW, null,
                    A, original.position(), original.attributes());
            byte[] output = write(basis, List.of(), List.of(unchanged));
            assertEquals("2", scalar(output, "SELECT count(*) FROM t_diagramobjects"));
            assertEquals("30|10|70|10|-20|170|-100|font", scalar(output,
                    "SELECT Diagram_ID||'|'||Object_ID||'|'||Instance_ID||'|'||RectLeft||'|'||RectTop||'|'||RectRight||'|'||RectBottom||'|'||ObjectStyle FROM t_diagramobjects WHERE Instance_ID=70"));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    private static IntegrationContext context() {
        return new IntegrationContext(NAMESPACE, AuthorityMode.PUBLISH_TARGET, new ExternalScope("sparx", "model", null),
                new InternalState("repository", "workspace", "draft", null, 1, null, null), "reviewer", "sparx-qea", "2");
    }

    private static ExchangeDocument read(byte[] basis) {
        return new SparxQeaWriterConnector().previewInbound(new InboundRequest(context(), "application/vnd.sparx.qea", basis, null, false));
    }

    private static byte[] write(byte[] basis, List<Artifact> artifacts, List<Placement> placements) {
        var document = new ExchangeDocument("sparx-qea", "2", null, false, "Reviewed native identities", artifacts,
                List.of(), placements, Map.of("identifier", MODEL), List.of());
        return new SparxQeaWriterConnector().previewOutbound(new BasedOutboundRequest(new OutboundRequest(context(), document, null),
                FileExportMode.UPDATE_COPY, NAMESPACE, QeaDatabase.digest(basis), "application/vnd.sparx.qea", basis)).content();
    }

    private String scalar(byte[] bytes, String sql) throws Exception {
        Path copy = Files.write(Files.createTempFile(directory, "inspect-", ".qea"), bytes);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy);
             var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            String value = rows.getString(1);
            assertFalse(rows.next());
            return value;
        } finally { Files.deleteIfExists(copy); }
    }
}
