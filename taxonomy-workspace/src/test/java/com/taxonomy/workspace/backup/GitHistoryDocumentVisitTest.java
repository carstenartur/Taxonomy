package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GitHistoryDocumentVisitTest {
    private static final BackupRepositoryKey KEY=new BackupRepositoryKey("original-repository","original-workspace");
    private static final BackupCheckpoint CHECK=()->{};
    @TempDir Path temporary;

    @Test void exactAncestorAndDatabaseOnlyDocumentBytesAreVisitedWithoutProjection() throws Exception {
        try(var git=repository()) {
            String original="Ursprünglicher Text äöü 🐦\n";
            String first=commit(git,original,"first");
            String head=commit(git,"CURRENT\n","current");
            git.checkout().setCreateBranch(true).setName("deleted").setStartPoint(first).call();
            String orphan=commit(git,"DATABASE ONLY\n","orphan");
            git.checkout().setName("main").call();
            git.branchDelete().setBranchNames("deleted").setForce(true).call();
            var capture=capture(git,Set.of(orphan));
            var expected=Map.of(first,original,head,"CURRENT\n",orphan,"DATABASE ONLY\n");
            var visited=new HashMap<String,String>();
            var result=capture.visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(3,256,1024),CHECK,(reference,bytes)->{
                assertThat(reference.repository()).isEqualTo(KEY);
                assertThat(reference.path()).isEqualTo("architecture.taxdsl");
                assertThat(reference.sha256()).isEqualTo(hash(bytes));
                assertThat(visited.put(reference.commit(),new String(bytes,StandardCharsets.UTF_8))).isNull();
            });
            assertThat(visited).isEqualTo(expected);
            assertThat(result.documents()).isEqualTo(3);
            assertThat(result.bytes()).isEqualTo(expected.values().stream().mapToLong(s->s.getBytes(StandardCharsets.UTF_8).length).sum());
            assertThat(git.getRepository().resolve("refs/heads/main").name()).isEqualTo(head);
            assertThat(git.getRepository().exactRef("refs/heads/deleted")).isNull();
        }
    }

    @Test void individualBodyBoundIsCheckedBeforeTheCallback() throws Exception {
        try(var git=repository()) {
            commit(git,"x".repeat(4096),"large"); var capture=capture(git,Set.of());
            var callbacks=new AtomicInteger();
            assertThatThrownBy(()->capture.visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(10,4095,8192),CHECK,
                    (reference,bytes)->callbacks.incrementAndGet())).isInstanceOf(IOException.class);
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test void duplicateBlobOccurrencesConsumeTheSharedVisitedByteBudget() throws Exception {
        try(var git=repository()) {
            commit(git,"same original body","one"); commit(git,"same original body","two");
            var capture=capture(git,Set.of()); var received=new AtomicInteger();
            assertThatThrownBy(()->capture.visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(2,100,35),CHECK,
                    (reference,bytes)->received.addAndGet(bytes.length))).isInstanceOf(IOException.class);
            assertThat(received.get()).isLessThanOrEqualTo(35);
        }
    }

    @Test void documentCountIsBoundedBeforeAnyBodyIsExposed() throws Exception {
        try(var git=repository()) {
            commit(git,"one","one"); commit(git,"two","two"); var capture=capture(git,Set.of());
            var callbacks=new AtomicInteger();
            assertThatThrownBy(()->capture.visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(1,100,200),CHECK,
                    (reference,bytes)->callbacks.incrementAndGet())).isInstanceOf(IOException.class);
            assertThat(callbacks).hasValue(0);
        }
    }

    @Test void cancellationAfterAConsumerPreventsFurtherBodyExposure() throws Exception {
        try(var git=repository()) {
            commit(git,"one","one"); commit(git,"two","two"); var capture=capture(git,Set.of());
            var cancelled=new AtomicBoolean(); var callbacks=new AtomicInteger();
            BackupCheckpoint checkpoint=()->{if(cancelled.get())throw new IOException("cancelled");};
            assertThatThrownBy(()->capture.visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(2,100,200),checkpoint,
                    (reference,bytes)->{callbacks.incrementAndGet();cancelled.set(true);})).isInstanceOf(IOException.class);
            assertThat(callbacks).hasValue(1);
        }
    }

    @Test void sourceMovementInsideTheCallbackCannotCompleteAVisit() throws Exception {
        try(var git=repository()) {
            commit(git,"original","one"); var capture=capture(git,Set.of());
            assertThatThrownBy(()->capture.visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(1,100,200),CHECK,
                    (reference,bytes)->{
                        try {commit(git,"moved","later");} catch(Exception failure){throw new IOException(failure);}
                    })).isInstanceOf(IOException.class);
        }
    }

    @Test void emptyRepositoryFitsAnExhaustedDocumentBudget() throws Exception {
        try(var git=repository()) {
            var result=capture(git,Set.of()).visitDocuments(KEY,new GitHistoryCapture.DocumentLimits(0,100,0),CHECK,
                    (reference,bytes)->{throw new AssertionError("empty repository has no document");});
            assertThat(result).isEqualTo(new GitHistoryCapture.DocumentVisit(0,0));
        }
    }

    private Git repository() throws Exception {
        return Git.init().setDirectory(temporary.resolve("source").toFile()).setInitialBranch("main").call();
    }
    private static GitHistoryCapture capture(Git git,Set<String> commits)throws IOException {
        return GitHistoryCapture.capture(git.getRepository(),commits,Map.of(),GitHistoryCapture.Limits.defaults(),CHECK);
    }
    private static String commit(Git git,String text,String message)throws Exception {
        Files.writeString(git.getRepository().getWorkTree().toPath().resolve("architecture.taxdsl"),text,StandardCharsets.UTF_8);
        git.add().addFilepattern("architecture.taxdsl").call();
        var author=new PersonIdent("Original Author","original@example.invalid");
        return git.commit().setMessage(message).setAuthor(author).setCommitter(author).setAllowEmpty(true).call().name();
    }
    private static String hash(byte[] bytes)throws IOException {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
