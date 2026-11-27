package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.AdmbwQeaExport.Connector;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Delivery;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Element;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdmbwQeaExportTest {
    private static final String COMMIT = "ab".repeat(20);
    private static final String HASH = "12".repeat(32);

    @TempDir Path temp;

    @Test
    void nativeWriterConsumesTheSharedSourceDescriptor() {
        assertSame(com.taxonomy.sparx.mapping.AdmbwSourceProfile.CURRENT, AdmbwQeaProfile.SOURCE_PROFILE);
        assertSame(AdmbwQeaProfile.SOURCE_PROFILE.elementTypes(), AdmbwQeaProfile.ELEMENT_TYPES);
        assertSame(AdmbwQeaProfile.SOURCE_PROFILE.diagramTypes(), AdmbwQeaProfile.DIAGRAM_TYPES);
    }

    @Test
    void keepsTheCompleteDeliveryCoverageInsideBothNativeDiagrams() throws Exception {
        try (var basis = basis()) {
            var original = delivery();
            String notes = "Delivery coverage: PARTIAL\nElements: 5/31 represented; 26 omitted\n"
                    + "Omitted: element:training-process / UNSUPPORTED_ELEMENT_TYPE\n"
                    + "Source analysis: training / saved-training / PARTIAL\n"
                    + "R7 scope: all checkpoint requirements\nFachliche Prüfung erforderlich.";
            var scoped = new Delivery(original.projectCode(), original.commit(), original.views(), notes);
            var connector = new SparxQeaWriterConnector();
            assertTrue(connector.supportsCoverageNotes());
            var file = connector.exportAdmbw(scoped, QeaFixture.ROOT, basis.bytes());
            Path target = Files.write(temp.resolve("coverage.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                var diagrams = column(read, "select Notes from t_diagram where Diagram_ID > 30");
                assertEquals(2, diagrams.size());
                for (String diagram : diagrams) assertTrue(diagram.contains(notes), diagram);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"FunctionalRequirement, short", "NonfunctionalRequirement, long", "FunctionalRequirement, sentinel"})
    void exportsExactInheritedRequirementTextAndLeavesPlanningValuesUnknown(String stereotype, String size) throws Exception {
        String text = switch (size) {
            case "long" -> "Nächster Absatz.\r\n".repeat(25);
            case "sentinel" -> "<memo>";
            default -> "Freigegebene Texte lesen.";
        };
        String hash = QeaDatabase.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var basis = basis()) {
            basis.sql("INSERT INTO t_objectproperties(PropertyID,Object_ID,Property,Value) VALUES(9100,10,'QS_Status','preserved')");
            byte[] original = basis.bytes();
            var req = new Element("req", stereotype, "Anforderung", text, null, 7, hash);
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(req), QeaFixture.ROOT, original);
            assertArrayEquals(original, basis.bytes());
            Path target = Files.write(temp.resolve("inherited-tags.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("preserved"), column(read,
                        "select Value from t_objectproperties where PropertyID=9100 and Object_ID=10"));
                try (var statement = read.prepareStatement("""
                        select o.Note,p.Value,p.Notes from t_object o join t_objectproperties p on o.Object_ID=p.Object_ID
                        where o.Stereotype=? and p.Property='Text'
                        """)) {
                    statement.setString(1, stereotype);
                    try (var rows = statement.executeQuery()) {
                        assertTrue(rows.next(), "The inherited BWRequirement.Text tag must exist");
                        assertEquals(text, rows.getString("Note"));
                        boolean memo = !"short".equals(size);
                        assertEquals(memo ? "<memo>" : text, rows.getString("Value"));
                        if (memo) assertEquals(text, rows.getString("Notes"));
                        assertFalse(rows.next());
                    }
                }
                assertEquals(List.of("Anforderungsart","Kritikalität","Operative Bewertung","Priorität Vergabe","QS_Status"),
                        column(read, """
                                select p.Property from t_objectproperties p join t_object o on p.Object_ID=o.Object_ID
                                where o.Object_Type='Requirement' and p.Value is null and p.Notes is null
                                order by p.Property
                                """));
                assertEquals(List.of(hash), column(read,
                        "select Value from t_objectproperties where Property='Taxonomy.RequirementContentSha256'"));
            }
        }
    }

    @Test
    void exportsExplicitEvaluationsIncludingMemoAndLeavesUnansweredFieldsNull() throws Exception {
        String assessment = "Zur fachlichen Prüfung. ".repeat(20);
        var req = new Element("req", "FunctionalRequirement", "Lesesaal", "Unterlagen bereitstellen.", null, 7, HASH,
                java.util.Map.of("Kritikalität", "Fachlich geprüft", "Operative Bewertung", assessment,
                        "QS_Status", "<memo>"));
        try (var basis = basis()) {
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(req), QeaFixture.ROOT, basis.bytes());
            Path target = Files.write(temp.resolve("assessed-tags.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("Anforderungsart", "Kritikalität", "Operative Bewertung", "Priorität Vergabe", "QS_Status"), column(read,
                        "select Property from t_objectproperties where Property in ('Anforderungsart','Kritikalität','Operative Bewertung','Priorität Vergabe','QS_Status') order by PropertyID"));
                assertEquals(List.of("Fachlich geprüft"), column(read, "select Value from t_objectproperties where Property='Kritikalität'"));
                assertEquals(List.of(assessment), column(read, "select Notes from t_objectproperties where Property='Operative Bewertung' and Value='<memo>'"));
                assertEquals(List.of("<memo>"), column(read, "select Notes from t_objectproperties where Property='QS_Status' and Value='<memo>'"));
                assertEquals(List.of("Anforderungsart", "Priorität Vergabe"), column(read,
                        "select Property from t_objectproperties where Value is null and Notes is null order by Property"));
            }
        }
    }

    @Test
    void newPackagesLeaveUnselectedNativePackageReferencesUnresolved() throws Exception {
        try (var basis = basis()) {
            basis.sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid) VALUES (110,'Class','Orphan',4,?)",
                    "{30000000-0000-0000-0000-000000000110}");
            basis.sql("UPDATE t_diagram SET Package_ID=5 WHERE Diagram_ID=30");
            basis.sql("INSERT INTO t_package (Package_ID,Name,Parent_ID,ea_guid) VALUES (3,'Orphan branch',6,'{20000000-0000-0000-0000-000000000003}')");
            basis.sql("UPDATE t_object SET PDATA1='7' WHERE Object_ID=100");
            basis.sql("UPDATE t_object SET PDATA1='8' WHERE Object_ID=110");
            basis.sql("UPDATE t_object SET Note='Reference' WHERE Object_ID=11");
            byte[] original = basis.bytes();
            var connector = new SparxQeaWriterConnector();
            assertFalse(connector.inspectAdmbwBasis(original).roots().isEmpty());
            var file = connector.exportAdmbw(delivery(), QeaFixture.ROOT, original);
            Path target = Files.write(temp.resolve("package-owners.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of(), column(read, "SELECT Name FROM t_package WHERE Package_ID BETWEEN 4 AND 7"));
                assertEquals(List.of("8"), column(read, "SELECT Package_ID FROM t_package WHERE Name='Service Specifications'"));
                assertEquals(List.of("4"), column(read, "SELECT Package_ID FROM t_object WHERE Object_ID=110"));
            }
            assertArrayEquals(original, basis.bytes());
        }
    }

    @Test
    void createsNativeViewsAndKeepsCatalogueObjectsInTheirPackages() throws Exception {
        try (var basis = basis()) {
            byte[] original = basis.bytes();
            var connector = new SparxQeaWriterConnector();
            var file = connector.exportAdmbw(delivery(), QeaFixture.ROOT, original);
            assertEquals("application/vnd.sparx.qea", file.mediaType());
            assertEquals("WARN-admbw.qea", file.filename());
            assertArrayEquals(original, basis.bytes());
            assertArrayEquals(file.content(), connector.exportAdmbw(delivery(), QeaFixture.ROOT, original).content());

            Path target = Files.write(temp.resolve("delivery.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("ok"), column(read, "pragma quick_check"));
                for (String notes : column(read, "select Notes from t_diagram where Diagram_ID > 30")) {
                    assertTrue(notes.contains("6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93"));
                    assertTrue(notes.contains("e2e8c69281dcc83ad4382bd1b196c23850a847defb33d0a0ffcb429d747f3643"));
                    assertTrue(notes.contains("taxonomy-admbw-qea/1"));
                    assertTrue(notes.contains("Basis SHA-256 " + QeaDatabase.digest(original)));
                }
                assertEquals(List.of("90000"), column(read, "select length(Description) from t_xref where XrefID='opaque'"));
                assertEquals(List.of("90000"), column(read, "select length(Note) from t_object where Object_ID=11"));
                assertEquals(List.of("90000"), column(read, "select length(Notes) from t_objectproperties where Property='Opaque'"));
                assertEquals(List.of("106"), column(read, "select Object_ID from t_objectproperties where Property='Orphan'"));
                assertEquals(List.of("0"), column(read, "select count(*) from t_object where Object_ID=106"));
                assertEquals(List.of(
                        "Custom|MDGDgm=NAFv4-ADMBw Service Specification Viewpoints::C1-S1 - Capability to Service Mapping;",
                        "Custom|MDGDgm=NAFv4-ADMBw Requirement Viewpoints::R7 - Requirement Derivation;"),
                        column(read, "select Diagram_Type || '|' || StyleEx from t_diagram where Diagram_ID > 30 order by Diagram_ID"));
                assertEquals(List.of("Exhibits|Abstraction|ServiceSpecification|Capability|Source -> Destination",
                        "DerivedFrom|Dependency|FunctionalRequirement|OperationalConstraint|Source -> Destination",
                        "Satisfy|Dependency|OperationalRole|OperationalConstraint|Source -> Destination",
                        "ToBeRealizedBy|Dependency|FunctionalRequirement|CapabilityConfiguration|Source -> Destination"),
                        column(read, """
                                select c.Stereotype || '|' || c.Connector_Type || '|' || s.Stereotype || '|' || t.Stereotype || '|' || c.Direction
                                from t_connector c join t_object s on c.Start_Object_ID=s.Object_ID
                                join t_object t on c.End_Object_ID=t.Object_ID where c.Connector_ID > 20 order by c.Connector_ID
                                """));
                assertEquals(List.of("Class|ServiceSpecification", "Requirement|FunctionalRequirement",
                                "Class|OperationalConstraint", "Class|CapabilityConfiguration"),
                        column(read, "select Object_Type || '|' || Stereotype from t_object where Object_ID > 100 and Object_Type <> 'Package' order by Object_ID"));
                assertEquals(List.of("10|2|Official capability", "11|2|Official role"),
                        column(read, "select Object_ID || '|' || Package_ID || '|' || Name from t_object where Object_ID in (10,11) order by Object_ID"));
                assertEquals(List.of("2", "4"), column(read, """
                        select count(*) from t_diagramobjects where Diagram_ID > 30 group by Diagram_ID order by Diagram_ID
                        """));
                assertEquals(List.of("1", "3"), column(read, """
                        select count(*) from t_diagramlinks where Hidden=0 group by DiagramID order by DiagramID
                        """));
                assertEquals(List.of("Einordnung Fähigkeitslage", "Requirement Derivation"), column(read, """
                        select p.Name from t_package p join t_diagram d on p.Package_ID=d.Package_ID
                        where d.Diagram_ID > 30 order by d.Diagram_ID
                        """));
                assertEquals(List.of("7", HASH), column(read, """
                        select Value from t_objectproperties where Property in ('Taxonomy.RequirementVersion','Taxonomy.RequirementContentSha256')
                        order by PropertyID
                        """));
                assertEquals(List.of("4"), column(read, """
                        select count(*) from t_xref x join t_connector c on x.Client=c.ea_guid where c.Connector_ID > 20
                        and x.Type='connector property' and x.Description like '%FQName=NAFv4-ADMBw::%;@ENDSTEREO;'
                        """));
            }
        }
    }

    @Test
    void preflightsTheNativeDeliveryOnADisposableCopy() throws Exception {
        try (var basis = basis()) {
            byte[] supplied = basis.bytes();
            var connector = new SparxQeaWriterConnector();
            assertTrue(connector.supportsDeliveryPreflight());
            connector.preflightAdmbw(delivery(), QeaFixture.ROOT, supplied);
            assertArrayEquals(basis.bytes(), supplied);
        }
    }

    @Test
    void preflightAndExportRequireAModelRoot() throws Exception {
        try (var basis = basis()) {
            assertRejectedByPreflightAndExport(QeaFixture.CHILD, basis.bytes(), "QEA_ADMBW_ROOT");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "UPDATE t_object SET ParentID=Object_ID WHERE Object_ID=11 | QEA_ADMBW_PART_OWNER",
            "INSERT INTO t_package(Name,Parent_ID,ea_guid) VALUES ('Requirements',1,'{20000000-0000-0000-0000-000000000003}'),"
                    + " ('Requirements',1,'{20000000-0000-0000-0000-000000000004}') | QEA_ADMBW_PACKAGE",
            "ALTER TABLE t_diagramlinks DROP COLUMN Geometry | QEA_SCHEMA_UNSUPPORTED",
            "UPDATE t_diagram SET Diagram_ID=2147483647 | QEA_LIMIT"
    })
    void preflightAndExportRejectTheSameNativeConstraints(String sql, String reason) throws Exception {
        try (var basis = basis()) {
            basis.sql(sql);
            assertRejectedByPreflightAndExport(QeaFixture.ROOT, basis.bytes(), reason);
        }
    }

    @Test
    void requiresCatalogueGuidAndQualifiedStereotypeInTheBasis() throws Exception {
        try (var basis = basis()) {
            basis.sql("UPDATE t_xref SET Description = replace(Description, 'NAFv4-ADMBw::', 'Other::') WHERE Client = ?", QeaFixture.A);
            byte[] original = basis.bytes();
            assertRejectedByPreflightAndExport(QeaFixture.ROOT, original, "QEA_ADMBW_CATALOGUE");
            assertArrayEquals(original, basis.bytes());
        }
    }

    @Test
    void rejectsAddingTheSameDiagramsToAnAlreadyDeliveredCopy() throws Exception {
        try (var basis = basis()) {
            var connector = new SparxQeaWriterConnector();
            var file = connector.exportAdmbw(delivery(), QeaFixture.ROOT, basis.bytes());
            assertRejectedByPreflightAndExport(QeaFixture.ROOT, file.content(), "QEA_ADMBW_DIAGRAM_EXISTS");
        }
    }

    @Test
    void reservesObjectIdsReferencedByOrphanDiagramPlacements() throws Exception {
        try (var basis = basis()) {
            basis.sql("""
                    INSERT INTO t_diagramobjects
                    (Diagram_ID,Object_ID,RectTop,RectLeft,RectRight,RectBottom,Sequence,ObjectStyle,Instance_ID)
                    VALUES (30,101,-20,400,560,-100,2,'',12)
                    """);
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(), QeaFixture.ROOT, basis.bytes());
            Path target = Files.write(temp.resolve("orphan-placement.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("0"), column(read, "select count(*) from t_object where Object_ID=101"));
                assertEquals(List.of("101"), column(read, "select Object_ID from t_diagramobjects where Diagram_ID=30 and Object_ID=101"));
            }
        }
    }

    @Test
    void reservesDiagramIdsReferencedByOrphanDiagramPlacementsAndLinks() throws Exception {
        try (var basis = basis()) {
            basis.sql("""
                    INSERT INTO t_diagramobjects
                    (Diagram_ID,Object_ID,RectTop,RectLeft,RectRight,RectBottom,Sequence,ObjectStyle,Instance_ID)
                    VALUES (31,10,-20,400,560,-100,2,'old placement',12)
                    """);
            basis.sql("""
                    INSERT INTO t_diagramlinks (DiagramID,ConnectorID,Geometry,Style,Hidden,Instance_ID)
                    VALUES (32,20,'old geometry','old link',1,13)
                    """);
            byte[] original = basis.bytes();
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(), QeaFixture.ROOT, original);
            assertArrayEquals(original, basis.bytes());
            Path target = Files.write(temp.resolve("orphan-diagrams.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("31|10|-20|400|560|-100|2|old placement"), column(read, """
                        select Diagram_ID || '|' || Object_ID || '|' || RectTop || '|' || RectLeft || '|'
                        || RectRight || '|' || RectBottom || '|' || Sequence || '|' || ObjectStyle
                        from t_diagramobjects where Instance_ID=12
                        """));
                assertEquals(List.of("32|20|old geometry|old link|1"), column(read, """
                        select DiagramID || '|' || ConnectorID || '|' || Geometry || '|' || Style || '|' || Hidden
                        from t_diagramlinks where Instance_ID=13
                        """));
                assertEquals(List.of(), column(read, "select Diagram_ID from t_diagram where Diagram_ID in (31,32) order by Diagram_ID"));
            }
        }
    }

    @Test
    void reservesConnectorIdsReferencedByOrphanConnectorTags() throws Exception {
        try (var basis = basis()) {
            basis.sql("INSERT INTO t_connectortag (PropertyID,ElementID,Property,Value,Notes) VALUES (10,21,'Orphan','unrelated','')");
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(), QeaFixture.ROOT, basis.bytes());
            Path target = Files.write(temp.resolve("orphan-connector-tag.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("0"), column(read, "select count(*) from t_connector where Connector_ID=21"));
                assertEquals(List.of("21|unrelated"), column(read,
                        "select ElementID || '|' || Value from t_connectortag where Property='Orphan'"));
            }
        }
    }

    @Test
    void reservesConnectorIdsReferencedByOrphanDiagramLinks() throws Exception {
        try (var basis = basis()) {
            basis.sql("""
                    INSERT INTO t_diagramlinks (DiagramID,ConnectorID,Geometry,Style,Hidden,Instance_ID)
                    VALUES (30,21,'old geometry','old link',1,13)
                    """);
            byte[] original = basis.bytes();
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(), QeaFixture.ROOT, original);
            assertArrayEquals(original, basis.bytes());
            Path target = Files.write(temp.resolve("orphan-link-connector.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("30|21|old geometry|old link|1"), column(read, """
                        select DiagramID || '|' || ConnectorID || '|' || Geometry || '|' || Style || '|' || Hidden
                        from t_diagramlinks where Instance_ID=13
                        """));
                assertEquals(List.of(), column(read, "select Connector_ID from t_connector where Connector_ID=21"));
            }
        }
    }

    @Test
    void preservesExistingDiagramReferencesAndTagsBeyondTheNativeReadRowBudget() throws Exception {
        try (var basis = basis()) {
            basis.sql("""
                    WITH RECURSIVE entries(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM entries WHERE n<150)
                    INSERT INTO t_diagram (Diagram_ID,Package_ID,Name,ea_guid)
                    SELECT 1000+n,2,'Existing diagram '||n,printf('{50000000-0000-0000-0000-%012d}',1000+n) FROM entries
                    """);
            basis.sql("""
                    WITH RECURSIVE entries(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM entries WHERE n<150)
                    INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid)
                    SELECT 1000+n,'Class','Existing object '||n,2,printf('{30000000-0000-0000-0000-%012d}',1000+n) FROM entries
                    """);
            basis.sql("""
                    INSERT INTO t_diagramobjects (Diagram_ID,Object_ID,Instance_ID)
                    SELECT d.Diagram_ID,o.Object_ID,ROW_NUMBER() OVER (ORDER BY d.Diagram_ID,o.Object_ID)
                    FROM t_diagram d CROSS JOIN t_object o WHERE d.Diagram_ID>1000 AND o.Object_ID>1000
                    """);
            basis.sql("""
                    INSERT INTO t_connector (Connector_ID,Connector_Type,Start_Object_ID,End_Object_ID,ea_guid)
                    SELECT Object_ID,'Dependency',10,Object_ID,printf('{40000000-0000-0000-0000-%012d}',Object_ID)
                    FROM t_object WHERE Object_ID>1000
                    """);
            basis.sql("""
                    INSERT INTO t_diagramlinks (DiagramID,ConnectorID,Hidden,Instance_ID)
                    SELECT d.Diagram_ID,c.Connector_ID,1,ROW_NUMBER() OVER (ORDER BY d.Diagram_ID,c.Connector_ID)
                    FROM t_diagram d CROSS JOIN t_connector c WHERE d.Diagram_ID>1000 AND c.Connector_ID>1000
                    """);
            basis.sql("""
                    WITH RECURSIVE entries(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM entries WHERE n<?)
                    INSERT INTO t_connectortag (PropertyID,ElementID,Property,Value)
                    SELECT n,20,'Existing tag '||n,'Original value' FROM entries
                    """, QeaDatabase.MAX_ROWS + 1);
            byte[] original = basis.bytes();
            var file = new SparxQeaWriterConnector().exportAdmbw(delivery(), QeaFixture.ROOT, original);
            assertArrayEquals(original, basis.bytes());
            Path target = Files.write(temp.resolve("delivery-with-existing-references.qea"), file.content());
            try (var read = DriverManager.getConnection("jdbc:sqlite:" + target)) {
                assertEquals(List.of("22502"), column(read, "select count(*) from t_diagramobjects where Diagram_ID<=1150"));
                assertEquals(List.of("6"), column(read, "select count(*) from t_diagramobjects where Diagram_ID>1150"));
                assertEquals(List.of("22500"), column(read, "select count(*) from t_diagramlinks where DiagramID<=1150 and Hidden=1"));
                assertEquals(List.of("4"), column(read, "select count(*) from t_diagramlinks where DiagramID>1150 and Hidden=0"));
                assertEquals(List.of(Integer.toString(QeaDatabase.MAX_ROWS + 1)), column(read,
                        "select count(*) from t_connectortag where ElementID=20 and Value='Original value'"));
            }
        }
    }

    private static void assertRejectedByPreflightAndExport(String root, byte[] basis, String reason) {
        var connector = new SparxQeaWriterConnector();
        var delivery = delivery();
        byte[] original = basis.clone();
        var export = assertThrows(IllegalArgumentException.class, () -> connector.exportAdmbw(delivery, root, basis));
        assertTrue(export.getMessage().startsWith(reason + ":"), export::getMessage);
        var preflight = assertThrows(IllegalArgumentException.class, () -> connector.preflightAdmbw(delivery, root, basis));
        assertEquals(export.getMessage(), preflight.getMessage());
        assertArrayEquals(original, basis);
    }

    private QeaFixture basis() throws Exception {
        var fixture = new QeaFixture(temp);
        fixture.sql("ALTER TABLE t_xref ADD COLUMN Visibility TEXT");
        fixture.sql("ALTER TABLE t_diagramobjects ADD COLUMN Instance_ID INTEGER");
        fixture.sql("CREATE TABLE t_diagramlinks (DiagramID INTEGER, ConnectorID INTEGER, Geometry TEXT, Style TEXT, Hidden INTEGER, Instance_ID INTEGER)");
        fixture.sql("UPDATE t_object SET Object_Type='Class', Name='Official capability', Stereotype='Capability' WHERE Object_ID=10");
        fixture.sql("UPDATE t_object SET Object_Type='Part', Name='Official role', Stereotype='OperationalRole', ParentID=12, Note=? WHERE Object_ID=11",
                "x".repeat(90_000));
        fixture.sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,ea_guid,ParentID) VALUES (12,'Class','Owner',2,'{30000000-0000-0000-0000-000000000003}',0)");
        fixture.sql("INSERT INTO t_xref (XrefID,Name,Type,Client,Description) VALUES ('x1','Stereotypes','element property',?,?)",
                QeaFixture.A, stereo("Capability"));
        fixture.sql("INSERT INTO t_xref (XrefID,Name,Type,Client,Description) VALUES ('x2','Stereotypes','element property',?,?)",
                QeaFixture.B, stereo("OperationalRole"));
        fixture.sql("INSERT INTO t_xref (XrefID,Name,Type,Client,Description) VALUES ('opaque','CustomProperties','element property',?,?)",
                QeaFixture.B, "x".repeat(90_000));
        fixture.sql("INSERT INTO t_objectproperties (PropertyID,Object_ID,Property,Value,Notes) VALUES (1,11,'Opaque','<memo>',?)", "x".repeat(90_000));
        fixture.sql("INSERT INTO t_objectproperties (PropertyID,Object_ID,Property,Value) VALUES (2,106,'Orphan','old')");
        return fixture;
    }

    private static String stereo(String type) {
        return "@STEREO;Name=" + type + ";FQName=NAFv4-ADMBw::" + type + ";@ENDSTEREO;";
    }

    private static Delivery delivery() {
        return delivery(new Element("req", "FunctionalRequirement", "Warnen", "Muss warnen.", null, 7, HASH));
    }

    private static Delivery delivery(Element requirement) {
        return new Delivery("WARN", COMMIT, List.of(
                new View("C1-S1", "C1-S1 : WARN : Capability to Service Mapping",
                        List.of("Service Specifications", "C1-S1", "Einordnung Fähigkeitslage"),
                        List.of(new Element("cap", "Capability", "Local name", "", QeaFixture.A, null, null),
                                new Element("service", "ServiceSpecification", "Warndienst", "Beschreibung", null, null, null)),
                        List.of(new Connector("Exhibits", "service", "cap"))),
                new View("R7", "R7 : WARN : Requirements Derivation", List.of("Requirements", "R7", "Requirement Derivation"),
                        List.of(requirement,
                                new Element("constraint", "OperationalConstraint", "Randbedingung", "", null, null, null),
                                new Element("subject", "OperationalRole", "Local role", "", QeaFixture.B, null, null),
                                new Element("realizer", "CapabilityConfiguration", "WARN", "", null, null, null)),
                        List.of(new Connector("DerivedFrom", "req", "constraint"), new Connector("Satisfy", "subject", "constraint"),
                                new Connector("ToBeRealizedBy", "req", "realizer")))));
    }

    private static List<String> column(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (rows.next()) values.add(rows.getString(1));
            return values;
        }
    }
}
