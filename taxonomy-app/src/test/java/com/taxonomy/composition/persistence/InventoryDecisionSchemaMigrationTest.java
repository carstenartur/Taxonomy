package com.taxonomy.composition.persistence;

import com.taxonomy.interop.inventory.InventoryComparisonEntity;
import com.taxonomy.shared.config.PortableSchemaInitializationConfiguration;
import org.hibernate.SessionFactory;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InventoryDecisionSchemaMigrationTest {
    @Test void flywayDisabledInitializerBackfillsLegacyRowsBeforeHibernateUpdate() throws Exception {
        var database=database();var jdbc=legacy(database);
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("compatibility",Map.of("spring.flyway.enabled","false","taxonomy.schema-migration.enabled","true")));
            context.registerBean(DataSource.class,()->database);
            context.register(PortableSchemaInitializationConfiguration.class);
            context.registerBean("entityManagerFactory",SessionFactory.class,()->{
                assertThat(columns(database,"INVENTORY_COMPARISON")).contains("SCOPE_GUARD_KEY","ROW_VERSION");
                return new org.hibernate.cfg.Configuration().addAnnotatedClass(InventoryComparisonEntity.class)
                    .setProperty("hibernate.connection.url",database.getUrl()).setProperty("hibernate.connection.username","SA")
                    .setProperty("hibernate.hbm2ddl.auto","update").setProperty("hibernate.hbm2ddl.halt_on_error","true").buildSessionFactory();
            },definition->definition.setDestroyMethodName("close"));
            context.refresh();
            assertThat(jdbc.queryForList("select scope_guard_key from inventory_comparison order by id",String.class)).containsExactly("comparison:a","comparison:b");
            assertThat(jdbc.queryForList("select row_version from inventory_comparison",Long.class)).containsOnly(0L);
            assertThat(jdbc.queryForList("select comparison_json from inventory_comparison",String.class)).containsOnly("retained-immutable-json");
        }
    }
    @Test void populatedUpgradeIsIdempotentAndEnforcesDistinctMandatoryGuardsAndVersionDefault() {
        var database=database();var jdbc=legacy(database);
        InventoryDecisionSchemaMigration.migrate(database);InventoryDecisionSchemaMigration.migrate(database);
        assertThat(columns(database,"PROJECT_SOLUTION")).contains("INVENTORY_COMPARISON_ID","INVENTORY_FINGERPRINT","INVENTORY_HARD_EXCLUSIONS");
        assertThat(jdbc.queryForList("select scope_guard_key from inventory_comparison order by id",String.class)).containsExactly("comparison:a","comparison:b");
        jdbc.update("insert into inventory_comparison(id,repository_id,scope_key,branch_name,project_id,snapshot_id,actor,fingerprint,comparison_json,scope_guard_key) values('c','repo','scope','main',3,'snap','alice','fp','unchanged','comparison:c')");
        assertThat(jdbc.queryForObject("select row_version from inventory_comparison where id='c'",Long.class)).isZero();
        assertThatThrownBy(()->jdbc.update("update inventory_comparison set scope_guard_key=null where id='c'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update inventory_comparison set scope_guard_key='comparison:a' where id='c'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update inventory_comparison set row_version=null where id='c'")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void acceptsSqlMigratedEndStateWithoutAddingDuplicateGuard() throws Exception {
        var database=database();var jdbc=legacy(database);
        try(var connection=database.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("db/restore/hsqldb/V15__Inventory_variant_decisions.sql"));
        }
        InventoryDecisionSchemaMigration.migrate(database);
        assertThat(jdbc.queryForObject("select count(*) from inventory_comparison",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("select comparison_json from inventory_comparison",String.class)).containsOnly("retained-immutable-json");
    }
    @Test void freshDatabaseDoesNotAcquireFabricatedComparisonOrSolutionRows() {
        var database=database();InventoryDecisionSchemaMigration.migrate(database);
        assertThat(columns(database,"INVENTORY_COMPARISON")).isEmpty();assertThat(columns(database,"PROJECT_SOLUTION")).isEmpty();
    }
    private static JDBCDataSource database(){var ds=new JDBCDataSource();ds.setUrl("jdbc:hsqldb:mem:inventory-schema-"+UUID.randomUUID());ds.setUser("SA");return ds;}
    private static JdbcTemplate legacy(DataSource ds){var jdbc=new JdbcTemplate(ds);jdbc.execute("create table inventory_comparison(id varchar(200) primary key, repository_id varchar(320) not null, scope_key varchar(1024) not null, branch_name varchar(400) not null, project_id bigint not null, snapshot_id varchar(100) not null, actor varchar(160) not null, fingerprint varchar(128) not null, comparison_json clob not null)");jdbc.update("insert into inventory_comparison values('a','repo','scope','main',1,'snap','alice','fp','retained-immutable-json'),('b','repo','scope','main',2,'snap2','alice','fp2','retained-immutable-json')");jdbc.execute("create table project_solution(id bigint primary key)");return jdbc;}
    private static Set<String> columns(DataSource ds,String table){try(var c=ds.getConnection();var rows=c.getMetaData().getColumns(null,c.getSchema(),table,"%")){var columns=new HashSet<String>();while(rows.next())columns.add(rows.getString("COLUMN_NAME"));return columns;}catch(Exception e){throw new IllegalStateException(e);}}
}
