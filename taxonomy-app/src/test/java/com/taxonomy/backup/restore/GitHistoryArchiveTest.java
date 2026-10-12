package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.workspace.backup.*;
import org.apache.commons.compress.archivers.zip.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import static org.assertj.core.api.Assertions.*;

class GitHistoryArchiveTest {
    static final BackupCheckpoint CHECK = () -> { };
    static final BackupComponentId OWNER = new BackupComponentId("workspace");
    static final BackupRepositoryKey SOURCE = new BackupRepositoryKey("source-repository", null);
    static final String ARCHIVE_ID = "original-history";
    static final PersonIdent ORIGINAL = new PersonIdent("Original author", "original@example.invalid",
            Instant.parse("1980-01-02T03:04:05Z"), ZoneOffset.ofHours(2));
    @TempDir Path temporary;

    @ParameterizedTest @EnumSource(value=GitRepresentation.class,names={"BUNDLE","BARE","WORKTREE"})
    void realVerifiedArchivePreparesTheOriginalClosureWithoutAllocatingTheTarget(GitRepresentation representation) throws Exception {
        Path privateSpool = Files.createDirectory(temporary.resolve("private"));
        try (var fixture = fixture(temporary, representation, entries -> { })) {
            try (var archive = GitHistoryArchive.open(fixture.archive(), fixture.repository(), privateSpool, GitHistoryCapture.Limits.defaults(), CHECK)) {
                assertThat(archive.source().origin()).isEqualTo(new GitReflogProvenance.Origin(fixture.archive().archiveSha256(), SOURCE, ARCHIVE_ID));
                assertThat(archive.source().state()).isEqualTo(fixture.repository().captured());
                assertThat(archive.prepared().objectCount()).isEqualTo(fixture.count());
                assertThat(Files.exists(temporary.resolve("target.git"))).isFalse();
                try (var target = Git.init().setBare(true).setDirectory(temporary.resolve("target.git").toFile()).call()) {
                    var proof = GitHistoryRestore.restore(archive.prepared(), target.getRepository(), UUID.randomUUID(),
                            new PersonIdent("Restore operator", "restore@example.invalid"), CHECK);
                    assertThat(proof.repositorySha256()).isEqualTo(archive.prepared().inputSha256());
                    assertThat(proof.commitMappings()).containsEntry(fixture.first(), fixture.first()).containsEntry(fixture.head(), fixture.head());
                    assertThat(target.getRepository().exactRef("HEAD").getTarget().getName()).isEqualTo("refs/heads/alias");
                    assertThat(target.getRepository().exactRef("refs/heads/alias").getTarget().getName()).isEqualTo("refs/heads/main");
                }
            }
        }
        try (var files = Files.list(privateSpool)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void archiveDigestCannotConcealDisagreementBetweenManifestAndHistoryRecords() throws Exception {
        Path spool = Files.createDirectory(temporary.resolve("private"));
        try (var fixture = fixture(temporary, GitRepresentation.BUNDLE, entries -> {
            String path = "data/workspace/git-history-" + ARCHIVE_ID + ".ndjson";
            String text = new String(entries.get(path), StandardCharsets.UTF_8).replace("\"archiveId\":\"" + ARCHIVE_ID + "\"", "\"archiveId\":\"other\"");
            entries.put(path, text.getBytes(StandardCharsets.UTF_8));
        })) {
            assertThatThrownBy(() -> GitHistoryArchive.open(fixture.archive(), fixture.repository(), spool, GitHistoryCapture.Limits.defaults(), CHECK))
                    .isInstanceOf(IOException.class);
        }
        try (var files = Files.list(spool)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void nativeContainerConfigurationRefsIndexAndUnknownResourcesMustMatchTheOriginalEvidence() throws Exception {
        for (String change : List.of("config", "HEAD", "packed-refs", "index", "extra-pack", "hook")) {
            Path root = Files.createDirectory(temporary.resolve(change)); Path spool = Files.createDirectory(root.resolve("private"));
            try (var fixture = fixture(root, GitRepresentation.BARE, entries -> {
                String prefix = "repositories/" + ARCHIVE_ID + "/";
                switch (change) {
                    case "config" -> entries.put(prefix + "config", "[include]\npath=/untrusted/config\n".getBytes(StandardCharsets.UTF_8));
                    case "HEAD" -> entries.put(prefix + "HEAD", "ref: refs/heads/foreign\n".getBytes(StandardCharsets.UTF_8));
                    case "packed-refs" -> entries.put(prefix + "packed-refs", new byte[0]);
                    case "index" -> { String path=entries.keySet().stream().filter(p->p.endsWith(".idx")).findFirst().orElseThrow();var bytes=entries.get(path).clone();bytes[bytes.length-1]^=1;entries.put(path,bytes); }
                    case "extra-pack" -> { String path=entries.keySet().stream().filter(p->p.endsWith(".pack")).findFirst().orElseThrow();entries.put(prefix+"objects/pack/pack-"+"b".repeat(40)+".pack",entries.get(path)); }
                    case "hook" -> entries.put(prefix + "hooks/post-checkout", "unexpected executable input".getBytes(StandardCharsets.UTF_8));
                }
            })) {
                assertThatThrownBy(() -> GitHistoryArchive.open(fixture.archive(), fixture.repository(), spool, GitHistoryCapture.Limits.defaults(), CHECK))
                        .as(change).isInstanceOf(IOException.class);
            }
            try (var files = Files.list(spool)) { assertThat(files.toList()).isEmpty(); }
        }
    }

    @Test void bundlePrerequisitesAndTrailingBytesAreNotAcceptedAsIndependentOriginalHistory() throws Exception {
        for (boolean prerequisite : List.of(true, false)) {
            Path root = Files.createDirectory(temporary.resolve("bundle-" + prerequisite)); Path spool = Files.createDirectory(root.resolve("private"));
            try (var fixture = fixture(root, GitRepresentation.BUNDLE, entries -> {
                String path = "repositories/" + ARCHIVE_ID + "/history.bundle"; byte[] bytes = entries.get(path);
                if (prerequisite) {
                    int newline = 0; while (bytes[newline] != '\n') newline++;
                    var output = new ByteArrayOutputStream(); output.write(bytes,0,newline+1);
                    output.writeBytes(("-"+"a".repeat(40)+" external prerequisite\n").getBytes(StandardCharsets.UTF_8));
                    output.write(bytes,newline+1,bytes.length-newline-1);entries.put(path,output.toByteArray());
                } else entries.put(path, Arrays.copyOf(bytes,bytes.length+1));
            })) {
                assertThatThrownBy(() -> GitHistoryArchive.open(fixture.archive(), fixture.repository(), spool, GitHistoryCapture.Limits.defaults(), CHECK))
                        .isInstanceOf(IOException.class);
            }
            try (var files = Files.list(spool)) { assertThat(files.toList()).isEmpty(); }
        }
    }

    @Test void cancellationDuringPrivatePreparationCleansEveryCreatedSpoolAndPreservesInterruption() throws Exception {
        Path spool = Files.createDirectory(temporary.resolve("private")); var calls = new AtomicInteger();
        try (var fixture = fixture(temporary, GitRepresentation.BUNDLE, entries -> { })) {
            try {
                assertThatThrownBy(() -> GitHistoryArchive.open(fixture.archive(), fixture.repository(), spool, GitHistoryCapture.Limits.defaults(), () -> {
                    try(var created=Files.list(spool)) {
                        if(created.findAny().isPresent()&&calls.incrementAndGet()==30)throw new InterruptedIOException("cancel preparation");
                    }
                })).isInstanceOf(InterruptedIOException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
        }
        try (var files = Files.list(spool)) { assertThat(files.toList()).isEmpty(); }
    }

    @ParameterizedTest @EnumSource(value=GitRepresentation.class,names={"BARE","WORKTREE"})
    void emptyOriginalRepositoryRetainsItsUnbornHeadWithoutInventingAnInitialCommit(GitRepresentation representation) throws Exception {
        Path spool=Files.createDirectory(temporary.resolve("private"));var sink=new Sink();SnapshotContext.RepositoryState state;
        try(var git=Git.init().setInitialBranch("unborn").setDirectory(temporary.resolve("empty").toFile()).call()) {
            var capture=GitHistoryCapture.capture(git.getRepository(),Set.of(),Map.of(),GitHistoryCapture.Limits.defaults(),CHECK);
            state=capture.state();capture.write(ARCHIVE_ID,representation,BackupProfile.REPOSITORY_HISTORY,sink);
        }
        try(var fixture=archive(temporary,representation,state,null,null,0,sink);
            var source=GitHistoryArchive.open(fixture.archive(),fixture.repository(),spool,GitHistoryCapture.Limits.defaults(),CHECK);
            var target=Git.init().setBare(true).setDirectory(temporary.resolve("target.git").toFile()).call()) {
            var proof=GitHistoryRestore.restore(source.prepared(),target.getRepository(),UUID.randomUUID(),ORIGINAL,CHECK);
            assertThat(proof.commitMappings()).isEmpty();assertThat(proof.state().refs()).isEmpty();
            assertThat(target.getRepository().exactRef("HEAD").getTarget().getName()).isEqualTo("refs/heads/unborn");
            assertThat(target.getRepository().getObjectDatabase().getApproximateObjectCount()).isZero();
        }
        try(var files=Files.list(spool)){assertThat(files.toList()).isEmpty();}
    }

    static Fixture fixture(Path root, GitRepresentation representation, Consumer<Map<String,byte[]>> mutation) throws Exception {
        String first, head; int count; SnapshotContext.RepositoryState state; var sink = new Sink();
        Path original = root.resolve("original");
        try (var git = Git.init().setInitialBranch("main").setDirectory(original.toFile()).call()) {
            Files.writeString(original.resolve("architecture.taxdsl"), "ORIGINAL"); Files.write(original.resolve("deleted.bin"), new byte[]{1,2,3});
            git.add().addFilepattern(".").call(); first=git.commit().setAuthor(ORIGINAL).setCommitter(ORIGINAL).setMessage("Original ancestor").call().name();
            Files.delete(original.resolve("deleted.bin")); git.rm().addFilepattern("deleted.bin").call();
            Files.writeString(original.resolve("architecture.taxdsl"), "CURRENT"); var binary=new byte[16_384];new Random(21).nextBytes(binary);Files.write(original.resolve("current.bin"),binary);
            git.add().addFilepattern(".").call();head=git.commit().setAuthor(ORIGINAL).setCommitter(ORIGINAL).setMessage("Original current").call().name();
            git.tag().setName("original").setAnnotated(true).setTagger(ORIGINAL).setMessage("Original tag").call();
            git.getRepository().updateRef("refs/heads/alias").link("refs/heads/main");git.getRepository().updateRef("HEAD").link("refs/heads/alias");
            var capture=GitHistoryCapture.capture(git.getRepository(),Set.of(),Map.of(),GitHistoryCapture.Limits.defaults(),CHECK);
            state=capture.state();count=GitHistoryRestore.Source.captured(capture,new GitReflogProvenance.Origin("a".repeat(64),SOURCE,ARCHIVE_ID)).objectCount();
            capture.write(ARCHIVE_ID,representation,BackupProfile.REPOSITORY_HISTORY,sink);
        }
        Files.move(original, root.resolve("original-disabled")); mutation.accept(sink.entries);
        return archive(root,representation,state,first,head,count,sink);
    }
    private static Fixture archive(Path root,GitRepresentation representation,SnapshotContext.RepositoryState state,
            String first,String head,int count,Sink sink)throws Exception {
        var repo = new BackupManifest.Repository(SOURCE, ARCHIVE_ID, representation, state, null, head, null);
        var request = new BackupRequest(BackupProfile.REPOSITORY_HISTORY,new BackupScope.Repositories(Map.of(SOURCE.repositoryId(),Set.of())),new BackupTime.History(),representation,SecretsSelection.EXCLUDE);
        var entries=sink.entries.entrySet().stream().map(e->new BackupEntry(e.getKey(),e.getValue().length,sha(e.getValue()))).toList();
        var manifest = new BackupManifest(1,"1.5.1-SNAPSHOT","native-history-fixture",new BackupId(UUID.randomUUID()),UUID.randomUUID().toString(),request,
                Instant.parse("2026-10-11T00:00:00Z"),Instant.parse("2026-10-11T00:00:00Z"),"writer-barrier-v1:installation:generation=1",
                Set.of("component-sha256","explicit-scope","directory-entries-v1"),
                List.of(new BackupManifest.Component(OWNER,1,BackupCompleteness.COMPLETE,entries.stream().map(BackupEntry::path).toList(),Set.of())),
                List.of(repo),entries,List.of(),List.of());
        Path file=root.resolve("original.taxbackup");
        try (var zip=new ZipArchiveOutputStream(file)) {
            zip.setUseZip64(Zip64Mode.AlwaysWithCompatibility);zip.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
            for(var entry:sink.entries.entrySet())write(zip,entry.getKey(),entry.getValue());
            write(zip,"manifest.json",new BackupManifestCodec().write(manifest));
        }
        return new Fixture(new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),"1.5.1-SNAPSHOT",Map.of(OWNER,1),BackupFeaturePrerequisites.discover()).verify(file),repo,first,head,count);
    }
    private static void write(ZipArchiveOutputStream zip,String path,byte[] bytes)throws IOException {
        var crc=new CRC32();crc.update(bytes);var entry=new ZipArchiveEntry(path);entry.setMethod(ZipArchiveEntry.STORED);entry.setSize(bytes.length);entry.setCrc(crc.getValue());
        entry.setUnixMode(path.endsWith("/")?040700:0100600);entry.setTime(631152000000L);zip.putArchiveEntry(entry);zip.write(bytes);zip.closeArchiveEntry();
    }
    static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new AssertionError(impossible);}
    }
    record Fixture(VerifiedBackup archive,BackupManifest.Repository repository,String first,String head,int count) implements AutoCloseable {
        public void close()throws IOException{archive.close();}
    }
    private static final class Sink implements ComponentSink {
        final Map<String,byte[]> entries=new LinkedHashMap<>();
        public BackupEntry write(String path,InputStream input)throws IOException{byte[] bytes=input.readAllBytes();entries.put(path,bytes);return new BackupEntry(path,bytes.length,sha(bytes));}
        public BackupEntry writeGenerated(String path,EntryWriter writer)throws IOException{var output=new ByteArrayOutputStream();writer.write(output);return write(path,new ByteArrayInputStream(output.toByteArray()));}
    }
}
