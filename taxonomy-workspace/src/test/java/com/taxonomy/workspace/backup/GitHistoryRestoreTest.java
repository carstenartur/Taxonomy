package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import com.taxonomy.workspace.storage.StagedJgitSchemaMigration;
import io.github.carstenartur.jgit.storage.hibernate.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class GitHistoryRestoreTest {
    private static final BackupCheckpoint CHECK=()->{};
    private static final PersonIdent AUTHOR=new PersonIdent("Original Author","original@example.invalid",Instant.parse("1980-01-02T03:04:05Z"),ZoneOffset.ofHours(2));
    private static final PersonIdent IMPORTER=new PersonIdent("Explicit Restore Operator","restore@example.invalid");
    @TempDir Path temporary;

    @Test void originalSourceCanBeProvenBeforeAnyTargetIsAllocatedAndRetainsTheFinalDigest() throws Exception {
        try (var source = source(GitRepresentation.BUNDLE)) {
            var before = source.payload.getRefDatabase().getRefsByPrefix("refs/").stream()
                    .map(ref -> ref.getName() + "=" + ref.getObjectId().name()).toList();
            var prepared = GitHistoryRestore.prepare(source.payload, source.evidence, GitHistoryCapture.Limits.defaults(), CHECK);
            assertThat(prepared.objectCount()).isEqualTo(source.evidence.objectCount());
            assertThat(prepared.expandedBytes()).isPositive();
            assertThat(prepared.inputSha256()).matches("[0-9a-f]{64}").isNotEqualTo("0".repeat(64));
            assertThat(source.payload.getRefDatabase().getRefsByPrefix("refs/").stream()
                    .map(ref -> ref.getName() + "=" + ref.getObjectId().name()).toList()).isEqualTo(before);
            try (var stage = new Stage()) {
                var restored = GitHistoryRestore.restore(prepared, stage.repository(), UUID.randomUUID(), IMPORTER, CHECK);
                assertThat(restored.repositorySha256()).isEqualTo(prepared.inputSha256());
                assertThat(restored.state().requiredCommits()).contains(source.first, source.head, source.orphan);
            }
        }
    }

    @ParameterizedTest @EnumSource(value=GitRepresentation.class,names={"BUNDLE","BARE","WORKTREE"})
    void nativePayloadRestoresOriginalObjectsRefsAliasesAndEventEvidenceToVersionedSql(GitRepresentation representation)throws Exception {
        try(var source=source(representation);var stage=new Stage()) {
            var proof=GitHistoryRestore.restore(source.payload,source.evidence,stage.repository(),GitHistoryCapture.Limits.defaults(),UUID.randomUUID(),IMPORTER,CHECK);
            assertThat(proof.state().requiredCommits()).contains(source.first,source.head,source.orphan);
            assertThat(proof.commitMappings()).containsEntry(source.first,source.first).containsEntry(source.head,source.head).containsEntry(source.orphan,source.orphan);
            for(var ref:source.evidence.state().refs().entrySet())
                assertThat(stage.repository().exactRef(ref.getKey()).getObjectId().name()).isEqualTo(ref.getValue());
            assertThat(stage.repository().exactRef("HEAD").getTarget().getName()).isEqualTo("refs/heads/alias");
            assertThat(stage.repository().exactRef("refs/heads/alias").getTarget().getName()).isEqualTo("refs/heads/main");
            assertThat(proof.state().workingStates()).as("Git import cannot claim SQL editor rows were restored").isEmpty();
            assertThat(body(stage.repository(),source.first)).isEqualTo("ORIGINAL");
            assertThat(body(stage.repository(),source.orphan)).isEqualTo("DATABASE-ONLY");
            try(var walk=new RevWalk(stage.repository())) {
                var commit=walk.parseCommit(ObjectId.fromString(source.head));
                assertThat(commit.getParent(0).name()).isEqualTo(source.first);
                assertThat(commit.getAuthorIdent().getWhenAsInstant()).isEqualTo(AUTHOR.getWhenAsInstant());
            }
            assertThat(proof.originalEvents()).containsExactlyElementsOf(GitReflogProvenance.original(source.evidence.origin(),source.evidence.reflogs()));
            assertThat(stage.repository().getReflogReader("refs/heads/main").getLastEntry().getWho().getName()).isEqualTo(IMPORTER.getName());
            assertThat(proof.repositorySha256()).matches("[0-9a-f]{64}").isNotEqualTo("0".repeat(64));
        }
    }

    @Test void anInterruptedObjectImportCanResumeWithoutDuplicateRefAudit()throws Exception {
        try(var source=source(GitRepresentation.BUNDLE);var stage=new Stage()) {
            var operation=UUID.randomUUID();
            try {
                assertThatThrownBy(()->GitHistoryRestore.restore(source.payload,source.evidence,stage.repository(),GitHistoryCapture.Limits.defaults(),operation,IMPORTER,()->{
                    if(contains(stage.repository(),source.head))throw new InterruptedIOException("Cancelled after actual Git object publication");
                })).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(stage.repository().getRefDatabase().getRefsByPrefix("refs/")).isEmpty();
            var first=GitHistoryRestore.restore(source.payload,source.evidence,stage.repository(),GitHistoryCapture.Limits.defaults(),operation,IMPORTER,CHECK);
            var logs=stage.repository().getReflogReader("refs/heads/main").getReverseEntries();
            var retry=GitHistoryRestore.restore(source.payload,source.evidence,stage.repository(),GitHistoryCapture.Limits.defaults(),operation,IMPORTER,CHECK);
            assertThat(retry).isEqualTo(first);
            assertThat(stage.repository().getReflogReader("refs/heads/main").getReverseEntries()).hasSameSizeAs(logs);
        }
    }

    @Test void completedOwnerReadbackProvesObjectsAndRefsWithoutRecreatingMissingState()throws Exception {
        try(var source=source(GitRepresentation.BUNDLE);var stage=new Stage()) {
            var prepared=GitHistoryRestore.prepare(source.payload,source.evidence,GitHistoryCapture.Limits.defaults(),CHECK);
            var proof=GitHistoryRestore.restore(prepared,stage.repository(),UUID.randomUUID(),IMPORTER,CHECK);
            int before=stage.repository().getReflogReader("refs/heads/main").getReverseEntries().size();
            assertThat(GitHistoryRestore.verify(prepared,stage.repository(),CHECK)).isEqualTo(proof);
            assertThat(stage.repository().getReflogReader("refs/heads/main").getReverseEntries()).hasSize(before);
            var remove=stage.repository().updateRef("refs/tags/original-tag");remove.setForceUpdate(true);
            assertThat(remove.delete()).isEqualTo(RefUpdate.Result.FORCED);
            assertThatThrownBy(()->GitHistoryRestore.verify(prepared,stage.repository(),CHECK)).isInstanceOf(IOException.class);
            assertThat(stage.repository().exactRef("refs/tags/original-tag")).isNull();
            assertThat(stage.repository().getReflogReader("refs/heads/main").getReverseEntries()).hasSize(before);
        }
    }

    @Test void aTargetWithUnapprovedRefsIsRejectedBeforeSourceObjectsAreWritten()throws Exception {
        try(var source=source(GitRepresentation.BUNDLE);var stage=new Stage();var other=new com.taxonomy.workspace.storage.DslGitRepository(
                new DefaultHibernateRepositoryFactory(stage.factory),"private-stage")) {
            String before=other.commitDsl("foreign","DO NOT MODIFY","target author","existing target");
            assertThat(other.getGitRepository().exactRef("refs/heads/foreign").getObjectId().name())
                    .as("Independent writer confirmed the existing target ref before importer").isEqualTo(before);
            assertThat(contains(stage.repository(),source.head)).as("Original object absent before importer").isFalse();
            assertThatThrownBy(()->GitHistoryRestore.restore(source.payload,source.evidence,stage.repository(),GitHistoryCapture.Limits.defaults(),UUID.randomUUID(),IMPORTER,CHECK))
                    .isInstanceOf(IOException.class);
            assertThat(stage.repository().exactRef("refs/heads/foreign").getObjectId().name()).isEqualTo(before);
            assertThat(contains(stage.repository(),source.head)).isFalse();
        }
    }

    @Test void incompleteObjectEvidenceAndCyclicAliasesFailBeforeAnyRefPublication()throws Exception {
        try(var source=source(GitRepresentation.BARE);var stage=new Stage()) {
            var original=source.evidence;
            var count=new GitHistoryRestore.Source(original.origin(),original.state(),original.backupRefs(),original.symbolicRefs(),original.reflogs(),original.importedReflogs(),original.objectCount()+1);
            var cycle=new GitHistoryRestore.Source(original.origin(),original.state(),original.backupRefs(),Map.of("refs/heads/alias","refs/heads/alias"),original.reflogs(),original.importedReflogs(),original.objectCount());
            for(var invalid:List.of(count,cycle)) {
                assertThatThrownBy(()->GitHistoryRestore.restore(source.payload,invalid,stage.repository(),GitHistoryCapture.Limits.defaults(),UUID.randomUUID(),IMPORTER,CHECK))
                        .isInstanceOf(IOException.class);
                assertThat(stage.repository().getRefDatabase().getRefsByPrefix("refs/")).isEmpty();
                assertThat(contains(stage.repository(),source.head)).isFalse();
            }
        }
    }

    @Test void captureRechecksPersistedRefsAcrossIndependentHandlesEvenWithoutNewReflogs()throws Exception {
        try(var stage=new Stage();var writer=new com.taxonomy.workspace.storage.DslGitRepository(
                new DefaultHibernateRepositoryFactory(stage.factory),"private-stage")) {
            String first=writer.commitDsl("main","FIRST","original author","first");
            // This reader handle predates the writer's commit and still has its initial cached table.
            var captured=GitHistoryCapture.capture(stage.repository(),Set.of(),Map.of(),GitHistoryCapture.Limits.defaults(),CHECK,CHECK,
                    checkpoint->Set.of("refs/heads/main"));
            assertThat(captured.state().refs()).containsEntry("refs/heads/main",first);
            var update=writer.getGitRepository().updateRef("refs/heads/after-capture");
            update.setExpectedOldObjectId(ObjectId.zeroId());update.setNewObjectId(ObjectId.fromString(first));
            update.disableRefLog();assertThat(update.update()).isEqualTo(RefUpdate.Result.NEW);
            var sink=new FilesSink(temporary.resolve("stale-capture"));
            assertThatThrownBy(()->captured.write("source",GitRepresentation.BARE,sink)).isInstanceOf(IOException.class);
            assertThat(Files.exists(sink.root)).isFalse();
        }
    }

    @Test void unresolvableAliasDepthIsRejectedBeforeImportWrites()throws Exception {
        try(var source=source(GitRepresentation.BARE);var stage=new Stage()) {
            var original=source.evidence;var refs=new TreeMap<>(original.state().refs());
            var aliases=new TreeMap<>(original.symbolicRefs());
            for(int index=0;index<=RefDatabase.MAX_SYMBOLIC_REF_DEPTH;index++) {
                String name="refs/heads/deep-"+index;refs.put(name,source.head);
                aliases.put(name,index==RefDatabase.MAX_SYMBOLIC_REF_DEPTH?"refs/heads/main":"refs/heads/deep-"+(index+1));
            }
            var state=new SnapshotContext.RepositoryState(refs,original.state().symbolicHead(),original.state().workingStates(),original.state().requiredCommits());
            var invalid=new GitHistoryRestore.Source(original.origin(),state,original.backupRefs(),aliases,original.reflogs(),original.importedReflogs(),original.objectCount());
            assertThatThrownBy(()->GitHistoryRestore.restore(source.payload,invalid,stage.repository(),GitHistoryCapture.Limits.defaults(),UUID.randomUUID(),IMPORTER,CHECK))
                    .isInstanceOf(IOException.class);
            assertThat(contains(stage.repository(),source.head)).as("Invalid source aliases are rejected before Git publication").isFalse();
            assertThat(stage.repository().getRefDatabase().getRefsByPrefix("refs/")).isEmpty();
        }
    }

    private SourceFixture source(GitRepresentation representation)throws Exception {
        Path root=Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));Path original=root.resolve("original");
        GitHistoryRestore.Source evidence;String first,head,orphan;var sink=new FilesSink(root.resolve("archive"));
        try(var git=Git.init().setInitialBranch("main").setDirectory(original.toFile()).call()) {
            first=commit(git,"ORIGINAL");head=commit(git,"CURRENT");
            git.tag().setName("original-tag").setAnnotated(true).setTagger(AUTHOR).setMessage("Original annotated tag").call();
            try(var insert=git.getRepository().newObjectInserter()) {
                var tree=new TreeFormatter();tree.append("architecture.taxdsl",FileMode.REGULAR_FILE,insert.insert(Constants.OBJ_BLOB,"DATABASE-ONLY".getBytes(StandardCharsets.UTF_8)));
                var commit=new CommitBuilder();commit.setTreeId(insert.insert(tree));commit.setAuthor(AUTHOR);commit.setCommitter(AUTHOR);commit.setMessage("Original unreachable commit");orphan=insert.insert(commit).name();insert.flush();
            }
            link(git.getRepository(),"refs/heads/alias","refs/heads/main");link(git.getRepository(),"HEAD","refs/heads/alias");
            var capture=GitHistoryCapture.capture(git.getRepository(),Set.of(orphan),Map.of("main",new SnapshotContext.WorkingState(4,head,3)),GitHistoryCapture.Limits.defaults(),CHECK);
            capture.write("original-repository",representation,sink);
            evidence=GitHistoryRestore.Source.captured(capture,new GitReflogProvenance.Origin("a".repeat(64),new BackupRepositoryKey("original",null),"original-repository"));
        }
        Files.move(original,root.resolve("original-disabled"));
        Path payload=sink.root.resolve("repositories/original-repository");
        if(representation==GitRepresentation.BUNDLE) {
            Path clone=root.resolve("payload.git");run("git","clone","--mirror",payload.resolve("history.bundle").toString(),clone.toString());payload=clone;
        } else if(representation==GitRepresentation.WORKTREE)payload=payload.resolve(".git");
        return new SourceFixture(new FileRepositoryBuilder().setGitDir(payload.toFile()).setBare().build(),evidence,first,head,orphan);
    }
    private static String commit(Git git,String body)throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve("architecture.taxdsl"),body);git.add().addFilepattern("architecture.taxdsl").call();
        return git.commit().setAuthor(AUTHOR).setCommitter(AUTHOR).setMessage(body).call().name();
    }
    private static void link(Repository repo,String name,String target)throws Exception {
        var before=repo.exactRef(name);var update=repo.updateRef(name);update.setExpectedOldObjectId(before==null||before.getObjectId()==null?ObjectId.zeroId():before.getObjectId());
        update.setRefLogIdent(AUTHOR);update.setRefLogMessage("Original symbolic selection",false);
        assertThat(update.link(target)).isIn(RefUpdate.Result.NEW,RefUpdate.Result.FORCED,RefUpdate.Result.NO_CHANGE);
    }
    private static String body(Repository repository,String commit)throws Exception {
        try(var walk=new RevWalk(repository);var tree=org.eclipse.jgit.treewalk.TreeWalk.forPath(repository,"architecture.taxdsl",walk.parseCommit(ObjectId.fromString(commit)).getTree())) {
            return new String(repository.open(tree.getObjectId(0)).getBytes(1_000),StandardCharsets.UTF_8);
        }
    }
    private static boolean contains(Repository repository,String object)throws IOException {
        try(var reader=repository.newObjectReader()){return reader.has(ObjectId.fromString(object));}
    }
    private static void run(String... args)throws Exception {
        var process=new ProcessBuilder(args).redirectErrorStream(true).start();byte[] output=process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as(new String(output,StandardCharsets.UTF_8)).isZero();
    }
    private record SourceFixture(Repository payload,GitHistoryRestore.Source evidence,String first,String head,String orphan)implements AutoCloseable {
        public void close(){payload.close();}
    }
    private static final class Stage implements AutoCloseable {
        final org.hibernate.SessionFactory factory;final HibernateGitStorage handle;
        Stage()throws Exception {
            var database=new JDBCDataSource();database.setURL("jdbc:hsqldb:mem:git-restore-"+UUID.randomUUID());database.setUser("sa");
            StagedJgitSchemaMigration.migrate(database,CHECK);var config=new org.hibernate.cfg.Configuration();config.getProperties().put("hibernate.connection.datasource",database);
            config.setProperty("hibernate.hbm2ddl.auto","validate");config.setProperty("hibernate.search.enabled","false");StagedJgitSchemaMigration.configureModel(config);
            factory=config.buildSessionFactory();handle=new DefaultHibernateRepositoryFactory(factory).open(new RepositoryName("private-stage"));
        }
        Repository repository(){return handle.repository();}
        public void close(){handle.close();factory.close();}
    }
    private static final class FilesSink implements ComponentSink {
        final Path root;FilesSink(Path root){this.root=root;}
        public BackupEntry write(String path,InputStream input)throws IOException {
            byte[] bytes=input.readAllBytes();Path target=root.resolve(path);Files.createDirectories(target.getParent());
            if(path.endsWith("/"))Files.createDirectories(target);else Files.write(target,bytes,StandardOpenOption.CREATE_NEW);
            return new BackupEntry(path,bytes.length,GitReflogProvenance.digest(bytes));
        }
        public BackupEntry writeGenerated(String path,EntryWriter producer)throws IOException {
            var output=new ByteArrayOutputStream();producer.write(output);return write(path,new ByteArrayInputStream(output.toByteArray()));
        }
    }
}
