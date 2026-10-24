package com.taxonomy.shared.config;

import com.taxonomy.relations.model.RelationEvidence;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.taxonomy.shared.config.HypothesisBranchMigrationContract.sql;
import static com.taxonomy.shared.config.HypothesisBranchMigrationContract.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real no-Flyway profile boundary before Hibernate touches populated tables. */
class PortableHypothesisStartupTest {
    @Test
    void recordedProjectBranchBackfillsBeforeHibernateAddsMandatoryColumns() throws Exception {
        DataSource database=database();
        prepare(database);
        String scope=new RepositoryTenantIdentity("repo","CENTRAL","main").scopeKey();
        sql(database,"INSERT INTO arch_project VALUES (10,'repo',NULL,'CENTRAL','main','"+scope+"')");
        sql(database,"UPDATE relation_hypothesis SET project_id=10 WHERE id=1");
        AtomicBoolean hibernateStarted=new AtomicBoolean();
        try(var context=context(database,hibernateStarted)) {
            context.refresh();
            assertThat(hibernateStarted).isTrue();
            assertThat(scalar(database,"SELECT branch_name FROM relation_hypothesis WHERE id=1")).isEqualTo("main");
            assertThat(scalar(database,"SELECT branch_name FROM relation_evidence WHERE id=1")).isEqualTo("main");
        }
    }

    @Test
    void ambiguousRetainedRowsStopStartupBeforeHibernateAndRemainAvailableForRepair() throws Exception {
        DataSource database=database();
        prepare(database);
        AtomicBoolean hibernateStarted=new AtomicBoolean();
        try(var context=context(database,hibernateStarted)) {
            assertThatThrownBy(context::refresh).hasStackTraceContaining("missing_provenance");
            assertThat(hibernateStarted).isFalse();
            assertThat(scalar(database,"SELECT COUNT(*) FROM relation_hypothesis")).isEqualTo("1");
            assertThat(scalar(database,"SELECT COUNT(*) FROM relation_evidence")).isEqualTo("1");
        }
    }

    private AnnotationConfigApplicationContext context(DataSource database,AtomicBoolean started) {
        var context=new AnnotationConfigApplicationContext();
        // These are the shipped Oracle/SQL Server profile conditions, using HSQLDB locally.
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("profile",
                Map.of("spring.flyway.enabled","false","taxonomy.schema-migration.enabled","true")));
        context.registerBean(DataSource.class,()->database);
        context.register(PortableSchemaInitializationConfiguration.class);
        context.registerBean("entityManagerFactory",SessionFactory.class,()->{
            started.set(true);
            return factory(database);
        },definition->definition.setDestroyMethodName("close"));
        return context;
    }
    private SessionFactory factory(DataSource database) {
        var configuration=new org.hibernate.cfg.Configuration()
                .addAnnotatedClass(RelationHypothesis.class).addAnnotatedClass(RelationEvidence.class);
        configuration.getProperties().put("hibernate.connection.datasource",database);
        configuration.setProperty("hibernate.hbm2ddl.auto","update");
        configuration.setProperty("hibernate.hbm2ddl.halt_on_error","true");
        return configuration.buildSessionFactory();
    }
    private DataSource database() {
        return new DriverManagerDataSource("jdbc:hsqldb:mem:startup-"+UUID.randomUUID(),"SA","");
    }
    private void prepare(DataSource database) throws Exception {
        HypothesisBranchMigrationContract.createLegacy(database);
        sql(database,"ALTER TABLE relation_hypothesis ADD status VARCHAR(255) DEFAULT 'PROVISIONAL' NOT NULL");
        sql(database,"ALTER TABLE relation_hypothesis ADD created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL");
        sql(database,"ALTER TABLE relation_evidence ADD evidence_type VARCHAR(255) DEFAULT 'analysis-rule' NOT NULL");
        sql(database,"ALTER TABLE relation_evidence ADD created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL");
        sql(database,"INSERT INTO relation_hypothesis (id,repository_id,source_node_id,target_node_id,relation_type) VALUES (1,'repo','BP','CP','SUPPORTS')");
        sql(database,"INSERT INTO relation_evidence (id,hypothesis_id) VALUES (1,1)");
    }
}
