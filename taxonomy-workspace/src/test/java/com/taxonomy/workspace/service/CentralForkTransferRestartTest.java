package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import com.taxonomy.workspace.signing.GitSigningProperties;
import com.taxonomy.workspace.signing.SignatureStatus;
import com.taxonomy.workspace.signing.SigningTestKeys;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.GitSelectedTag;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class CentralForkTransferRestartTest {
    private static final String OWNER = "architect";
    private static final String BRANCH = "approved";
    private static final String TAG = "review/restart";
    private static final int BEFORE_PUBLICATION = 71;
    private static final int AFTER_PUBLICATION = 72;

    private record Selection(String source, String fork, String base, String local,
                             String intermediate, String incoming, String tag, String textPrefix) {}

    private record Selections(Selection interrupted, Selection independent) {}

    @Test
    void interruptedPublicationAndLostAcknowledgementKeepTheForkIndependent(@TempDir Path directory) throws Exception {
        runProcess(directory, "before-publication", BEFORE_PUBLICATION);
        runProcess(directory, "after-publication", AFTER_PUBLICATION);
        runProcess(directory, "retry", 0);
    }

    private static void runProcess(Path directory, String phase, int expectedExit) throws Exception {
        Path log = directory.resolve(phase + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx480m", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                TransferProcess.class.getName(), directory.toString(), phase)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(4, TimeUnit.MINUTES), () -> "Fork transfer process timed out in " + phase);
            assertEquals(expectedExit, process.exitValue(), phase + ": " + Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    public static final class TransferProcess {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]);
            String phase = args[1];
            var signer = signatures(directory, phase.equals("before-publication"));
            String database = "jdbc:hsqldb:file:" + directory.resolve("git") + ";shutdown=true;hsqldb.write_delay=false";
            try (var fixture = new CentralForkOriginalHistoryTest.Fixture(database, signer)) {
                if (phase.equals("before-publication")) {
                    firstFetch(directory, fixture);
                    fail("The first process returned before its publication boundary");
                }
                var selections = new ObjectMapper().readValue(directory.resolve("selected.json").toFile(), Selections.class);
                var ids = selections.interrupted();
                verifyIndependentPair(fixture, selections);
                String fork = ids.fork(), source = ids.source();
                String base = ids.base(), incoming = ids.incoming();
                var target = fixture.git.getCentralRepository(fork);
                var tracking = new GitUpstreamRef(source, BRANCH);
                var service = new CentralForkUpstreamService(fixture.catalog, fixture.memberships, fixture.git);
                assertEquals(base, fixture.catalog.getRepository(fork).getForkPointCommit());
                assertEquals(ids.local(), target.getHeadCommit(BRANCH));
                verifyClosure(target, ids);
                if (phase.equals("after-publication")) {
                    assertEquals(base, tracking.head(target), "Copied objects must not publish the tracking ref");
                    assertNull(GitSelectedTag.objectAt(target, TAG));
                } else {
                    assertEquals("retry", phase);
                    assertEquals(incoming, tracking.head(target), "The acknowledged ref must be durable");
                    assertEquals(ids.tag(), GitSelectedTag.objectAt(target, TAG));
                }
                assertEquals(incoming, service.fetch(fork, OWNER, new WorkspaceUpstreamService.Request(incoming, base)).trackedCommit());
                var tag = new WorkspaceUpstreamService.TagRequest(TAG, ids.tag(), incoming);
                assertEquals(tag.sourceTagObject(), service.fetchTag(fork, OWNER, tag).destinationTagObject());
                if (phase.equals("after-publication")) Runtime.getRuntime().halt(AFTER_PUBLICATION);

                fixture.git.deleteCentralRepository(source);
                verifyIndependentPair(fixture, selections);
                assertEquals(incoming, tracking.head(target));
                assertEquals(ids.tag(), GitSelectedTag.objectAt(target, TAG));
                assertEquals(ids.local(), target.getHeadCommit(BRANCH));
                verifyClosure(target, ids);
            }
        }

        private static GitCommitSignatureService signatures(Path directory, boolean firstProcess) throws Exception {
            Path trustedKey = directory.resolve("original-upstream-signer.asc");
            if (firstProcess) {
                Path keys = directory.resolve("keys");
                var signer = SigningTestKeys.createService(keys);
                var trust = SigningTestKeys.verifierProperties(keys);
                Files.copy(Path.of(trust.getTrustedPublicKeyFiles().getFirst()), trustedKey);
                return signer;
            }
            var trust = new GitSigningProperties();
            trust.setTrustedPublicKeyFiles(List.of(trustedKey.toString()));
            return new GitCommitSignatureService(trust);
        }

        private static void firstFetch(Path directory, CentralForkOriginalHistoryTest.Fixture fixture) throws Exception {
            var origin = fixture.createSource(RepositoryVisibility.PRIVATE);
            var independent = fixture.catalog.createCentralRepository("Independent source", "independent-origin",
                    null, RepositoryVisibility.PRIVATE, OWNER, BRANCH);
            fixture.catalog.markProvisioningReady(independent.getRepositoryId());
            var ids = prepareSelection(fixture, origin.getRepositoryId(), "restart-fork", "interrupted ");
            var other = prepareSelection(fixture, independent.getRepositoryId(), "independent-fork", "independent ");
            var selections = new Selections(ids, other);
            new ObjectMapper().writeValue(directory.resolve("selected.json").toFile(), selections);

            var copied = new CountDownLatch(1);
            var otherPublished = new CountDownLatch(1);
            var target = fixture.git.getCentralRepository(ids.fork());
            // The second real catalogue decision runs after durable object copy and before the tracking CAS.
            var catalogue = new SystemRepositoryService(fixture.rows,
                    org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(fixture.persistence.factory)) {
                private int decisions;

                @Override
                public <T> T readCurrentForkAuthorization(String forkId, BiFunction<SystemRepository, SystemRepository, T> decision) {
                    T resolved = fixture.catalog.readCurrentForkAuthorization(forkId, decision);
                    if (++decisions == 2) {
                        assertDoesNotThrow(() -> verifyClosure(target, ids));
                        assertDoesNotThrow(() -> assertEquals(ids.base(), new GitUpstreamRef(ids.source(), BRANCH).head(target)));
                        copied.countDown();
                        assertDoesNotThrow(() -> assertTrue(otherPublished.await(30, TimeUnit.SECONDS)));
                        Runtime.getRuntime().halt(BEFORE_PUBLICATION);
                    }
                    return resolved;
                }
            };
            var interrupted = new CentralForkUpstreamService(catalogue, fixture.memberships, fixture.git);
            var service = new CentralForkUpstreamService(fixture.catalog, fixture.memberships, fixture.git);
            try (var workers = Executors.newSingleThreadExecutor()) {
                var transfer = workers.submit(() -> interrupted.fetch(ids.fork(), OWNER,
                        new WorkspaceUpstreamService.Request(ids.incoming(), ids.base())));
                try {
                    assertTrue(copied.await(30, TimeUnit.SECONDS), "The interrupted transfer has not reached publication");
                    assertEquals(other.incoming(), service.fetch(other.fork(), OWNER,
                            new WorkspaceUpstreamService.Request(other.incoming(), other.base())).trackedCommit());
                    assertEquals(other.tag(), service.fetchTag(other.fork(), OWNER,
                            new WorkspaceUpstreamService.TagRequest(TAG, other.tag(), other.incoming())).destinationTagObject());
                    verifyIndependentPair(fixture, selections);
                    otherPublished.countDown();
                    transfer.get(30, TimeUnit.SECONDS);
                } finally {
                    transfer.cancel(true);
                }
            }
        }

        private static Selection prepareSelection(CentralForkOriginalHistoryTest.Fixture fixture, String source,
                                                  String slug, String textPrefix) throws Exception {
            var sourceGit = fixture.git.getCentralRepository(source);
            String base = sourceGit.commitDsl(BRANCH, textPrefix + "base", OWNER, "Initial architecture");
            String fork = fixture.service.createFork(source, BRANCH, "Restart fork", slug, null, null, OWNER)
                    .getRepositoryId();
            var target = fixture.git.getCentralRepository(fork);
            String local = target.commitDsl(BRANCH, textPrefix + "local version", OWNER, "Local edit");
            String intermediate = sourceGit.commitDsl(BRANCH, textPrefix + "upstream intermediate", OWNER, "Upstream intermediate");
            String incoming = sourceGit.commitDsl(BRANCH, textPrefix + "upstream final", OWNER, "Upstream final");
            String selected;
            try (var walk = new RevWalk(sourceGit.getGitRepository())) {
                selected = Git.wrap(sourceGit.getGitRepository()).tag().setName(TAG).setAnnotated(true)
                        .setMessage("Selected upstream revision").setTagger(new PersonIdent(OWNER, "fork@example.test"))
                        .setObjectId(walk.parseCommit(ObjectId.fromString(incoming))).call().getObjectId().name();
            }
            return new Selection(source, fork, base, local, intermediate, incoming, selected, textPrefix);
        }

        private static void verifyIndependentPair(CentralForkOriginalHistoryTest.Fixture fixture,
                                                  Selections selections) throws Exception {
            var ids = selections.interrupted();
            var other = selections.independent();
            var target = fixture.git.getCentralRepository(ids.fork());
            var otherSource = fixture.git.getCentralRepository(other.source());
            var otherTarget = fixture.git.getCentralRepository(other.fork());
            assertEquals(other.incoming(), otherSource.getHeadCommit(BRANCH));
            assertEquals(other.tag(), GitSelectedTag.objectAt(otherSource, TAG));
            assertEquals(other.local(), otherTarget.getHeadCommit(BRANCH));
            assertEquals(other.incoming(), new GitUpstreamRef(other.source(), BRANCH).head(otherTarget));
            assertEquals(other.tag(), GitSelectedTag.objectAt(otherTarget, TAG));
            verifyNoForeignObjects(target, other);
            verifyNoForeignObjects(otherTarget, ids);
            verifyClosure(otherTarget, other);
        }

        private static void verifyNoForeignObjects(DslGitRepository target, Selection other) throws Exception {
            try (var formatter = new ObjectInserter.Formatter()) {
                var blob = formatter.idFor(Constants.OBJ_BLOB,
                        (other.textPrefix() + "upstream final").getBytes(StandardCharsets.UTF_8));
                var objects = target.getGitRepository().getObjectDatabase();
                for (var id : List.of(ObjectId.fromString(other.incoming()), ObjectId.fromString(other.tag()), blob)) {
                    assertFalse(objects.has(id), () -> "Foreign object present in target: " + id.name());
                }
            }
        }

        private static void verifyClosure(DslGitRepository target, Selection ids) throws Exception {
            String incoming = ids.incoming();
            try (var walk = new ObjectWalk(target.getGitRepository())) {
                walk.markStart(walk.parseCommit(ObjectId.fromString(incoming)));
                walk.checkConnectivity();
            }
            assertEquals(ids.textPrefix() + "upstream final", target.getDslAtCommit(incoming));
            assertEquals(ids.textPrefix() + "upstream intermediate", target.getDslAtCommit(ids.intermediate()));
            for (String commit : List.of(incoming, ids.intermediate(), ids.base())) {
                assertEquals(SignatureStatus.VERIFIED, target.verifyCommit(commit).status());
            }
            assertTrue(target.isAncestor(ids.base(), incoming));
            assertFalse(target.isAncestor(ids.local(), incoming));
        }
    }
}
