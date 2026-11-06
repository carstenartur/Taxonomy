package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import com.taxonomy.extension.api.plugin.TaxonomyPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class SparxQeaImportTest {
    @TempDir Path directory;

    @Test void readsGuidIdentitiesPackageHierarchyAndDiagramMembershipWithoutConflatingThem() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var document = read(fixture.bytes());
            assertEquals(Set.of(ROOT, CHILD, A, B, VIEW), ids(document));
            assertEquals(2, document.artifacts().stream().filter(a -> a.title().equals("Same name")).count());
            assertEquals(ArtifactKind.SPECIFICATION, artifact(document, CHILD).kind());
            assertEquals(ArtifactKind.VIEW, artifact(document, VIEW).kind());
            assertEquals("Component", artifact(document, A).extensions().get("canonicalType"));
            assertEquals(MODEL, document.metadata().get("identifier"));
            var member = placement(document, "member:" + A);
            assertEquals(MODEL, member.containerId());
            assertEquals("package:" + CHILD, member.parentId());
            assertEquals("package", member.attributes().get("placementKind"));
            assertEquals("package:" + ROOT, placement(document, "package:" + CHILD).parentId());
            var diagram = placement(document, "diagram:" + VIEW + ":" + A);
            assertEquals(VIEW, diagram.containerId());
            assertNull(diagram.parentId());
            assertEquals("diagram", diagram.attributes().get("placementKind"));
            assertEquals("-20", diagram.attributes().get("ea:t_diagramobjects:recttop"));
            assertEquals(A, document.relations().getFirst().source());
            assertEquals(B, document.relations().getFirst().target());
            assertEquals("DEPENDS_ON", document.relations().getFirst().extensions().get("canonicalType"));
            assertFalse(document.completeScope(), "The supported QEA projection is not deletion authority for an entire EA repository");
            assertDoesNotThrow(() -> connector().validateInboundSelection(selection(document)));
        }
    }

    @Test void repeatedReadsAndAFileRenameHaveIdenticalProjectionAndNeverChangeTheSource() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            byte[] input = fixture.bytes();
            byte[] untouched = input.clone();
            var first = read(input);
            Path renamed = directory.resolve("a-different-file-name.qea");
            Files.copy(fixture.file, renamed);
            assertEquals(first, read(Files.readAllBytes(renamed)));
            assertEquals(first, read(input));
            assertArrayEquals(untouched, input);
            assertArrayEquals(untouched, fixture.bytes());
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
            assertEquals(digest, first.metadata().get("sourceSha256"));
            assertTrue(first.source().contains(digest));
            assertTrue(first.source().length() < 256, "The database is represented by provenance, never binary or base64 source text");
        }
    }

    @Test void renamingAndMovingAnElementRetainsItsIdentityAndWritesPackagingFixtures() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var before = read(fixture.bytes());
            Path exports = Path.of("target", "packaged-fixtures");
            Files.createDirectories(exports);
            Files.copy(fixture.file, exports.resolve("base.qea"), StandardCopyOption.REPLACE_EXISTING);
            fixture.sql("UPDATE t_object SET Name='Renamed component', Package_ID=1 WHERE Object_ID=10");
            var after = read(fixture.bytes());
            assertEquals(ids(before), ids(after));
            assertEquals("Renamed component", artifact(after, A).title());
            assertEquals("package:" + ROOT, placement(after, "member:" + A).parentId());
            assertEquals(before.relations(), after.relations());
            assertNotEquals(before.metadata().get("sourceSha256"), after.metadata().get("sourceSha256"));
            assertDoesNotThrow(() -> connector().validateInboundSelection(selection(after)));
            Files.copy(fixture.file, exports.resolve("renamed.qea"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void canonicalGuidSpellingAndPackageAliasesDoNotCreateDuplicateElements() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET ea_guid=? WHERE Object_ID=10", A.toLowerCase(Locale.ROOT));
            fixture.sql("UPDATE t_object SET ea_guid='{20000000-0000-0000-0000-000000000099}' WHERE Object_ID=100");
            fixture.sql("UPDATE t_connector SET End_Object_ID=100 WHERE Connector_ID=20");
            var document = read(fixture.bytes());
            assertEquals(Set.of(ROOT, CHILD, A, B, VIEW), ids(document));
            assertEquals(CHILD, document.relations().getFirst().target());
            assertEquals("{20000000-0000-0000-0000-000000000099}", artifact(document, CHILD).extensions().get("ea:packageObjectGuid"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "not-a-guid", "{3-0-0-0-1}"})
    void rejectsMissingOrMalformedExternalIdentityInsteadOfDerivingItFromAName(String guid) throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET ea_guid=? WHERE Object_ID=10", guid);
            fails("QEA_IDENTITY_REQUIRED", () -> read(fixture.bytes()));
        }
    }

    @Test void rejectsDuplicateGuidsAcrossDifferentObjects() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET ea_guid=? WHERE Object_ID=11", A);
            fails("QEA_DUPLICATE_IDENTITY", () -> read(fixture.bytes()));
        }
    }

    @Test void requiresOneStableModelIdentityRatherThanUsingFilenameOrFileHash() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("DELETE FROM usys_system");
            fails("QEA_MODEL_IDENTITY", () -> read(fixture.bytes()));
            fixture.sql("INSERT INTO usys_system VALUES ('ProjectGUID', ?), ('ProjectGUID', ?)", MODEL, MODEL);
            fails("QEA_MODEL_IDENTITY", () -> read(fixture.bytes()));
        }
    }

    @Test void leavesUnknownStereotypesAndUmlRealizationForExplicitMapping() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type='Class', Stereotype='Vendor::Unmapped' WHERE Object_ID=10");
            fixture.sql("UPDATE t_connector SET Connector_Type='Realisation' WHERE Connector_ID=20");
            var document = read(fixture.bytes());
            assertEquals("Class", artifact(document, A).type());
            assertEquals("Vendor::Unmapped", artifact(document, A).extensions().get("stereotype"));
            assertFalse(artifact(document, A).extensions().containsKey("canonicalType"));
            var relation = document.relations().getFirst();
            assertFalse(relation.extensions().containsKey("canonicalType"));
            assertEquals(A, relation.source()); assertEquals(B, relation.target());
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_ELEMENT_UNMAPPED") && l.artifactId().equals(A)));
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_RELATION_UNMAPPED") && l.artifactId().equals(C)));
            fails("QEA_ELEMENT_MAPPING_REQUIRED", () -> connector().validateInboundSelection(selection(document)));
            var remapped = remapElement(document, A, "Component");
            fails("QEA_RELATION_MAPPING_REQUIRED", () -> connector().validateInboundSelection(selection(remapped)));
            var preserved = preserveRelations(remapped);
            assertDoesNotThrow(() -> connector().validateInboundSelection(selection(preserved)));
        }
    }

    @Test void retainsTaggedValuesDuplicateTagsQualifiedStereotypesAndRawEvidence() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_objectproperties VALUES (1,10,'owner','Team A','note','{60000000-0000-0000-0000-000000000001}')");
            fixture.sql("INSERT INTO t_objectproperties VALUES (2,10,'owner','Team B','','{60000000-0000-0000-0000-000000000002}')");
            fixture.sql("INSERT INTO t_objectproperties VALUES (3,11,'status','approved','','{60000000-0000-0000-0000-000000000003}')");
            fixture.sql("INSERT INTO t_connectortag VALUES (4,20,'trace','source paragraph','','{60000000-0000-0000-0000-000000000004}')");
            fixture.sql("INSERT INTO t_xref VALUES ('{70000000-0000-0000-0000-000000000001}','Stereotypes','element property',?,'','','@STEREO;Name=System;FQName=Taxonomy::System;@ENDSTEREO;')", B);
            var document = read(fixture.bytes());
            var a = artifact(document, A);
            assertFalse(a.attributes().containsKey("tag:owner"), "Duplicate values must not be overwritten by a map entry");
            assertTrue(a.attributes().containsValue("Team A")); assertTrue(a.attributes().containsValue("Team B"));
            assertEquals("source alias", a.attributes().get("ea:t_object:alias"));
            assertEquals("approved", artifact(document, B).attributes().get("tag:status"));
            assertEquals("Taxonomy::System", artifact(document, B).extensions().get("stereotype"));
            assertEquals("System", artifact(document, B).extensions().get("canonicalType"));
            assertEquals("source paragraph", document.relations().getFirst().attributes().get("tag:trace"));
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_DUPLICATE_TAG")));
        }
    }

    @Test void derivesImplicitReferencesWithStableIdsAndNeverPretendsTheyAreCanonicalRelations() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Object_Type='Port', ParentID=11, PDATA1=?, PDATA3=? WHERE Object_ID=10", B, B);
            var before = read(fixture.bytes());
            var inferred = before.relations().stream().filter(r -> r.id().startsWith("implicit:")).toList();
            assertEquals(Set.of("HAS_PORT", "INSTANCE_OF", "REUSAGE"), inferred.stream().map(Relation::type).collect(java.util.stream.Collectors.toSet()));
            assertTrue(inferred.stream().allMatch(r -> !r.extensions().containsKey("canonicalType")));
            var parent = inferred.stream().filter(r -> r.type().equals("HAS_PORT")).findFirst().orElseThrow();
            assertEquals(B, parent.source()); assertEquals(A, parent.target());
            fixture.sql("UPDATE t_object SET Name='renamed port' WHERE Object_ID=10");
            assertEquals(inferred, read(fixture.bytes()).relations().stream().filter(r -> r.id().startsWith("implicit:")).toList());
            assertTrue(before.losses().stream().anyMatch(l -> l.code().equals("QEA_IMPLICIT_RELATION")));
        }
    }

    @Test void doesNotApplyAttributeCrossReferenceSemanticsToAnElementWithTheSameClientGuid() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_xref VALUES ('{70000000-0000-0000-0000-000000000001}','Stereotypes','attribute property',?,'','','@STEREO;Name=System;FQName=Taxonomy::System;@ENDSTEREO;')", A);
            var document = read(fixture.bytes());
            assertEquals("Component", artifact(document, A).extensions().get("canonicalType"));
            assertTrue(artifact(document, A).attributes().values().stream().anyMatch(v -> v.contains("Taxonomy::System")), "Unsupported xref content remains evidence");
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_XREF_KIND_UNSUPPORTED")));
        }
    }

    @Test void unresolvedEndpointsRemainVisibleAndRequirePreserveOnlyOrRejection() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_connector SET End_Object_ID=999 WHERE Connector_ID=20");
            var document = read(fixture.bytes());
            assertEquals("unresolved:object:999", document.relations().getFirst().target());
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_UNRESOLVED_REFERENCE")));
            fails("QEA_SELECTION_DEPENDENCY", () -> connector().validateInboundSelection(selection(document)));
            assertDoesNotThrow(() -> connector().validateInboundSelection(selection(preserveRelations(document))));
        }
    }

    @Test void rejectsAReviewedSelectionWhoseAcceptedRelationLostItsEndpoint() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var document = read(fixture.bytes());
            var selected = copy(document, document.artifacts().stream().filter(a -> !a.id().equals(B)).toList(), document.relations(), List.of());
            fails("QEA_SELECTION_DEPENDENCY", () -> connector().validateInboundSelection(selection(selected)));
        }
    }

    @Test void rejectsAReviewedSelectionWithARejectedParentPackage() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var document = read(fixture.bytes());
            var selected = copy(document, document.artifacts().stream().filter(a -> !a.id().equals(CHILD)).toList(), List.of(),
                    document.placements().stream().filter(p -> !Objects.equals(p.artifactId(), CHILD)).toList());
            fails("QEA_SELECTION_DEPENDENCY", () -> connector().validateInboundSelection(selection(selected)));
        }
    }

    @Test void rejectsPackageCyclesRatherThanFlatteningTheModel() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_package SET Parent_ID=2 WHERE Package_ID=1");
            fails("QEA_PACKAGE_CYCLE", () -> read(fixture.bytes()));
        }
    }

    @Test void unsupportedTablesAndDiagramRoutingAreDeclaredLosses() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("CREATE TABLE t_diagramlinks (DiagramID INTEGER, ConnectorID INTEGER, Geometry TEXT)");
            fixture.sql("INSERT INTO t_diagramlinks VALUES (30,20,'unmapped routing')");
            fixture.sql("CREATE TABLE vendor_data (payload BLOB)");
            var document = read(fixture.bytes());
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_TABLE_UNSUPPORTED") && l.field().equals("t_diagramlinks")));
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_TABLE_UNSUPPORTED") && l.field().equals("vendor_data")));
            assertFalse(document.completeScope());
        }
    }

    @Test void unprojectedSystemPropertiesAreDeclaredRatherThanDisappearingSilently() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO usys_system VALUES ('UnsupportedSetting', 'original value')");
            var document = read(fixture.bytes());
            assertTrue(document.losses().stream().anyMatch(l -> l.code().equals("QEA_SYSTEM_PROPERTY_UNSUPPORTED") && l.field().equals("usys_system:UnsupportedSetting")));
        }
    }

    @Test void rejectsViewsMasqueradingAsSupportedTablesWithoutEvaluatingThem() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("DROP TABLE t_object");
            fixture.sql("CREATE VIEW t_object AS SELECT load_extension('must-not-load') AS Object_ID");
            fails("QEA_SCHEMA_UNSUPPORTED", () -> read(fixture.bytes()));
        }
    }

    @Test void boundsIndividualCellsBeforeRetainingEvidence() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Note=? WHERE Object_ID=10", "n".repeat(65_537));
            fails("QEA_LIMIT", () -> read(fixture.bytes()));
        }
    }

    @Test void boundsTotalRowsWithoutTruncatingTheImport() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("WITH RECURSIVE n(v) AS (SELECT 1 UNION ALL SELECT v+1 FROM n WHERE v<20001) INSERT INTO t_objectproperties(PropertyID,Object_ID,Property,Value) SELECT v,10,'tag-'||v,'value' FROM n");
            fails("QEA_LIMIT", () -> read(fixture.bytes()));
        }
    }

    @Test void rejectsOversizedInvalidAndTruncatedDatabases() throws Exception {
        fails("QEA_LIMIT", () -> read(new byte[32 * 1024 * 1024 + 1]));
        fails("QEA_FILE_INVALID", () -> read("not a database".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try (var fixture = new QeaFixture(directory)) {
            fails("QEA_FILE_INVALID", () -> read(Arrays.copyOf(fixture.bytes(), 100)));
        }
    }

    @Test void exposesReadOnlyCapabilitiesAndRejectsWrongMediaAndPublication() throws Exception {
        assertEquals(Set.of("FILE_IMPORT", "ARCHITECTURE_MODEL", "CANONICAL_PROJECTION_V1"),
                connector().descriptor().capabilities().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
        try (var fixture = new QeaFixture(directory)) {
            fails("QEA_MEDIA_TYPE", () -> connector().previewInbound(new InboundRequest(context(), "application/xml", fixture.bytes(), "v1", true)));
            var document = read(fixture.bytes());
            assertThrows(UnsupportedOperationException.class, () -> connector().previewOutbound(selection(document)));
        }
    }

    private static LifecycleIntegrationConnector connector() {
        return ServiceLoader.load(TaxonomyPlugin.class).stream().map(ServiceLoader.Provider::get)
                .flatMap(p -> p.extensions().stream()).filter(LifecycleIntegrationConnector.class::isInstance)
                .map(LifecycleIntegrationConnector.class::cast).findFirst().orElseThrow();
    }
    private static ExchangeDocument read(byte[] bytes) { return connector().previewInbound(new InboundRequest(context(), "application/octet-stream", bytes, "fixture-v1", true)); }
    private static OutboundRequest selection(ExchangeDocument document) { return new OutboundRequest(context(), document, "fixture-v1"); }
    private static IntegrationContext context() {
        return new IntegrationContext(UUID.fromString("00000000-0000-0000-0000-000000000001"), AuthorityMode.IMPORT_COPY,
                new ExternalScope("sparx", "fixture", null), new InternalState("repository", "workspace", "main", null, 0, null, null),
                "test", "sparx-qea", "1");
    }
    private static Set<String> ids(ExchangeDocument document) { return document.artifacts().stream().map(Artifact::id).collect(java.util.stream.Collectors.toSet()); }
    private static Artifact artifact(ExchangeDocument document, String id) { return document.artifacts().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(); }
    private static Placement placement(ExchangeDocument document, String id) { return document.placements().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(); }
    private static void fails(String code, org.junit.jupiter.api.function.Executable work) {
        var failure = assertThrows(IllegalArgumentException.class, work);
        assertTrue(failure.getMessage().startsWith(code + ":"), failure.getMessage());
    }
    private static ExchangeDocument remapElement(ExchangeDocument doc, String id, String type) {
        return copy(doc, doc.artifacts().stream().map(a -> {
            if (!a.id().equals(id)) return a;
            var extensions = new TreeMap<>(a.extensions()); extensions.put("canonicalType", type);
            return new Artifact(a.id(), a.kind(), a.type(), a.title(), a.text(), a.attributes(), extensions);
        }).toList(), doc.relations(), doc.placements());
    }
    private static ExchangeDocument preserveRelations(ExchangeDocument doc) {
        return copy(doc, doc.artifacts(), doc.relations().stream().map(r -> {
            var extensions = new TreeMap<>(r.extensions()); extensions.put("nativeProjection", "PRESERVE_ONLY");
            return new Relation(r.id(), r.type(), r.source(), r.target(), r.attributes(), extensions);
        }).toList(), doc.placements());
    }
    private static ExchangeDocument copy(ExchangeDocument doc, List<Artifact> artifacts, List<Relation> relations, List<Placement> placements) {
        return new ExchangeDocument(doc.profile(), doc.profileVersion(), doc.externalVersion(), doc.completeScope(), doc.source(), artifacts, relations, placements, doc.metadata(), doc.losses());
    }
}
