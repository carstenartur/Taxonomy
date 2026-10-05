package com.taxonomy.templates;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.CoreEntities;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateRepositoryConcurrencyTest {
    @TempDir Path temporary;

    @Test
    void headReadAndCommitThroughDifferentWrappersCannotDeadlockTheLiveRepository() throws Exception {
        Path output = temporary.resolve("template-lock-order.log");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-XX:MaxMetaspaceSize=192m", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LockOrderProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS))
                    .withFailMessage("Lock-order probe exceeded its deadline:%n%s", Files.readString(output))
                    .isTrue();
            assertThat(process.exitValue())
                    .withFailMessage("Lock-order probe failed:%n%s", Files.readString(output))
                    .isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** A deadlock must fail this subprocess, never block Maven's worker or fixture teardown. */
    public static final class LockOrderProbe {
        public static void main(String[] args) {
            try {
                exerciseConcurrentPublication();
            } catch (Throwable failure) {
                failure.printStackTrace();
                Thread.getAllStackTraces().forEach((thread, stack) -> {
                    if (thread.getName().startsWith("template-")) {
                        System.err.println(thread.getName() + " " + thread.getState());
                        Arrays.stream(stack).forEach(frame -> System.err.println("  at " + frame));
                    }
                });
                System.exit(1);
            }
        }

        private static void exerciseConcurrentPublication() throws Exception {
            var pause = new PackPublicationPause();
            var configuration = new Configuration()
                    .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                    .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:template-lock-" + UUID.randomUUID())
                    .setProperty("hibernate.connection.username", "SA")
                    .setProperty("hibernate.connection.password", "")
                    .setProperty("hibernate.connection.pool_size", "3")
                    .setProperty("hibernate.hbm2ddl.auto", "update")
                    .setProperty("hibernate.search.enabled", "false")
                    .setStatementInspector(pause);
            CoreEntities.annotatedClasses().forEach(configuration::addAnnotatedClass);
            var factory = configuration.buildSessionFactory();
            var storage = new DefaultHibernateRepositoryFactory(factory)
                    .open(new RepositoryName(DocumentTemplateGitRepository.REPOSITORY_NAME));
            var writer = new DocumentTemplateGitRepository(storage.repository());
            var reader = new DocumentTemplateGitRepository(storage.repository());
            Map<String, byte[]> parts;
            try (var input = LockOrderProbe.class.getResourceAsStream(
                    "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
                parts = new OoxmlTemplatePackageCodec().unpack(input).parts();
            }
            var manifest = new DocumentTemplateGitRepository.TemplateManifest(
                    1, "alpha", "Alpha", "alpha.dotx", OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                    Instant.EPOCH.toString(), "writer", parts.values().stream().mapToLong(value -> value.length).sum(),
                    parts.size(), OoxmlTemplatePackageCodec.packageSha256(parts));
            var commit = new FutureTask<>(() -> writer.commit(manifest, parts, null, "writer", "Create alpha"));
            var head = new FutureTask<>(reader::headCommit);
            Thread writing = daemon("template-writer", commit);
            Thread reading = daemon("template-reader", head);
            boolean completed = false;
            try {
                writing.start();
                assertThat(pause.beforeDatabaseLock.await(5, TimeUnit.SECONDS))
                        .as("writer reaches real pack publication while holding the catalogue write lock")
                        .isTrue();
                reading.start();
                awaitReadContention(reading);
                pause.resume.countDown();

                String committed = commit.get(3, TimeUnit.SECONDS).commitId();
                assertThat(head.get(3, TimeUnit.SECONDS)).isEqualTo(committed);
                assertThat(reader.readCurrent("alpha").commitId()).isEqualTo(committed);
                completed = true;
            } finally {
                pause.resume.countDown();
                commit.cancel(true);
                head.cancel(true);
                // A broken storage lock cannot be interrupted safely. On failure
                // main exits the process, without closing a still-active SessionFactory.
                if (completed) {
                    storage.close();
                    factory.close();
                }
            }
        }

        private static void awaitReadContention(Thread reader) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                StackTraceElement[] stack = reader.getStackTrace();
                boolean atRepositoryMonitor = reader.getState() == Thread.State.BLOCKED
                        && stack.length > 0
                        && stack[0].getClassName().equals(DocumentTemplateGitRepository.class.getName())
                        && stack[0].getMethodName().equals("headObjectId");
                boolean atCatalogueLock = reader.getState() == Thread.State.WAITING
                        && Arrays.stream(stack).anyMatch(frame ->
                        frame.getClassName().endsWith("ReadAheadHibernateObjDatabase")
                                && frame.getMethodName().equals("listPacks"));
                if (atRepositoryMonitor || atCatalogueLock) {
                    return;
                }
                if (!reader.isAlive()) throw new AssertionError("Reader did not contend with the paused publication");
                Thread.sleep(5);
            }
            throw new AssertionError("Reader did not reach the live repository or catalogue lock");
        }

        private static Thread daemon(String name, Runnable work) {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class PackPublicationPause implements StatementInspector {
        private final CountDownLatch beforeDatabaseLock = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean paused = new AtomicBoolean();

        @Override public String inspect(String sql) {
            if (Thread.currentThread().getName().equals("template-writer")
                    && sql.toLowerCase(java.util.Locale.ROOT).contains("for update")
                    && Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame ->
                    frame.getClassName().endsWith("ReadAheadHibernateObjDatabase")
                            && frame.getMethodName().equals("commitPackImpl"))
                    && paused.compareAndSet(false, true)) {
                beforeDatabaseLock.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Pack-publication test barrier timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Pack-publication test barrier interrupted", interrupted);
                }
            }
            return sql;
        }
    }
}
