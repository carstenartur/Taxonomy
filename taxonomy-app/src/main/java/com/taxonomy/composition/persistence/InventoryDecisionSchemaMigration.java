package com.taxonomy.composition.persistence;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Backfills technical inventory coordination before Hibernate can add mandatory columns. */
public final class InventoryDecisionSchemaMigration {
    private InventoryDecisionSchemaMigration() { }
    public static void migrate(DataSource database) {
        Flyway.configure().dataSource(database).table("inventory_decision_schema_history")
            .locations("classpath:db/inventory-decision-java-migrations")
            .baselineOnMigrate(true).baselineVersion("0")
            .javaMigrations(new V1__RetainedInventoryDecisions()).load().migrate();
    }
    public static final class V1__RetainedInventoryDecisions extends BaseJavaMigration {
        @Override public void migrate(Context context) throws Exception {
            var c=context.getConnection();String vendor=c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            if(!Set.of("postgresql","microsoft sql server","oracle","hsql database engine").contains(vendor))throw new SQLException("Unsupported inventory decision database");
            boolean oracle=vendor.equals("oracle"),mssql=vendor.equals("microsoft sql server");
            String comparison=table(c,"inventory_comparison");
            if(comparison!=null) {
                var columns=columns(c,comparison);
                if(!columns.containsKey("row_version"))execute(c,"alter table inventory_comparison add row_version "+(oracle?"number(19)":"bigint")+" default 0");
                if(!columns.containsKey("scope_guard_key"))execute(c,"alter table inventory_comparison add scope_guard_key varchar(240)");
                execute(c,"update inventory_comparison set row_version=0 where row_version is null");
                // Prepared updates avoid dialect-specific concatenation and preserve immutable business JSON.
                var ids=new ArrayList<String>();
                try(var statement=c.createStatement();var rows=statement.executeQuery("select id from inventory_comparison where scope_guard_key is null")){while(rows.next())ids.add(rows.getString(1));}
                if(!ids.isEmpty())try(var statement=c.prepareStatement("update inventory_comparison set scope_guard_key=? where id=? and scope_guard_key is null")) {
                    for(String id:ids){statement.setString(1,"comparison:"+id);statement.setString(2,id);statement.addBatch();}statement.executeBatch();
                }
                if(oracle) {
                    execute(c,"alter table inventory_comparison modify (row_version default 0)");
                    if(columns(c,comparison).get("row_version").nullable())execute(c,"alter table inventory_comparison modify (row_version not null)");
                    if(columns(c,comparison).get("scope_guard_key").nullable())execute(c,"alter table inventory_comparison modify (scope_guard_key not null)");
                } else if(mssql) {
                    execute(c,"alter table inventory_comparison alter column row_version bigint not null");
                    if(columns(c,comparison).get("row_version").defaultValue()==null)execute(c,"alter table inventory_comparison add default 0 for row_version");
                    execute(c,"alter table inventory_comparison alter column scope_guard_key varchar(240) not null");
                } else {
                    execute(c,"alter table inventory_comparison alter column row_version set default 0");
                    execute(c,"alter table inventory_comparison alter column row_version set not null");
                    execute(c,"alter table inventory_comparison alter column scope_guard_key set not null");
                }
                if(!uniqueGuard(c,comparison))execute(c,"alter table inventory_comparison add constraint uq_inventory_scope_guard unique(scope_guard_key)");
            }
            String solution=table(c,"project_solution");
            if(solution!=null) {
                var columns=columns(c,solution);
                for(var column:Map.of("inventory_comparison_id",36,"inventory_fingerprint",128,"inventory_hard_exclusions",4000).entrySet())
                    if(!columns.containsKey(column.getKey()))execute(c,"alter table project_solution add "+column.getKey()+" varchar("+column.getValue()+")");
            }
        }
        private static void execute(Connection c,String sql)throws SQLException{try(var s=c.createStatement()){s.execute(sql);}}
        private static String table(Connection c,String name)throws SQLException {
            try(var rows=c.getMetaData().getTables(c.getCatalog(),c.getSchema(),"%",new String[]{"TABLE"})){while(rows.next())if(name.equalsIgnoreCase(rows.getString("TABLE_NAME")))return rows.getString("TABLE_NAME");}return null;
        }
        private record Column(boolean nullable,String defaultValue) { }
        private static Map<String,Column> columns(Connection c,String table)throws SQLException {
            var result=new HashMap<String,Column>();try(var rows=c.getMetaData().getColumns(c.getCatalog(),c.getSchema(),table,"%")){while(rows.next())result.put(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),new Column(rows.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,rows.getString("COLUMN_DEF")));}return result;
        }
        private static boolean uniqueGuard(Connection c,String table)throws SQLException {
            var indexes=new HashMap<String,List<String>>();try(var rows=c.getMetaData().getIndexInfo(c.getCatalog(),c.getSchema(),table,true,false)){while(rows.next()){String name=rows.getString("INDEX_NAME"),column=rows.getString("COLUMN_NAME");if(name!=null&&column!=null)indexes.computeIfAbsent(name,k->new ArrayList<>()).add(column.toLowerCase(Locale.ROOT));}}
            return indexes.values().stream().anyMatch(columns->columns.equals(List.of("scope_guard_key")));
        }
    }
}
