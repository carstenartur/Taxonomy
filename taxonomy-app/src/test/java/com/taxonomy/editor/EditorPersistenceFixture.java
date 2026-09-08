package com.taxonomy.editor;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.editor.persistence.*;
import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;

/** Real journal and Git database; closing this fixture leaves no application persistence handle alive. */
public final class EditorPersistenceFixture implements AutoCloseable {
    public final SessionFactory factory;
    public final DslGitRepositoryFactory repositories;
    public final EditorJournal journal;
    public final ArchitectureEditorService service;

    public EditorPersistenceFixture(String url, Class<?>... additionalEntities) {
        Configuration configuration = new Configuration()
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "SA")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.connection.pool_size", "8")
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.search.enabled", "false");
        CoreEntities.annotatedClasses().forEach(configuration::addAnnotatedClass);
        configuration.addAnnotatedClass(EditorWorkspace.class).addAnnotatedClass(EditorOperation.class).addAnnotatedClass(EditorCheckpoint.class);
        for (Class<?> entity : additionalEntities) configuration.addAnnotatedClass(entity);
        factory = configuration.buildSessionFactory();
        repositories = new DslGitRepositoryFactory(new DefaultHibernateRepositoryFactory(factory));
        journal = new EditorJournal(factory, new JpaTransactionManager(factory));
        service = new ArchitectureEditorService(repositories, journal, new ArchitectureCheckpointWriter());
    }
    @Override public void close() { repositories.close(); factory.close(); }
}
