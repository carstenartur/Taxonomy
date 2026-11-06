package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native SQL assertions complement the reader: a shared encoder/decoder mistake must not hide data loss. */
class SparxQeaWriteTest {
    private static final UUID NAMESPACE = UUID.fromString("d7a9a0f3-8ef7-4e55-9558-9e5008a54d91");
    private static final String NEW_MODEL = "{60000000-0000-0000-0000-000000000001}";
    private static final String NEW_PACKAGE = "{60000000-0000-0000-0000-000000000002}";
    private static final String NEW_ELEMENT = "{60000000-0000-0000-0000-000000000003}";
    private static final String NEW_REQUIREMENT = "{60000000-0000-0000-0000-000000000004}";
    private static final String NEW_VIEW = "{60000000-0000-0000-0000-000000000005}";
    private static final String NEW_ROOT = "{60000000-0000-0000-0000-000000000006}";
    @TempDir Path directory;

    @Test void discoversSeparateReadOnlyAndBasedWritingProfiles() {
        var extensions = new SparxPlugin().extensions();
        assertEquals(Set.of("sparx-qea@1", "sparx-qea@2"), extensions.stream().map(e -> e.id()).collect(java.util.stream.Collectors.toSet()));
        var readOnly = (LifecycleIntegrationConnector) extensions.stream().filter(e -> e.id().equals("sparx-qea@1")).findFirst().orElseThrow();
        assertFalse(readOnly.descriptor().capabilities().contains(Capability.FILE_EXPORT));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.previewOutbound(new OutboundRequest(context("1"), document(MODEL, List.of(), List.of(), List.of()), null)));
        var writer = connector();
        assertTrue(writer.descriptor().capabilities().containsAll(Set.of(Capability.FILE_IMPORT, Capability.FILE_EXPORT, Capability.BASED_FILE_EXPORT_V1)));
        assertEquals(Set.of(FileExportMode.NEW_PROJECT, FileExportMode.UPDATE_COPY), writer.fileExportProfile().modes());
        assertEquals(ExternalIdentityFormat.UUID_BRACED_UPPER, writer.fileExportProfile().identityFormat());
        assertEquals("Association", writer.fileExportProfile().relationTypes().get("REALIZES"));
        assertThrows(UnsupportedOperationException.class, () -> writer.previewOutbound(new OutboundRequest(context("2"), document(MODEL, List.of(), List.of(), List.of()), null)),
                "A write requires exact supplied basis bytes, never an invented blank schema");
    }

    @Test void updatesSelectedValuesAndPackageWithoutReplacingUnknownRowsBlobsOrLayout() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("CREATE TABLE vendor_state (ID INTEGER PRIMARY KEY, Payload BLOB, Label TEXT)");
            fixture.sql("INSERT INTO vendor_state VALUES (7, ?, 'retain vendor data')", new byte[]{0, 1, -1, 0, 42});
            fixture.sql("ALTER TABLE t_object ADD COLUMN VendorPayload BLOB");
            fixture.sql("UPDATE t_object SET VendorPayload=? WHERE Object_ID=10", new byte[]{9, 0, 8, -1});
            fixture.sql("CREATE TABLE t_diagramlinks (DiagramID INTEGER, ConnectorID INTEGER, Geometry TEXT, Style TEXT, Hidden INTEGER, Path TEXT, Instance_ID INTEGER PRIMARY KEY)");
            fixture.sql("INSERT INTO t_diagramlinks VALUES (30,20,'opaque geometry','routing',0,'opaque path',91)");
            byte[] basis = fixture.bytes();
            var selected = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Reviewed component", "Reviewed description",
                    Map.of("tag:owner", "Architecture team"), Map.of("canonicalType", "Component"));
            var move = new Placement("member:" + A, MODEL, "package:" + ROOT, A, 0, Map.of("placementKind", "package"));
            var result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(selected), List.of(), List.of(move)));
            assertArrayEquals(basis, fixture.bytes(), "The caller's source file must remain byte-identical");
            assertEquals("application/vnd.sparx.qea", result.mediaType());
            assertTrue(result.filename().endsWith(".qea"));
            assertEquals(List.of("10|Reviewed component|Reviewed description|1|source alias|090008FF"),
                    query(result.content(), "SELECT Object_ID,Name,Note,Package_ID,Alias,hex(VendorPayload) FROM t_object WHERE Object_ID=10"));
            assertEquals(query(basis, "SELECT * FROM vendor_state"), query(result.content(), "SELECT * FROM vendor_state"));
            assertEquals(query(basis, "SELECT * FROM t_diagramobjects ORDER BY Object_ID"), query(result.content(), "SELECT * FROM t_diagramobjects ORDER BY Object_ID"));
            assertEquals(query(basis, "SELECT * FROM t_diagramlinks"), query(result.content(), "SELECT * FROM t_diagramlinks"));
            assertEquals(query(basis, "SELECT * FROM t_object WHERE Object_ID=11"), query(result.content(), "SELECT * FROM t_object WHERE Object_ID=11"));
            assertEquals(List.of("Architecture team"), query(result.content(), "SELECT Value FROM t_objectproperties WHERE Object_ID=10 AND Property='owner'"));
            assertEquals(List.of("Taxonomy::Component"), query(result.content(), "SELECT Stereotype FROM t_object WHERE Object_ID=10"));
            assertEquals(List.of(MODEL), query(result.content(), "SELECT Value FROM usys_system WHERE Property='ProjectGUID'"));
            assertEquals(List.of("2"), query(result.content(), "SELECT count(*) FROM t_package"));
            assertEquals(List.of("1"), query(result.content(), "SELECT count(*) FROM t_connector"));
            assertTrue(result.losses().stream().noneMatch(l -> l.disposition() == LossDisposition.UNSUPPORTED),
                    "Preserved unknown basis content is not a failed requested transformation");
            var read = read(result.content());
            assertEquals("Component", artifact(read, A).extensions().get("canonicalType"));
            assertEquals("Reviewed component", artifact(read, A).title());
            assertEquals(Set.of(ROOT, CHILD, A, B, VIEW), read.artifacts().stream().map(Artifact::id).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void renamingAndMovingAPackageKeepsItsDistinctMirrorIdentityAndNativeReferences() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            String mirror = "{20000000-0000-0000-0000-000000000099}";
            fixture.sql("UPDATE t_object SET ea_guid=? WHERE Object_ID=100", mirror);
            byte[] basis = fixture.bytes();
            var selected = new Artifact(CHILD, ArtifactKind.SPECIFICATION, "Package", "Renamed package", "Changed package note", Map.of(), Map.of());
            var placement = new Placement("package:" + CHILD, MODEL, null, CHILD, 0, Map.of("placementKind", "package"));
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(selected), List.of(), List.of(placement))).content();
            assertEquals(List.of("2|Renamed package|0|" + CHILD + "|Changed package note"), query(result,
                    "SELECT Package_ID,Name,Parent_ID,ea_guid,Notes FROM t_package WHERE Package_ID=2"));
            assertEquals(List.of("100|Renamed package|0|2|" + mirror), query(result,
                    "SELECT Object_ID,Name,Package_ID,PDATA1,ea_guid FROM t_object WHERE Object_ID=100"));
            assertEquals(query(basis, "SELECT * FROM t_connector"), query(result, "SELECT * FROM t_connector"));
            assertEquals(query(basis, "SELECT * FROM t_diagramobjects"), query(result, "SELECT * FROM t_diagramobjects"));
        }
    }

    @Test void newProjectCopiesAnEmptyTemplateAndWritesReviewedGuidsAndNativeReferences() throws Exception {
        byte[] template = emptyTemplate();
        var pkg = new Artifact(NEW_PACKAGE, ArtifactKind.SPECIFICATION, "Package", "Reviewed package", "Package notes", Map.of(), Map.of());
        var element = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, "UseCase", "Capability", "Capability notes", Map.of("tag:owner", "Product"), Map.of("canonicalType", "Capability"));
        var requirement = new Artifact(NEW_REQUIREMENT, ArtifactKind.REQUIREMENT, "Requirement", "Requirement", "Requirement text", Map.of(), Map.of());
        var view = new Artifact(NEW_VIEW, ArtifactKind.VIEW, "Diagram", "Overview", "View notes", Map.of(), Map.of());
        var placements = List.of(new Placement("package:new", NEW_MODEL, null, NEW_PACKAGE, 0, Map.of("placementKind", "package")),
                new Placement("member:new", NEW_MODEL, "package:new", NEW_ELEMENT, 0, Map.of("placementKind", "package")),
                new Placement("view:new", NEW_MODEL, "package:new", NEW_VIEW, 0, Map.of("placementKind", "package")),
                new Placement("occurrence:new", NEW_VIEW, null, NEW_ELEMENT, 0, Map.of("placementKind", "diagram")));
        var selected = document(NEW_MODEL, List.of(pkg, element, requirement, view), List.of(), placements);
        byte[] result = write(template, FileExportMode.NEW_PROJECT, selected).content();
        assertEquals(List.of(NEW_MODEL), query(result, "SELECT Value FROM usys_system WHERE Property='ProjectGUID'"));
        assertEquals(List.of(NEW_ROOT), query(result, "SELECT ea_guid FROM t_package WHERE Package_ID=1"), "The host assigns the independent template-root identity before review");
        assertEquals(List.of("2|1|" + NEW_PACKAGE), query(result, "SELECT Package_ID,Parent_ID,ea_guid FROM t_package WHERE Name='Reviewed package'"));
        assertEquals(List.of(NEW_ELEMENT + "|UseCase|Taxonomy::Capability|2"), query(result,
                "SELECT ea_guid,Object_Type,Stereotype,Package_ID FROM t_object WHERE Name='Capability'"));
        assertEquals(List.of(NEW_REQUIREMENT + "|Requirement|1"), query(result,
                "SELECT ea_guid,Object_Type,Package_ID FROM t_object WHERE Name='Requirement'"));
        assertEquals(List.of(NEW_VIEW + "|2|Logical"), query(result, "SELECT ea_guid,Package_ID,Diagram_Type FROM t_diagram WHERE Name='Overview'"));
        assertEquals(List.of(NEW_VIEW + "|" + NEW_ELEMENT), query(result,
                "SELECT d.ea_guid,o.ea_guid FROM t_diagramobjects m JOIN t_diagram d ON d.Diagram_ID=m.Diagram_ID JOIN t_object o ON o.Object_ID=m.Object_ID"));
        assertEquals(List.of("ok"), query(result, "PRAGMA integrity_check"));
        assertEquals(List.of("0"), query(template, "SELECT count(*) FROM usys_system WHERE Property='ProjectGUID'"));
        assertEquals(List.of("0"), query(template, "SELECT count(*) FROM t_object"));
        assertEquals(query(template, "SELECT * FROM t_implement ORDER BY Type"), query(result, "SELECT * FROM t_implement ORDER BY Type"),
                "Native lookup/configuration rows do not make an otherwise empty template a populated model");
        assertEquals("Capability", artifact(read(result), NEW_ELEMENT).extensions().get("canonicalType"));
        assertArrayEquals(result, write(template, FileExportMode.NEW_PROJECT, selected).content(), "The same reviewed request has a reproducible binary result");
    }

    @ParameterizedTest
    @CsvSource({"Capability,UseCase", "Process,Activity", "CoreService,Interface", "COIService,Interface", "CommunicationsService,Interface",
            "UserApplication,Component", "InformationProduct,Artifact", "BusinessRole,Actor", "System,Component", "Component,Component"})
    void writesExplicitElementSemanticsWithoutTreatingUmlTransportAsTaxonomyMeaning(String canonical, String transport) throws Exception {
        var selected = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, transport, canonical, "", Map.of(), Map.of("canonicalType", canonical));
        byte[] result = write(emptyTemplate(), FileExportMode.NEW_PROJECT, document(NEW_MODEL, List.of(selected), List.of(), List.of())).content();
        assertEquals(List.of(transport + "|Taxonomy::" + canonical), query(result, "SELECT Object_Type,Stereotype FROM t_object WHERE Name='" + canonical + "'"));
        assertEquals(List.of(canonical), query(result, "SELECT Value FROM t_objectproperties WHERE Property='taxonomy.elementType'"));
        assertEquals(List.of("sparx-qea@2"), query(result, "SELECT Value FROM t_objectproperties WHERE Property='taxonomy.mappingProfile'"));
        assertEquals(canonical, artifact(read(result), NEW_ELEMENT).extensions().get("canonicalType"));
    }

    @ParameterizedTest
    @CsvSource({"DEPENDS_ON,Dependency", "REALIZES,Association", "COMMUNICATES_WITH,InformationFlow", "RELATED_TO,Association", "CONSUMES,Usage", "CONTAINS,Composition",
            "SUPPORTS,Association", "USES,Association", "FULFILLS,Association", "ASSIGNED_TO,Association", "PRODUCES,Association", "REQUIRES,Association"})
    void writesTheTwelveRelationMeaningsWithExplicitTagsAndDirection(String canonical, String transport) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var relation = new Relation(C, transport, A, B, Map.of("tag:owner", "Review"),
                    Map.of("canonicalType", canonical, "direction", "Source -> Destination"));
            byte[] result = write(fixture.bytes(), FileExportMode.UPDATE_COPY, document(MODEL, List.of(), List.of(relation), List.of())).content();
            String nativeType = canonical.equals("CONTAINS") ? "Aggregation" : transport;
            assertEquals(List.of("20|" + nativeType + "|10|11|Taxonomy::" + canonical + "|Source -> Destination"), query(result,
                    "SELECT Connector_ID,Connector_Type,Start_Object_ID,End_Object_ID,Stereotype,Direction FROM t_connector"));
            if (canonical.equals("CONTAINS")) assertEquals(List.of("2|0"), query(result, "SELECT SourceIsAggregate,DestIsAggregate FROM t_connector"),
                    "The container is the source/whole end; EA stores composite aggregation as 2");
            assertEquals(List.of(canonical), query(result, "SELECT Value FROM t_connectortag WHERE Property='taxonomy.relationType'"));
            assertEquals(List.of("Source -> Destination"), query(result, "SELECT Value FROM t_connectortag WHERE Property='taxonomy.relationDirection'"));
            assertEquals(List.of("sparx-qea@2"), query(result, "SELECT Value FROM t_connectortag WHERE Property='taxonomy.mappingProfile'"));
            var reread = read(result).relations().stream().filter(r -> r.id().equals(C)).findFirst().orElseThrow();
            assertEquals(canonical, reread.extensions().get("canonicalType"));
            assertEquals(transport, reread.type());
            assertEquals(A, reread.source()); assertEquals(B, reread.target());
            if (canonical.equals("REALIZES")) assertNotEquals("Realization", reread.type());
        }
    }

    @Test void aPartialReviewNeverDeletesOrRetypesRejectedAndUnmentionedRows() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type='VendorSpecial',Stereotype='Vendor::Secret' WHERE Object_ID=11");
            byte[] basis = fixture.bytes();
            var selected = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Accepted", "First note", Map.of(), Map.of("canonicalType", "Component"));
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(selected), List.of(), List.of())).content();
            for (String sql : List.of("SELECT * FROM t_object WHERE Object_ID=11", "SELECT * FROM t_package ORDER BY Package_ID",
                    "SELECT * FROM t_connector", "SELECT * FROM t_diagram", "SELECT * FROM t_diagramobjects ORDER BY Object_ID"))
                assertEquals(query(basis, sql), query(result, sql));
        }
    }

    @Test void updatingNoReviewedItemsReturnsTheExactBasisBytes() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            byte[] basis = fixture.bytes();
            assertArrayEquals(basis, write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(), List.of(), List.of())).content());
        }
    }

    @Test void newDiagramOccurrencesHonorFrozenInstanceIdsWithoutReplacingEarlierOccurrences() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
            fixture.sql("UPDATE t_diagramobjects SET Instance_ID=Object_ID");
            byte[] basis = fixture.bytes();
            var occurrence = new Placement("diagram:" + VIEW + ":" + A + ":71", VIEW, null, A, 2,
                    Map.of("placementKind", "diagram", "ea:t_diagramobjects:instance_id", "71", "ea:t_diagramobjects:rectleft", "410",
                            "ea:t_diagramobjects:rectright", "570", "ea:t_diagramobjects:recttop", "-20", "ea:t_diagramobjects:rectbottom", "-100"));
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(), List.of(), List.of(occurrence))).content();
            assertEquals(query(basis, "SELECT * FROM t_diagramobjects WHERE Instance_ID=10"), query(result, "SELECT * FROM t_diagramobjects WHERE Instance_ID=10"));
            assertEquals(List.of("71|410|570|-20|-100"), query(result,
                    "SELECT Instance_ID,RectLeft,RectRight,RectTop,RectBottom FROM t_diagramobjects WHERE Instance_ID=71"));
            assertEquals(2, read(result).placements().stream().filter(p -> p.containerId().equals(VIEW) && p.artifactId().equals(A)).count());
        }
    }

    @Test void aReviewedNewNativeRowRetainsTheLocalIdFromTheCandidate() throws Exception {
        var selected = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, "Component", "Frozen candidate", "",
                Map.of("ea:t_object:object_id", "71", "ea:t_object:ea_guid", NEW_ELEMENT), Map.of("canonicalType", "Component"));
        byte[] result = write(emptyTemplate(), FileExportMode.NEW_PROJECT, document(NEW_MODEL, List.of(selected), List.of(), List.of())).content();
        assertEquals(List.of("71|" + NEW_ELEMENT), query(result, "SELECT Object_ID,ea_guid FROM t_object WHERE Object_Type='Component'"));
    }

    @ParameterizedTest(name = "frozen instance {0} leaves automatic instances {1} and {2}")
    @CsvSource({"13,12,14", "12,13,14"})
    void mixedDiagramInstancesReserveFrozenIdsBeforeAllocatingAutomaticOccurrences(int frozen, int firstAutomatic,
                                                                                   int lastAutomatic) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
            fixture.sql("UPDATE t_diagramobjects SET Instance_ID=Object_ID");
            fixture.sql("CREATE UNIQUE INDEX unique_diagram_instance ON t_diagramobjects(Instance_ID)");
            byte[] basis = fixture.bytes();
            var placements = List.of(
                    new Placement("new:1-automatic", VIEW, null, A, 2, Map.of("placementKind", "diagram")),
                    new Placement("new:2-frozen", VIEW, null, B, 3,
                            Map.of("placementKind", "diagram", "ea:t_diagramobjects:instance_id", Integer.toString(frozen))),
                    new Placement("new:3-automatic", VIEW, null, A, 4, Map.of("placementKind", "diagram")));
            byte[] result = assertDoesNotThrow(() -> write(basis, FileExportMode.UPDATE_COPY,
                    document(MODEL, List.of(), List.of(), placements)).content(),
                    "A free automatic ID must not collide with an earlier or later frozen occurrence");
            assertEquals(List.of(firstAutomatic + "|10|2", frozen + "|11|3", lastAutomatic + "|10|4"), query(result,
                    "SELECT Instance_ID,Object_ID,Sequence FROM t_diagramobjects WHERE Instance_ID NOT IN (10,11) ORDER BY Sequence"));
            assertEquals(query(basis, "SELECT * FROM t_diagramobjects ORDER BY Instance_ID"),
                    query(result, "SELECT * FROM t_diagramobjects WHERE Instance_ID IN (10,11) ORDER BY Instance_ID"));
            assertEquals(List.of("5|5"), query(result, "SELECT count(*),count(DISTINCT Instance_ID) FROM t_diagramobjects"));
            assertEquals(5, read(result).placements().stream().filter(p -> p.containerId().equals(VIEW)).count());
            assertArrayEquals(result, write(basis, FileExportMode.UPDATE_COPY,
                    document(MODEL, List.of(), List.of(), placements.reversed())).content(),
                    "Input list order must not change the deterministic native occurrence assignment");
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test void mixedDiagramInstancesStillRejectTwoNewOccurrencesWithTheSameFrozenId() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
            fixture.sql("UPDATE t_diagramobjects SET Instance_ID=Object_ID");
            byte[] basis = fixture.bytes();
            var placements = List.of(
                    new Placement("new:1-automatic", VIEW, null, A, 2, Map.of("placementKind", "diagram")),
                    new Placement("new:2-frozen", VIEW, null, B, 3,
                            Map.of("placementKind", "diagram", "ea:t_diagramobjects:instance_id", "13")),
                    new Placement("new:3-frozen", VIEW, null, A, 4,
                            Map.of("placementKind", "diagram", "ea:t_diagramobjects:instance_id", "13")));
            var failure = assertThrows(IllegalArgumentException.class, () -> write(basis, FileExportMode.UPDATE_COPY,
                    document(MODEL, List.of(), List.of(), placements)));
            assertTrue(failure.getMessage().startsWith("QEA_DUPLICATE_IDENTITY:"));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test void canonicalPropertiesAreVisibleTagsAndRoundTripThroughVersionTwo() throws Exception {
        var selected = new Artifact(NEW_ELEMENT, ArtifactKind.ELEMENT, "Component", "Tagged", "", Map.of(),
                Map.of("canonicalType", "Component", "taxonomy:owner", "Architecture team", "taxonomy:status", "planned"));
        byte[] result = write(emptyTemplate(), FileExportMode.NEW_PROJECT, document(NEW_MODEL, List.of(selected), List.of(), List.of())).content();
        assertEquals(List.of("Architecture team"), query(result, "SELECT Value FROM t_objectproperties WHERE Property='taxonomy.owner'"));
        assertEquals("Architecture team", artifact(read(result), NEW_ELEMENT).extensions().get("taxonomy:owner"));
        assertEquals("planned", artifact(read(result), NEW_ELEMENT).extensions().get("taxonomy:status"));
    }

    @Test void reverseContainsKeepsTheCompositeFlagAtTheSemanticContainerEnd() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var relation = new Relation(C, "Composition", A, B, Map.of(), Map.of("canonicalType", "CONTAINS", "direction", "Destination -> Source"));
            byte[] result = write(fixture.bytes(), FileExportMode.UPDATE_COPY, document(MODEL, List.of(), List.of(relation), List.of())).content();
            assertEquals(List.of("Aggregation|0|2|10|11|Destination -> Source"), query(result,
                    "SELECT Connector_Type,SourceIsAggregate,DestIsAggregate,Start_Object_ID,End_Object_ID,Direction FROM t_connector"));
            var read = read(result).relations().getFirst();
            assertEquals("CONTAINS", read.extensions().get("canonicalType"));
            assertEquals("Destination -> Source", read.extensions().get("direction"));
        }
    }

    @Test void unrelatedMalformedAuxiliaryTagGuidIsPreservedInsteadOfBlockingANameChange() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_objectproperties VALUES (63,11,'vendor','opaque','opaque memo','vendor-local-key')");
            byte[] basis = fixture.bytes();
            var selected = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Renamed", "First note", Map.of(), Map.of("canonicalType", "Component"));
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(selected), List.of(), List.of())).content();
            assertEquals(query(basis, "SELECT * FROM t_objectproperties WHERE PropertyID=63"), query(result, "SELECT * FROM t_objectproperties WHERE PropertyID=63"));
        }
    }

    @Test void unchangedUnresolvedNativeConnectorSurvivesASelectedNameChange() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_connector SET End_Object_ID=999");
            byte[] basis = fixture.bytes(); var before = read(basis);
            var artifacts = before.artifacts().stream().map(a -> a.id().equals(A)
                    ? new Artifact(a.id(), a.kind(), a.type(), "Renamed", a.text(), a.attributes(), a.extensions()) : a).toList();
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, artifacts, before.relations(), before.placements())).content();
            assertEquals(query(basis, "SELECT * FROM t_connector"), query(result, "SELECT * FROM t_connector"));
        }
    }

    @Test void memoTagsAndUnrelatedQualifiedStereotypesArePreservedDuringANameChange() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_objectproperties VALUES (61,10,'owner','<memo>','Existing long memo',?)", "{71000000-0000-0000-0000-000000000001}");
            fixture.sql("INSERT INTO t_xref VALUES (?, 'VendorData', 'element property', ?, '', 'opaque', 'opaque cross reference')",
                    "{71000000-0000-0000-0000-000000000002}", A);
            byte[] basis = fixture.bytes();
            var selected = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Renamed", "First note", Map.of("tag:owner", "Changed memo"), Map.of("canonicalType", "Component"));
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(selected), List.of(), List.of())).content();
            assertEquals(List.of("61|<memo>|Changed memo|{71000000-0000-0000-0000-000000000001}"), query(result,
                    "SELECT PropertyID,Value,Notes,ea_guid FROM t_objectproperties WHERE PropertyID=61"));
            assertEquals(query(basis, "SELECT * FROM t_xref WHERE Name='VendorData'"), query(result, "SELECT * FROM t_xref WHERE Name='VendorData'"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "NULL"})
    void aNameChangeKeepsAnEmptyMemoTaggedValueByteForByte(String notes) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_objectproperties VALUES (61,10,'owner','<memo>',?,?)", notes.equals("NULL") ? null : notes,
                    "{71000000-0000-0000-0000-000000000001}");
            byte[] basis = fixture.bytes(); var original = artifact(read(basis), A);
            assertEquals("<memo>", original.attributes().get("tag:owner"), "Retain the existing read-only projection of a memo without text");
            var renamed = new Artifact(A, original.kind(), original.type(), "Title only", original.text(), original.attributes(), original.extensions());
            byte[] result = write(basis, FileExportMode.UPDATE_COPY, document(MODEL, List.of(renamed), List.of(), List.of())).content();
            assertEquals(query(basis, "SELECT * FROM t_objectproperties WHERE PropertyID=61"), query(result, "SELECT * FROM t_objectproperties WHERE PropertyID=61"));
            assertEquals("Title only", artifact(read(result), A).title());
        }
    }

    @Test void anExplicitEmptyTagClearsMemoTextAndReadsBackAsEmpty() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_objectproperties VALUES (61,10,'owner','<memo>','Old memo text',?)", "{71000000-0000-0000-0000-000000000001}");
            var original = artifact(read(fixture.bytes()), A); var attributes = new HashMap<>(original.attributes()); attributes.put("tag:owner", "");
            var selected = new Artifact(A, original.kind(), original.type(), original.title(), original.text(), attributes, original.extensions());
            byte[] result = write(fixture.bytes(), FileExportMode.UPDATE_COPY, document(MODEL, List.of(selected), List.of(), List.of())).content();
            assertEquals(List.of("|"), query(result, "SELECT Value,Notes FROM t_objectproperties WHERE PropertyID=61"));
            assertEquals("", artifact(read(result), A).attributes().get("tag:owner"));
        }
    }

    @Test void writesTheSyntheticTemplateForTheInstalledJarAcceptanceWithoutBundlingIt() throws Exception {
        byte[] template = emptyTemplate();
        assertEquals(List.of("0"), query(template, "SELECT count(*) FROM usys_system WHERE Property='ProjectGUID'"));
        assertEquals(List.of("1"), query(template, "SELECT count(*) FROM t_package"));
        assertEquals(List.of("0"), query(template, "SELECT count(*) FROM t_object"));
        Path fixtures = Path.of("target", "packaged-fixtures"); Files.createDirectories(fixtures);
        Files.write(fixtures.resolve("empty-template.qea"), template);
    }

    @Test void readerDoesNotInterpretUmlRealizationAsTaxonomyRealizes() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_connector SET Connector_Type='Realization'");
            var relation = read(fixture.bytes()).relations().getFirst();
            assertFalse(relation.extensions().containsKey("canonicalType"));
        }
    }

    @Test void readerRejectsContradictoryTaxonomyDeclarationsInsteadOfGuessingMeaning() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_connector SET Connector_Type='Association',Stereotype='Taxonomy::REALIZES'");
            fixture.sql("INSERT INTO t_connectortag VALUES (1,20,'taxonomy.relationType','REALIZES','',NULL)");
            fixture.sql("INSERT INTO t_connectortag VALUES (2,20,'taxonomy.relationDirection','Destination -> Source','',NULL)");
            fixture.sql("INSERT INTO t_connectortag VALUES (3,20,'taxonomy.mappingProfile','sparx-qea@2','',NULL)");
            var result = read(fixture.bytes());
            assertFalse(result.relations().getFirst().extensions().containsKey("canonicalType"));
            assertTrue(result.losses().stream().anyMatch(l -> l.code().equals("QEA_TAXONOMY_DECLARATION_INVALID")));
        }
    }

    private byte[] emptyTemplate() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            for (String table : List.of("t_object", "t_connector", "t_diagram", "t_diagramobjects", "t_objectproperties", "t_connectortag", "t_xref")) fixture.sql("DELETE FROM " + table);
            fixture.sql("DELETE FROM t_package WHERE Package_ID<>1");
            fixture.sql("DELETE FROM usys_system");
            fixture.sql("INSERT INTO usys_system VALUES ('Version','4.01')");
            fixture.sql("CREATE TABLE t_implement (Type TEXT)");
            fixture.sql("INSERT INTO t_implement VALUES ('Requirement'),('UseCase'),('Component')");
            fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
            return fixture.bytes();
        }
    }

    private static LifecycleIntegrationConnector connector() {
        return assertInstanceOf(LifecycleIntegrationConnector.class, new SparxPlugin().extensions().stream().filter(e -> e.id().equals("sparx-qea@2")).findFirst().orElse(null),
                "Install an explicit version 2 writer while retaining the version 1 registration");
    }
    private static IntegrationContext context(String version) {
        return new IntegrationContext(NAMESPACE, AuthorityMode.PUBLISH_TARGET, new ExternalScope("sparx", "model", null),
                new InternalState("repository", "workspace", "draft", null, 1, null, null), "reviewer", "sparx-qea", version);
    }
    private static ExchangeDocument document(String model, List<Artifact> artifacts, List<Relation> relations, List<Placement> placements) {
        return new ExchangeDocument("sparx-qea", "2", null, false, "Reviewed canonical values", artifacts, relations, placements,
                model.equals(NEW_MODEL) ? Map.of("identifier", model, "rootIdentifier", NEW_ROOT) : Map.of("identifier", model), List.of());
    }
    private static ExchangeFile write(byte[] basis, FileExportMode mode, ExchangeDocument document) throws Exception {
        return connector().previewOutbound(new BasedOutboundRequest(new OutboundRequest(context("2"), document, null), mode,
                NAMESPACE, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis)), "application/vnd.sparx.qea", basis));
    }
    private static ExchangeDocument read(byte[] bytes) {
        return connector().previewInbound(new InboundRequest(context("2"), "application/vnd.sparx.qea", bytes, null, false));
    }
    private static Artifact artifact(ExchangeDocument document, String id) {
        return document.artifacts().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }
    private List<String> query(byte[] bytes, String sql) throws Exception {
        Path copy = Files.createTempFile(directory, "inspect-", ".qea"); Files.write(copy, bytes);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + copy); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            var result = new ArrayList<String>();
            while (rows.next()) {
                var fields = new ArrayList<String>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    Object value = rows.getObject(i);
                    fields.add(value instanceof byte[] binary ? HexFormat.of().formatHex(binary) : Objects.toString(value, "NULL"));
                }
                result.add(String.join("|", fields));
            }
            return result;
        } finally { Files.deleteIfExists(copy); }
    }
}
