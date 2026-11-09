package com.taxonomy.plugins.sparx;

import com.taxonomy.extension.api.integration.AdmbwQeaExport.Connector;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Delivery;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.Element;
import com.taxonomy.extension.api.integration.AdmbwQeaExport.View;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void requiresCatalogueGuidAndQualifiedStereotypeInTheBasis() throws Exception {
        try (var basis = basis()) {
            var connector = new SparxQeaWriterConnector();
            basis.sql("UPDATE t_xref SET Description = replace(Description, 'NAFv4-ADMBw::', 'Other::') WHERE Client = ?", QeaFixture.A);
            byte[] original = basis.bytes();
            assertTrue(assertThrows(IllegalArgumentException.class, () ->
                    connector.exportAdmbw(delivery(), QeaFixture.ROOT, original)).getMessage().contains("QEA_ADMBW_CATALOGUE"));
            assertArrayEquals(original, basis.bytes());
        }
    }

    @Test
    void rejectsAddingTheSameDiagramsToAnAlreadyDeliveredCopy() throws Exception {
        try (var basis = basis()) {
            var connector = new SparxQeaWriterConnector();
            var file = connector.exportAdmbw(delivery(), QeaFixture.ROOT, basis.bytes());
            assertTrue(assertThrows(IllegalArgumentException.class, () ->
                    connector.exportAdmbw(delivery(), QeaFixture.ROOT, file.content())).getMessage().contains("QEA_ADMBW_DIAGRAM_EXISTS"));
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
        return new Delivery("WARN", COMMIT, List.of(
                new View("C1-S1", "C1-S1 : WARN : Capability to Service Mapping",
                        List.of("Service Specifications", "C1-S1", "Einordnung Fähigkeitslage"),
                        List.of(new Element("cap", "Capability", "Local name", "", QeaFixture.A, null, null),
                                new Element("service", "ServiceSpecification", "Warndienst", "Beschreibung", null, null, null)),
                        List.of(new Connector("Exhibits", "service", "cap"))),
                new View("R7", "R7 : WARN : Requirements Derivation", List.of("Requirements", "R7", "Requirement Derivation"),
                        List.of(new Element("req", "FunctionalRequirement", "Warnen", "Muss warnen.", null, 7, HASH),
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
