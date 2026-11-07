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
import javax.sql.DataSource;
import java.io.IOException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hsqldb.jdbc.JDBCDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    private JDBCDataSource database;

    @BeforeEach
    void openIndependentApplicationRepositories() throws Exception {
        String url = "jdbc:hsqldb:mem:template-shared-" + UUID.randomUUID();
        database = new JDBCDataSource();
        database.setUrl(url);
        database.setUser("SA");
        database.setPassword("");
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
                "/" + TemplateTestFixture.DEFAULT_RESOURCE)) {
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

        new DefaultDocumentTemplateBootstrap(first, TemplateTestFixture.contributions()).seedIfMissing();
        String winner = first.headCommit();
        assertThat(refPacks()).doesNotContainAnyElementsOf(initialRefPacks);

        // The second application opened before the winner published its template.
        // Its pack catalogue still names the removed initial HEAD reftable.
        new DefaultDocumentTemplateBootstrap(second, TemplateTestFixture.contributions()).seedIfMissing();

        assertThat(second.exists(TemplateTestFixture.TEMPLATE_ID)).isTrue();
        assertThat(second.headCommit()).isEqualTo(winner);
        assertThat(second.history(TemplateTestFixture.TEMPLATE_ID)).hasSize(1);
        assertThat(second.downloadCurrent(TemplateTestFixture.TEMPLATE_ID).content())
                .isEqualTo(first.downloadCurrent(TemplateTestFixture.TEMPLATE_ID).content());
    }

    @Test
    void laterWriterPreservesOtherTemplatesAndRejectsAStaleTemplateVersion() throws Exception {
        new DefaultDocumentTemplateBootstrap(first, TemplateTestFixture.contributions()).seedIfMissing();
        String winner = first.headCommit();

        var alpha = second.upload("alpha", "Alpha", new ByteArrayInputStream(bundledTemplate),
                null, "second-application", "Create alpha");
        assertThat(second.downloadCurrent(TemplateTestFixture.TEMPLATE_ID).commitId())
                .isEqualTo(winner);

        var revised = first.upload("alpha", "Alpha revised", new ByteArrayInputStream(bundledTemplate),
                alpha.headCommit(), "first-application", "Revise alpha");

        assertThatThrownBy(() -> second.upload("alpha", "Stale alpha",
                new ByteArrayInputStream(bundledTemplate), alpha.headCommit(),
                "second-application", "Stale replacement"))
                .isInstanceOf(com.taxonomy.templates.api.TemplateConflictException.class)
                .hasMessageContaining(revised.headCommit());
        assertThat(second.headCommit()).isEqualTo(revised.headCommit());
        assertThat(second.downloadCurrent("alpha").manifest().displayName()).isEqualTo("Alpha revised");
        assertThat(second.history("alpha")).hasSize(2);
        assertThat(second.history(TemplateTestFixture.TEMPLATE_ID)).hasSize(1);
    }

    @Test
    void concurrentLockedPutsCannotReuseOneIfMatchAfterAnotherInstanceCommits() throws Exception {
        var initial = first.upload("alpha", "Alpha", new ByteArrayInputStream(bundledTemplate),
                null, "admin", "Create alpha");
        var barrier = new CyclicBarrier(2);
        var firstLocks = new ConcurrentWriteLocks(database, barrier);
        var secondLocks = new ConcurrentWriteLocks(database, barrier);
        var lease = firstLocks.acquire("alpha", "admin", initial.headCommit(), java.time.Duration.ofMinutes(5), null);
        var firstServlet = new DocumentTemplateWebDavServlet(first, firstLocks);
        var secondServlet = new DocumentTemplateWebDavServlet(second, secondLocks);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> put(firstServlet, initial.headCommit(), lease.token(), new MockHttpServletResponse()));
            var two = workers.submit(() -> put(secondServlet, initial.headCommit(), lease.token(), new MockHttpServletResponse()));
            assertThat(List.of(one.get(10, TimeUnit.SECONDS).getStatus(), two.get(10, TimeUnit.SECONDS).getStatus()))
                    .containsExactlyInAnyOrder(204, 412);
        }
        assertThat(first.history("alpha")).hasSize(2);
        assertThat(second.history("alpha")).hasSize(2);
        assertThat(firstLocks.require("alpha", lease.token(), "admin").currentCommit())
                .isEqualTo(first.downloadCurrent("alpha").commitId());
    }

    @Test
    void lostHttpAcknowledgementRetainsTheCommittedBytesLeaseAndHistoryAcrossRestart() throws Exception {
        var initial = first.upload("alpha", "Alpha", new ByteArrayInputStream(bundledTemplate),
                null, "admin", "Create alpha");
        var locks = new DocumentTemplateWebDavLockManager(database);
        var lease = locks.acquire("alpha", "admin", initial.headCommit(), java.time.Duration.ofMinutes(5), null);
        var disconnected = new MockHttpServletResponse() {
            @Override public void setHeader(String name, String value) {
                if (name.equals("ETag")) throw new IllegalStateException("HTTP acknowledgement lost after Git success");
                super.setHeader(name, value);
            }
        };
        assertThatThrownBy(() -> put(new DocumentTemplateWebDavServlet(first, locks),
                initial.headCommit(), lease.token(), disconnected)).isInstanceOf(IllegalStateException.class);
        var saved = second.downloadCurrent("alpha");
        assertThat(saved.commitId()).isNotEqualTo(initial.headCommit());
        assertThat(saved.content()).isNotEmpty();
        var restarted = new DocumentTemplateWebDavLockManager(database);
        assertThat(restarted.require("alpha", lease.token(), "admin").currentCommit()).isEqualTo(saved.commitId());
        assertThat(put(new DocumentTemplateWebDavServlet(second, restarted), initial.headCommit(),
                lease.token(), new MockHttpServletResponse()).getStatus()).isEqualTo(412);
        assertThat(second.history("alpha")).hasSize(2);
        assertThat(second.download("alpha", initial.headCommit()).commitId()).isEqualTo(initial.headCommit());
    }

    @Test
    void concurrentCreateOnlyLockedPutsCannotReplaceTheFirstAcceptedTemplate() throws Exception {
        var barrier = new CyclicBarrier(2);
        var firstLocks = new ConcurrentWriteLocks(database, barrier);
        var secondLocks = new ConcurrentWriteLocks(database, barrier);
        var lease = firstLocks.acquire("alpha", "admin", null, java.time.Duration.ofMinutes(5), null);
        var firstServlet = new DocumentTemplateWebDavServlet(first, firstLocks);
        var secondServlet = new DocumentTemplateWebDavServlet(second, secondLocks);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> put(firstServlet, null, lease.token(), new MockHttpServletResponse()));
            var two = workers.submit(() -> put(secondServlet, null, lease.token(), new MockHttpServletResponse()));
            assertThat(List.of(one.get(10, TimeUnit.SECONDS).getStatus(), two.get(10, TimeUnit.SECONDS).getStatus()))
                    .containsExactlyInAnyOrder(201, 412);
        }
        assertThat(first.history("alpha")).hasSize(1);
        assertThat(second.downloadCurrent("alpha").commitId())
                .isEqualTo(firstLocks.require("alpha", lease.token(), "admin").currentCommit());
    }

    private MockHttpServletResponse put(DocumentTemplateWebDavServlet servlet, String expected,
                                       String token, MockHttpServletResponse response) throws Exception {
        var request = new MockHttpServletRequest("PUT", "/dav/templates/alpha.dotx");
        request.setPathInfo("/alpha.dotx");
        request.setUserPrincipal(() -> "admin");
        request.addUserRole("ADMIN");
        if (expected == null) request.addHeader("If-None-Match", "*");
        else request.addHeader("If-Match", "\"" + expected + "\"");
        request.addHeader("If", "(<" + token + ">)");
        request.setContent(bundledTemplate);
        servlet.service(request, response);
        return response;
    }

    private static final class ConcurrentWriteLocks extends DocumentTemplateWebDavLockManager {
        private final CyclicBarrier beforeFence;
        private ConcurrentWriteLocks(DataSource database, CyclicBarrier beforeFence) {
            super(database);
            this.beforeFence = beforeFence;
        }
        @Override public <T> T executeWrite(String resource, String token, String owner, String expected,
                                            LockedWrite<T> operation) throws IOException {
            try {
                beforeFence.await(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IOException("Concurrent PUTs did not reach the write fence", failure);
            }
            return super.executeWrite(resource, token, owner, expected, operation);
        }
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
                new OoxmlTemplatePackageCodec(), List.of(new TemplateTestFixture()));
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
