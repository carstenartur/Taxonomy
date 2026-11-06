package com.taxonomy.plugins.sparx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Hand-authored schema/values. These files have never been generated or accepted by Enterprise Architect. */
final class QeaFixture implements AutoCloseable {
    static final String MODEL = "{10000000-0000-0000-0000-000000000000}";
    static final String ROOT = "{20000000-0000-0000-0000-000000000001}";
    static final String CHILD = "{20000000-0000-0000-0000-000000000002}";
    static final String A = "{30000000-0000-0000-0000-000000000001}";
    static final String B = "{30000000-0000-0000-0000-000000000002}";
    static final String C = "{40000000-0000-0000-0000-000000000001}";
    static final String VIEW = "{50000000-0000-0000-0000-000000000001}";
    final Path file;
    private final Connection connection;

    QeaFixture(Path directory) throws SQLException, IOException {
        file = Files.createTempFile(directory, "synthetic-", ".qea");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        sql("CREATE TABLE usys_system (Property TEXT, Value TEXT)");
        sql("CREATE TABLE t_package (Package_ID INTEGER PRIMARY KEY, Name TEXT, Parent_ID INTEGER, ea_guid TEXT, Notes TEXT, TPos INTEGER)");
        sql("CREATE TABLE t_object (Object_ID INTEGER PRIMARY KEY, Object_Type TEXT, Name TEXT, Note TEXT, Package_ID INTEGER, Stereotype TEXT, ea_guid TEXT, ParentID INTEGER, Classifier INTEGER, Classifier_guid TEXT, PDATA1 TEXT, PDATA3 TEXT, Alias TEXT)");
        sql("CREATE TABLE t_connector (Connector_ID INTEGER PRIMARY KEY, Connector_Type TEXT, Start_Object_ID INTEGER, End_Object_ID INTEGER, ea_guid TEXT, Name TEXT, Notes TEXT, Stereotype TEXT, Direction TEXT, SourceIsAggregate INTEGER, DestIsAggregate INTEGER)");
        sql("CREATE TABLE t_diagram (Diagram_ID INTEGER PRIMARY KEY, Package_ID INTEGER, Name TEXT, Diagram_Type TEXT, ea_guid TEXT, Notes TEXT, StyleEx TEXT)");
        sql("CREATE TABLE t_diagramobjects (Diagram_ID INTEGER, Object_ID INTEGER, RectTop INTEGER, RectLeft INTEGER, RectRight INTEGER, RectBottom INTEGER, Sequence INTEGER, ObjectStyle TEXT)");
        sql("CREATE TABLE t_objectproperties (PropertyID INTEGER PRIMARY KEY, Object_ID INTEGER, Property TEXT, Value TEXT, Notes TEXT, ea_guid TEXT)");
        sql("CREATE TABLE t_connectortag (PropertyID INTEGER PRIMARY KEY, ElementID INTEGER, Property TEXT, VALUE TEXT, NOTES TEXT, ea_guid TEXT)");
        sql("CREATE TABLE t_xref (XrefID TEXT, Name TEXT, Type TEXT, Client TEXT, Supplier TEXT, Behavior TEXT, Description TEXT)");
        sql("INSERT INTO usys_system VALUES ('ProjectGUID', ?)", MODEL);
        sql("INSERT INTO t_package VALUES (1, 'Root', 0, ?, 'Root note', 0)", ROOT);
        sql("INSERT INTO t_package VALUES (2, 'Child', 1, ?, 'Child note', 0)", CHILD);
        sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Note,Package_ID,Stereotype,ea_guid,ParentID,Alias) VALUES (10,'Component','Same name','First note',2,'',?,0,'source alias')", A);
        sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Note,Package_ID,Stereotype,ea_guid,ParentID) VALUES (11,'Component','Same name','Second note',2,'',?,0)", B);
        // EA also represents a package in t_object. It is not a second architecture element.
        sql("INSERT INTO t_object (Object_ID,Object_Type,Name,Package_ID,Stereotype,ea_guid,ParentID,PDATA1) VALUES (100,'Package','Child',1,'',?,0,'2')", CHILD);
        sql("INSERT INTO t_connector VALUES (20,'Dependency',10,11,?,'depends','Connector note','','Source -> Destination',0,0)", C);
        sql("INSERT INTO t_diagram VALUES (30,2,'Overview','Logical',?,'Diagram note','style')", VIEW);
        sql("INSERT INTO t_diagramobjects VALUES (30,10,-20,10,170,-100,0,'font')");
        sql("INSERT INTO t_diagramobjects VALUES (30,11,-20,200,360,-100,1,'')");
    }

    void sql(String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.execute();
        }
    }

    byte[] bytes() throws IOException { return Files.readAllBytes(file); }
    @Override public void close() throws SQLException { connection.close(); }
}
