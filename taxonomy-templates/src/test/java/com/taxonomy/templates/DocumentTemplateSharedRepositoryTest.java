package com.taxonomy.templates;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent application storage handles sharing real persisted Git packs and refs. */
@Isolated("Models separate application block caches using JGit's process-wide cache configuration")
class DocumentTemplateSharedRepositoryTest {
    private SessionFactory firstFactory;
    private SessionFactory secondFactory;
    private HibernateGitStorage firstStorage;
    private HibernateGitStorage secondStorage;
    private DocumentTemplateService first;
    private DocumentTemplateService second;
    private byte[] bundledTemplate;

    @BeforeEach
    void openIndependentApplicationRepositories() throws Exception {
        String url = "jdbc:hsqldb:mem:template-shared-" + UUID.randomUUID();
        firstFactory = sessionFactory(url);
        secondFactory = sessionFactory(url);
        var name = new RepositoryName(DocumentTemplateGitRepository.REPOSITORY_NAME);
        firstStorage = new DefaultHibernateRepositoryFactory(firstFactory).open(name);
        // Separate pods do not share JGit's static block cache. Keep the second
        // handle's cached pack catalogue, but do not let it borrow the writer's bytes.
        DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
        secondStorage = new DefaultHibernateRepositoryFactory(secondFactory).open(name);
        DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
        first = service(firstStorage);
        second = service(secondStorage);
        try (var input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            bundledTemplate = input.readAllBytes();
        }
    }

    @AfterEach
    void closeIndependentApplicationRepositories() {
        if (secondStorage != null) secondStorage.close();
        if (firstStorage != null) firstStorage.close();
        if (secondFactory != null) secondFactory.close();
        if (firstFactory != null) firstFactory.close();
        DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    }

    @Test
    void lateBootstrapReadsWinnerAfterInitialRefPackWasCompacted() throws Exception {
        List<String> initialRefPacks = refPacks();
        assertThat(initialRefPacks).isNotEmpty();

        new DefaultDocumentTemplateBootstrap(first).seedIfMissing();
        String winner = first.headCommit();
        assertThat(refPacks()).doesNotContainAnyElementsOf(initialRefPacks);

        // The second application opened before the winner published its template.
        // Its pack catalogue still names the removed initial HEAD reftable.
        new DefaultDocumentTemplateBootstrap(second).seedIfMissing();

        assertThat(second.exists(DecisionRationaleTemplateContract.TEMPLATE_ID)).isTrue();
        assertThat(second.headCommit()).isEqualTo(winner);
        assertThat(second.history(DecisionRationaleTemplateContract.TEMPLATE_ID)).hasSize(1);
        assertThat(second.downloadCurrent(DecisionRationaleTemplateContract.TEMPLATE_ID).content())
                .isEqualTo(first.downloadCurrent(DecisionRationaleTemplateContract.TEMPLATE_ID).content());
    }

    @Test
    void laterWriterPreservesOtherTemplatesAndRejectsAStaleTemplateVersion() throws Exception {
        new DefaultDocumentTemplateBootstrap(first).seedIfMissing();
        String winner = first.headCommit();

        var alpha = second.upload("alpha", "Alpha", new ByteArrayInputStream(bundledTemplate),
                null, "second-application", "Create alpha");
        assertThat(second.downloadCurrent(DecisionRationaleTemplateContract.TEMPLATE_ID).commitId())
                .isEqualTo(winner);

        var revised = first.upload("alpha", "Alpha revised", new ByteArrayInputStream(bundledTemplate),
                alpha.headCommit(), "first-application", "Revise alpha");

        assertThatThrownBy(() -> second.upload("alpha", "Stale alpha",
                new ByteArrayInputStream(bundledTemplate), alpha.headCommit(),
                "second-application", "Stale replacement"))
                .isInstanceOf(DocumentTemplateGitRepository.TemplateConflictException.class)
                .hasMessageContaining(revised.headCommit());
        assertThat(second.headCommit()).isEqualTo(revised.headCommit());
        assertThat(second.downloadCurrent("alpha").manifest().displayName()).isEqualTo("Alpha revised");
        assertThat(second.history("alpha")).hasSize(2);
        assertThat(second.history(DecisionRationaleTemplateContract.TEMPLATE_ID)).hasSize(1);
    }

    private List<String> refPacks() {
        try (var session = firstFactory.openSession()) {
            return session.createQuery("select p.packName from GitPackEntity p "
                            + "where p.repositoryName = :repository and p.packExtension = 'ref' "
                            + "and p.committed = true", String.class)
                    .setParameter("repository", DocumentTemplateGitRepository.REPOSITORY_NAME)
                    .getResultList();
        }
    }

    private static DocumentTemplateService service(HibernateGitStorage storage) {
        return new DocumentTemplateService(new DocumentTemplateGitRepository(storage.repository()),
                new OoxmlTemplatePackageCodec(), List.of(new DecisionRationaleTemplateContract()));
    }

    private static SessionFactory sessionFactory(String url) {
        var configuration = new Configuration()
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "SA")
                .setProperty("hibernate.connection.password", "")
                .setProperty("hibernate.connection.pool_size", "2")
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.search.enabled", "false");
        CoreEntities.annotatedClasses().forEach(configuration::addAnnotatedClass);
        return configuration.buildSessionFactory();
    }
}
