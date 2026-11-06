package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.LifecycleIntegrationConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static com.taxonomy.plugins.sparx.QeaFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class SparxQeaWriteValidationTest {
    private static final UUID NAMESPACE = UUID.fromString("d7a9a0f3-8ef7-4e55-9558-9e5008a54d91");
    @TempDir Path directory;

    @Test void bindsTheMutationToTheExactBasisDigest() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            byte[] basis = fixture.bytes();
            var request = request(basis, FileExportMode.UPDATE_COPY, selected(MODEL, List.of(renamed()), List.of(), List.of()));
            fails("QEA_BASIS_CHANGED", new BasedOutboundRequest(request.outbound(), request.mode(), request.identityNamespace(),
                    "0".repeat(64), request.baseMediaType(), request.baseContent()));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test void anUpdateCannotReplaceTheModelIdentity() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fails("QEA_MODEL_IDENTITY", request(fixture.bytes(), FileExportMode.UPDATE_COPY,
                    selected("{99999999-0000-0000-0000-000000000000}", List.of(renamed()), List.of(), List.of())));
        }
    }

    @Test void aNewProjectRejectsAPopulatedTemplateWithoutDeletingItsContents() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            byte[] basis = fixture.bytes();
            fails("QEA_TEMPLATE_NOT_EMPTY", request(basis, FileExportMode.NEW_PROJECT, selected(MODEL, List.of(renamed()), List.of(), List.of())));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test void duplicateReviewedGuidSpellingsCannotCreateTwoObjects() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var duplicate = new Artifact(A.toLowerCase(Locale.ROOT), ArtifactKind.ELEMENT, "Component", "Other", "", Map.of(), Map.of("canonicalType", "Component"));
            fails("QEA_DUPLICATE_IDENTITY", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(renamed(), duplicate), List.of(), List.of())));
        }
    }

    @Test void aReviewedGuidCannotChangeItsNativeObjectKind() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var changedKind = new Artifact(A, ArtifactKind.VIEW, "Diagram", "Cannot become a diagram", "", Map.of(), Map.of());
            fails("QEA_IDENTITY_CONFLICT", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(changedKind), List.of(), List.of())));
        }
    }

    @Test void unsupportedElementSemanticsFailBeforeReturningAnyFile() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var unsupported = new Artifact(A, ArtifactKind.ELEMENT, "VendorType", "Unknown", "", Map.of(), Map.of("canonicalType", "UnmappedCustomType"));
            fails("QEA_ELEMENT_MAPPING_REQUIRED", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(unsupported), List.of(), List.of())));
        }
    }

    @Test void anUnknownRequiredInsertColumnIsNotInvented() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("DELETE FROM t_diagramobjects"); fixture.sql("DELETE FROM t_connector"); fixture.sql("DELETE FROM t_object");
            fixture.sql("ALTER TABLE t_object ADD COLUMN VendorRequired TEXT NOT NULL");
            var addition = new Artifact(A, ArtifactKind.ELEMENT, "Component", "New", "", Map.of(), Map.of("canonicalType", "Component"));
            fails("QEA_SCHEMA_UNSUPPORTED", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(addition), List.of(), List.of())));
        }
    }

    @Test void missingWritableColumnsAreRejectedInsteadOfDroppingSelectedText() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("ALTER TABLE t_object DROP COLUMN Note");
            fails("QEA_SCHEMA_UNSUPPORTED", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(renamed()), List.of(), List.of())));
        }
    }

    @Test void triggersCannotModifyUnreviewedTablesAsAWriteSideEffect() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("CREATE TABLE private_audit (value TEXT)");
            fixture.sql("CREATE TRIGGER hidden_effect AFTER UPDATE ON t_object BEGIN INSERT INTO private_audit VALUES ('unexpected'); END");
            byte[] basis = fixture.bytes();
            fails("QEA_SCHEMA_UNSUPPORTED", request(basis, FileExportMode.UPDATE_COPY, selected(MODEL, List.of(renamed()), List.of(), List.of())));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test void duplicateWritableTagNamesAreNotSilentlyCollapsed() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("INSERT INTO t_objectproperties VALUES (1,10,'owner','first','',NULL)");
            fixture.sql("INSERT INTO t_objectproperties VALUES (2,10,'owner','second','',NULL)");
            var artifact = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Renamed", "", Map.of("tag:owner", "Reviewed"), Map.of("canonicalType", "Component"));
            fails("QEA_AMBIGUOUS_TAG", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(artifact), List.of(), List.of())));
        }
    }

    @Test void relationDirectionMustBeExplicitAndCannotContradictASelectedTaxonomyTag() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var ambiguous = new Relation(C, "Association", A, B, Map.of(), Map.of("canonicalType", "REALIZES", "direction", "Bi-Directional"));
            fails("QEA_RELATION_DIRECTION", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(), List.of(ambiguous), List.of())));
            var conflicting = new Relation(C, "Association", A, B, Map.of("tag:taxonomy.relationType", "DEPENDS_ON"),
                    Map.of("canonicalType", "REALIZES", "direction", "Source -> Destination"));
            fails("QEA_TAXONOMY_DECLARATION_INVALID", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(), List.of(conflicting), List.of())));
        }
    }

    @Test void anUnavailableEndpointCannotBeCreatedFromANumericOrNameFallback() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var dangling = new Relation(C, "Dependency", A, "{99999999-0000-0000-0000-000000000001}", Map.of(),
                    Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination"));
            fails("QEA_SELECTION_DEPENDENCY", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(), List.of(dangling), List.of())));
        }
    }

    @Test void packageCyclesAreRejectedBeforeMutatingTheCopy() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var cycle = new Placement("package:" + ROOT, MODEL, "package:" + CHILD, ROOT, 0, Map.of("placementKind", "package"));
            fails("QEA_PACKAGE_CYCLE", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(), List.of(), List.of(cycle))));
        }
    }

    @Test void implicitReferencesAreNotMaterializedAsInventedNativeConnectors() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET ParentID=10 WHERE Object_ID=11");
            var implicit = new Relation("implicit:123", "Association", A, B, Map.of(), Map.of("canonicalType", "RELATED_TO", "direction", "Unspecified"));
            fails("QEA_IMPLICIT_REFERENCE_UNSUPPORTED", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(), List.of(implicit), List.of())));
        }
    }

    @Test void aBinaryCellCannotBeOverwrittenByItsEvidenceHash() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            fixture.sql("UPDATE t_object SET Note=? WHERE Object_ID=10", new byte[]{1, 0, -1});
            fails("QEA_BINARY_FIELD_UNSUPPORTED", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(renamed()), List.of(), List.of())));
        }
    }

    @Test void oversizedReviewedValuesAreRejectedBeforeWriting() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var oversized = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Reviewed", "", Map.of("tag:owner", "x".repeat(65_537)), Map.of("canonicalType", "Component"));
            fails("QEA_LIMIT", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(oversized), List.of(), List.of())));
        }
    }

    @Test void nativeLayoutChangesNeedAnExplicitSupportedWriterInsteadOfSilentDiscard() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            var moved = new Placement("diagram:" + VIEW + ":" + A, VIEW, null, A, 0,
                    Map.of("placementKind", "diagram", "ea:t_diagramobjects:rectleft", "999"));
            fails("QEA_LAYOUT_UNSUPPORTED", request(fixture.bytes(), FileExportMode.UPDATE_COPY, selected(MODEL, List.of(), List.of(), List.of(moved))));
        }
    }

    @Test void aTemplateCannotCarryDanglingNativeDiagramLinks() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            for (String table : List.of("t_object", "t_connector", "t_diagram", "t_diagramobjects")) fixture.sql("DELETE FROM " + table);
            fixture.sql("DELETE FROM t_package WHERE Package_ID<>1");
            fixture.sql("CREATE TABLE t_diagramlinks (DiagramID INTEGER, ConnectorID INTEGER, Instance_ID INTEGER PRIMARY KEY)");
            fixture.sql("INSERT INTO t_diagramlinks VALUES (30,20,1)");
            var outbound = selected(MODEL, List.of(), List.of(), List.of());
            var withRoot = new ExchangeDocument(outbound.profile(), outbound.profileVersion(), null, false, "Empty candidate", List.of(), List.of(), List.of(),
                    Map.of("identifier", MODEL, "rootIdentifier", "{90000000-0000-0000-0000-000000000001}"), List.of());
            fails("QEA_TEMPLATE_NOT_EMPTY", request(fixture.bytes(), FileExportMode.NEW_PROJECT, withRoot));
        }
    }

    @Test void foreignKeyCascadesCannotRewriteUnknownTemplateRows() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            for (String table : List.of("t_object", "t_connector", "t_diagram", "t_diagramobjects")) fixture.sql("DELETE FROM " + table);
            fixture.sql("DELETE FROM t_package WHERE Package_ID<>1");
            fixture.sql("CREATE UNIQUE INDEX uq_template_package_guid ON t_package(ea_guid)");
            fixture.sql("CREATE TABLE vendor_root (RootGUID TEXT REFERENCES t_package(ea_guid) ON UPDATE CASCADE)");
            fixture.sql("INSERT INTO vendor_root VALUES (?)", ROOT);
            var candidate = new ExchangeDocument("sparx-qea", "2", null, false, "Empty candidate", List.of(), List.of(), List.of(),
                    Map.of("identifier", MODEL, "rootIdentifier", "{90000000-0000-0000-0000-000000000001}"), List.of());
            fails("QEA_SCHEMA_UNSUPPORTED", request(fixture.bytes(), FileExportMode.NEW_PROJECT, candidate));
        }
    }

    @Test void aSchemaReplacePolicyCannotDeleteAnUnselectedRowDuringUpdate() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            replacingNameConstraint(fixture);
            byte[] basis = fixture.bytes();
            var renamed = new Artifact(A, ArtifactKind.ELEMENT, "Component", "Protected", "Reviewed", Map.of(), Map.of("canonicalType", "Component"));
            fails("QEA_WRITE_REJECTED", request(basis, FileExportMode.UPDATE_COPY, selected(MODEL, List.of(renamed), List.of(), List.of())));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    @Test void aSchemaReplacePolicyCannotDeleteAnUnselectedRowDuringInsert() throws Exception {
        try (var fixture = new QeaFixture(directory)) {
            replacingNameConstraint(fixture);
            byte[] basis = fixture.bytes();
            var addition = new Artifact("{90000000-0000-0000-0000-000000000002}", ArtifactKind.ELEMENT, "Component", "Protected", "New", Map.of(), Map.of("canonicalType", "Component"));
            fails("QEA_WRITE_REJECTED", request(basis, FileExportMode.UPDATE_COPY, selected(MODEL, List.of(addition), List.of(), List.of())));
            assertArrayEquals(basis, fixture.bytes());
        }
    }

    private static void replacingNameConstraint(QeaFixture fixture) throws Exception {
        fixture.sql("UPDATE t_object SET Name='Candidate' WHERE Object_ID=10");
        fixture.sql("UPDATE t_object SET Name='Protected' WHERE Object_ID=11");
        fixture.sql("ALTER TABLE t_object RENAME TO original_objects");
        fixture.sql("CREATE TABLE t_object (Object_ID INTEGER PRIMARY KEY, Object_Type TEXT, Name TEXT UNIQUE ON CONFLICT REPLACE, Note TEXT, Package_ID INTEGER, Stereotype TEXT, ea_guid TEXT, ParentID INTEGER, Classifier INTEGER, Classifier_guid TEXT, PDATA1 TEXT, PDATA3 TEXT, Alias TEXT)");
        fixture.sql("INSERT INTO t_object SELECT * FROM original_objects");
        fixture.sql("DROP TABLE original_objects");
    }

    private static Artifact renamed() {
        return new Artifact(A, ArtifactKind.ELEMENT, "Component", "Reviewed", "Reviewed text", Map.of(), Map.of("canonicalType", "Component"));
    }
    private static ExchangeDocument selected(String model, List<Artifact> artifacts, List<Relation> relations, List<Placement> placements) {
        return new ExchangeDocument("sparx-qea", "2", null, false, "Reviewed values", artifacts, relations, placements, Map.of("identifier", model), List.of());
    }
    private static BasedOutboundRequest request(byte[] basis, FileExportMode mode, ExchangeDocument selected) throws Exception {
        var context = new IntegrationContext(NAMESPACE, AuthorityMode.PUBLISH_TARGET, new ExternalScope("sparx", "project", null),
                new InternalState("repository", "workspace", "draft", null, 1, null, null), "reviewer", "sparx-qea", "2");
        return new BasedOutboundRequest(new OutboundRequest(context, selected, null), mode, NAMESPACE,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(basis)), "application/vnd.sparx.qea", basis);
    }
    private static void fails(String code, BasedOutboundRequest request) {
        var connector = assertInstanceOf(LifecycleIntegrationConnector.class, new SparxPlugin().extensions().stream()
                .filter(e -> e.id().equals("sparx-qea@2")).findFirst().orElse(null), "Version 2 writer must be discoverable");
        var failure = assertThrows(IllegalArgumentException.class, () -> connector.previewOutbound(request));
        assertTrue(failure.getMessage().startsWith(code + ":"), () -> "Expected " + code + ", received: " + failure.getMessage());
    }
}
