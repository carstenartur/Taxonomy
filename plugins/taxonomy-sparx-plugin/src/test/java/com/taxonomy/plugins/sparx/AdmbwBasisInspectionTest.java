package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.AdmbwQeaExport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdmbwBasisInspectionTest {
    @TempDir Path directory;
    private final AdmbwQeaExport connector = new SparxQeaWriterConnector();

    @Test void returnsOnlyQualifiedSourceObjectsWithTheirHierarchyAndOriginalIdentity() throws Exception {
        try (var fixture = basis()) {
            // Like the public MASC basis, a disconnected test root can have a non-EA placeholder key.
            fixture.sql("INSERT INTO t_package VALUES (3, 'Test root', 0, '{not-an-ea-guid}', '', 0)");
            fixture.sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid,Stereotype) VALUES (12,'Class','Same name',2,?,'Capability')", QeaFixture.C);
            fixture.sql("INSERT INTO t_xref (Name,Type,Client,Description) VALUES ('Stereotypes','element property',?,'@STEREO;Name=Capability;FQName=Other::Capability;@ENDSTEREO;')", QeaFixture.C);
            byte[] original = fixture.bytes();
            assertTrue(connector.supportsBasisInspection());
            var basis = connector.inspectAdmbwBasis(original);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture.bytes())), basis.sha256());
            assertEquals(QeaFixture.MODEL, basis.projectGuid());
            assertEquals(List.of(new AdmbwQeaExport.Root(QeaFixture.ROOT, "Root")), basis.roots());
            assertEquals(List.of(new AdmbwQeaExport.Reference(QeaFixture.A, "Capability", "Same name", "First note",
                            List.of("Root", "Child"), QeaFixture.ROOT, null),
                    new AdmbwQeaExport.Reference(QeaFixture.B, "OperationalRole", "Same name", "Second note",
                            List.of("Root", "Child"), QeaFixture.ROOT, QeaFixture.A)), basis.references());
            assertArrayEquals(fixture.bytes(), original);
            assertEquals(basis, connector.inspectAdmbwBasis(original));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Quelle", "Source"})
    void readsSourceTaggedServiceSpecificationsAsEvidenceWithoutAddingSelectableReferences(String property) throws Exception {
        try (var fixture = basis()) {
            fixture.sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid,Stereotype) VALUES (12,'Class','Records service',2,?,'ServiceSpecification')", QeaFixture.C);
            fixture.sql("INSERT INTO t_xref (Name,Type,Client,Description) VALUES ('Stereotypes','element property',?,'@STEREO;Name=ServiceSpecification;FQName=NAFv4-ADMBw::ServiceSpecification;@ENDSTEREO;')", QeaFixture.C);
            fixture.sql("INSERT INTO t_objectproperties (Object_ID,Property,Value) VALUES (12,?,'NATO C3 Taxonomy')", property);
            var result = connector.inspectAdmbwBasis(fixture.bytes());
            assertEquals(List.of(new AdmbwQeaExport.ServiceSpecification(QeaFixture.C, "Records service",
                    List.of("Root", "Child"), QeaFixture.ROOT, "NATO C3 Taxonomy")), result.serviceSpecifications());
            assertFalse(result.references().stream().anyMatch(reference -> reference.guid().equals(QeaFixture.C)));

            fixture.sql("UPDATE t_objectproperties SET Value='<memo>', Notes='NATO C3 Taxonomy' WHERE Object_ID=12");
            assertEquals(result.serviceSpecifications(), connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications());
            fixture.sql("UPDATE t_objectproperties SET Notes=? WHERE Object_ID=12", "x".repeat(QeaDatabase.MAX_CELL + 1));
            var oversized = assertThrows(QeaFailure.class, () -> connector.inspectAdmbwBasis(fixture.bytes()));
            assertTrue(oversized.getMessage().startsWith("QEA_LIMIT:"), oversized.getMessage());
            fixture.sql("UPDATE t_objectproperties SET Notes='NATO C3 Taxonomy' WHERE Object_ID=12");
            fixture.sql("INSERT INTO t_objectproperties (Object_ID,Property,Value) VALUES (12,'Source','Ambiguous alternative')");
            assertNull(connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().getFirst().source());
            fixture.sql("UPDATE t_xref SET Description=replace(Description,'NAFv4-ADMBw','Other') WHERE Client=?", QeaFixture.C);
            assertTrue(connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().isEmpty());
        }
    }

    @Test void onlyTheExactMemoSentinelWithTextInNotesOverridesTheInlineValue() throws Exception {
        try (var fixture = basis()) {
            fixture.sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid,Stereotype) VALUES (12,'Class','Records service',2,?,'ServiceSpecification')", QeaFixture.C);
            fixture.sql("INSERT INTO t_xref (Name,Type,Client,Description) VALUES ('Stereotypes','element property',?,'@STEREO;Name=ServiceSpecification;FQName=NAFv4-ADMBw::ServiceSpecification;@ENDSTEREO;')", QeaFixture.C);
            fixture.sql("DROP TABLE t_objectproperties");
            fixture.sql("CREATE TABLE t_objectproperties (PropertyID INTEGER PRIMARY KEY, Object_ID INTEGER, Property TEXT, Value TEXT COLLATE NOCASE, Notes TEXT, ea_guid TEXT)");
            fixture.sql("INSERT INTO t_objectproperties (Object_ID,Property,Value,Notes) VALUES (12,'Source','<MEMO>','Unrelated notes')");
            assertEquals("<MEMO>", connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().getFirst().source());
            fixture.sql("UPDATE t_objectproperties SET Value='<memo>', Notes=NULL");
            assertEquals("<memo>", connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().getFirst().source());
            fixture.sql("UPDATE t_objectproperties SET Notes=''");
            assertEquals("<memo>", connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().getFirst().source());
            fixture.sql("ALTER TABLE t_objectproperties DROP COLUMN Notes");
            assertEquals("<memo>", connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().getFirst().source());
            fixture.sql("UPDATE t_objectproperties SET Value='Inline source'");
            assertEquals("Inline source", connector.inspectAdmbwBasis(fixture.bytes()).serviceSpecifications().getFirst().source());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE usys_system SET value='{00000000-0000-0000-0000-000000000000}'",
            "INSERT INTO usys_system SELECT * FROM usys_system",
            "UPDATE t_package SET ea_guid='invalid' WHERE parent_id=0",
            "INSERT INTO t_package VALUES (3,'Duplicate',0,'{20000000-0000-0000-0000-000000000001}','',0)",
            "UPDATE t_package SET parent_id=2 WHERE package_id=2",
            "UPDATE t_object SET ea_guid='{00000000-0000-0000-0000-000000000000}' WHERE object_id=10",
            "INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid) SELECT 14, 'Class','Duplicate',2,ea_guid FROM t_object WHERE object_id=10",
            "UPDATE t_object SET parentid=11 WHERE object_id=11",
            "UPDATE t_object SET parentid=99 WHERE object_id=11",
            "UPDATE t_object SET parentid=11 WHERE object_id=10"
    })
    void rejectsAmbiguousOrUnusableIdentities(String mutation) throws Exception {
        try (var fixture = basis()) {
            fixture.sql(mutation);
            byte[] original = fixture.bytes();
            assertThrows(IllegalArgumentException.class, () -> connector.inspectAdmbwBasis(original));
            assertArrayEquals(fixture.bytes(), original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OperationalArchitecture", "CapabilityConfiguration", "ResourceRole"})
    void includesSupportedNativeRolesAndOmitsAQualifiedObjectWithTheWrongBaseType(String stereotype) throws Exception {
        try (var fixture = basis()) {
            fixture.sql("UPDATE t_object SET Stereotype=?, Object_Type=?, ParentID=? WHERE Object_ID=11",
                    stereotype, stereotype.equals("ResourceRole") ? "Part" : "Class", stereotype.equals("ResourceRole") ? 10 : 0);
            fixture.sql("UPDATE t_xref SET Description=replace(Description,'OperationalRole',?) WHERE Client=?", stereotype, QeaFixture.B);
            var result = connector.inspectAdmbwBasis(fixture.bytes());
            assertTrue(result.references().stream().anyMatch(r -> r.guid().equals(QeaFixture.B) && r.stereotype().equals(stereotype)));
            fixture.sql("UPDATE t_object SET Object_Type='Requirement' WHERE Object_ID=11");
            assertFalse(connector.inspectAdmbwBasis(fixture.bytes()).references().stream().anyMatch(r -> r.guid().equals(QeaFixture.B)));
        }
    }

    @Test void rejectsUnusableUpload() {
        assertThrows(IllegalArgumentException.class, () -> connector.inspectAdmbwBasis(new byte[1000]));
        assertThrows(IllegalArgumentException.class, () -> connector.inspectAdmbwBasis(new byte[AdmbwQeaExport.MAX_BASIS_BYTES + 1]));
    }

    private QeaFixture basis() throws Exception {
        var fixture = new QeaFixture(directory);
        fixture.sql("UPDATE t_object SET Object_Type='Class', Stereotype='Capability' WHERE Object_ID=10");
        fixture.sql("UPDATE t_object SET Object_Type='Part', Stereotype='OperationalRole', ParentID=10 WHERE Object_ID=11");
        for (String[] entry : List.of(new String[]{"Capability", QeaFixture.A}, new String[]{"OperationalRole", QeaFixture.B})) {
            String stereotype = entry[0];
            fixture.sql("INSERT INTO t_xref (XrefID,Name,Type,Client,Description) VALUES (?,'Stereotypes','element property',?,?)",
                    stereotype, entry[1], "@STEREO;Name=" + stereotype + ";FQName=NAFv4-ADMBw::" + stereotype + ";@ENDSTEREO;");
        }
        return fixture;
    }
}
