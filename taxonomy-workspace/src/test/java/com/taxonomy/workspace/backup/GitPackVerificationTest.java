package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GitPackVerificationTest {
    private static final BackupCheckpoint CHECK=()->{};
    @TempDir Path temporary;

    @Test void actualPortableNativePackIsVerifiedWithoutCreatingContextObjectsOrRefs()throws Exception {
        var pack=pack();
        try(var context=Git.init().setBare(true).setDirectory(temporary.resolve("context.git").toFile()).call()) {
            var result=GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(pack.bytes),pack.count,GitHistoryCapture.Limits.defaults(),CHECK);
            assertThat(result.objectCount()).isEqualTo(pack.count);
            assertThat(result.objectIds()).hasSize(pack.count).contains(pack.head);
            assertThat(result.expandedBytes()).isGreaterThan(131_072);
            assertThat(result.packChecksum()).isEqualTo(HexFormat.of().formatHex(Arrays.copyOfRange(pack.bytes,pack.bytes.length-20,pack.bytes.length)));
            assertThat(context.getRepository().getRefDatabase().getRefsByPrefix("refs/")).isEmpty();
            try(var reader=context.getRepository().newObjectReader()){assertThat(reader.has(ObjectId.fromString(pack.head))).isFalse();}
        }
    }

    @Test void corruptionTruncationExtraBytesAndWrongObjectCountsAreRejected()throws Exception {
        var pack=pack();var changed=pack.bytes.clone();changed[changed.length-1]^=1;
        try(var context=Git.init().setBare(true).setDirectory(temporary.resolve("reject.git").toFile()).call()) {
            for(var bytes:List.of(changed,Arrays.copyOf(pack.bytes,pack.bytes.length-1),Arrays.copyOf(pack.bytes,pack.bytes.length+1)))
                assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(bytes),pack.count,GitHistoryCapture.Limits.defaults(),CHECK)).isInstanceOf(IOException.class);
            assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(pack.bytes),pack.count+1,GitHistoryCapture.Limits.defaults(),CHECK)).isInstanceOf(IOException.class);
            assertThat(context.getRepository().getRefDatabase().getRefsByPrefix("refs/")).isEmpty();
        }
    }

    @Test void countAndDeclaredMetadataSizeAreBoundedBeforeInflationOrLargeAllocation()throws Exception {
        try(var context=Git.init().setBare(true).setDirectory(temporary.resolve("bounded.git").toFile()).call()) {
            byte[] hugeCount=ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(Integer.MAX_VALUE).array();
            assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(hugeCount),1,GitHistoryCapture.Limits.defaults(),CHECK)).isInstanceOf(IOException.class);
            for(int type:List.of(Constants.OBJ_COMMIT,Constants.OBJ_TREE,Constants.OBJ_TAG)) {
                long length=type==Constants.OBJ_TREE?4L*1_048_576+1:1_048_577;
                byte[] headerOnly=singleObject(type,length,new byte[0]);
                assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(headerOnly),1,GitHistoryCapture.Limits.defaults(),CHECK))
                        .isInstanceOf(IOException.class).hasMessageContaining("byte limit");
            }
        }
    }

    @Test void wholeClosureAndIndividualObjectsHaveIndependentExpandedByteLimits()throws Exception {
        var pack=pack();
        try(var context=Git.init().setBare(true).setDirectory(temporary.resolve("limits.git").toFile()).call()) {
            for(var limits:List.of(new GitHistoryCapture.Limits(100,100,131_071,1_000_000,100),new GitHistoryCapture.Limits(100,100,1_000_000,131_072,100)))
                assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(pack.bytes),pack.count,limits,CHECK))
                        .isInstanceOf(IOException.class).hasMessageContaining("byte limit");
        }
    }

    @Test void deltaObjectsCannotBypassTheDeclaredWholeObjectBudget()throws Exception {
        try(var context=Git.init().setBare(true).setDirectory(temporary.resolve("delta.git").toFile()).call()) {
            byte[] refDelta=singleObject(Constants.OBJ_REF_DELTA,0,new byte[20]);
            assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),new ByteArrayInputStream(refDelta),1,GitHistoryCapture.Limits.defaults(),CHECK))
                    .isInstanceOf(IOException.class).hasMessageContaining("delta");
        }
    }

    @Test void cancellationDuringRealPackInputRetainsInterruptionAndWritesNothing()throws Exception {
        var pack=pack();var reads=new java.util.concurrent.atomic.AtomicInteger();
        var input=new ByteArrayInputStream(pack.bytes){@Override public synchronized int read(byte[] bytes,int offset,int length){int count=super.read(bytes,offset,length);if(count>0)reads.addAndGet(count);return count;}};
        try(var context=Git.init().setBare(true).setDirectory(temporary.resolve("cancel.git").toFile()).call()) {
            try {
                assertThatThrownBy(()->GitPackVerification.inspect(context.getRepository(),input,pack.count,GitHistoryCapture.Limits.defaults(),()->{
                    if(reads.get()>0)throw new InterruptedIOException("cancelled after actual input");
                })).isInstanceOf(InterruptedIOException.class);
                assertThat(reads.get()).isPositive();assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {Thread.interrupted();}
            try(var reader=context.getRepository().newObjectReader()){assertThat(reader.has(ObjectId.fromString(pack.head))).isFalse();}
        }
    }

    private Pack pack()throws Exception {
        Path source=temporary.resolve("source-"+UUID.randomUUID());
        try(var git=Git.init().setInitialBranch("main").setDirectory(source.toFile()).call()) {
            var content=new byte[131_072];new Random(17).nextBytes(content);Files.write(source.resolve("ordinary.bin"),content);
            Files.writeString(source.resolve("architecture.taxdsl"),"ORIGINAL");git.add().addFilepattern(".").call();
            var author=new PersonIdent("Original","source@example.invalid");
            String head=git.commit().setAuthor(author).setCommitter(author).setMessage("Original pack source").call().name();
            var capture=GitHistoryCapture.capture(git.getRepository(),Set.of(),Map.of(),GitHistoryCapture.Limits.defaults(),CHECK);
            var sink=new Sink();capture.write("native",GitRepresentation.BARE,sink);
            byte[] bytes=sink.entries.entrySet().stream().filter(entry->entry.getKey().endsWith(".pack")).findFirst().orElseThrow().getValue();
            return new Pack(bytes,capture.objectCount(),head);
        }
    }
    private static byte[] singleObject(int type,long length,byte[] content)throws Exception {
        var output=new ByteArrayOutputStream();output.write(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(1).array());
        int first=(type<<4)|(int)(length&15);length>>>=4;output.write(first|(length==0?0:128));
        while(length!=0){int next=(int)(length&127);length>>>=7;output.write(next|(length==0?0:128));}
        output.write(content);output.write(MessageDigest.getInstance("SHA-1").digest(output.toByteArray()));return output.toByteArray();
    }
    private record Pack(byte[] bytes,int count,String head) { }
    private static final class Sink implements ComponentSink {
        final Map<String,byte[]> entries=new HashMap<>();
        public BackupEntry write(String path,InputStream input)throws IOException {byte[] bytes=input.readAllBytes();entries.put(path,bytes);return new BackupEntry(path,bytes.length,GitReflogProvenance.digest(bytes));}
        public BackupEntry writeGenerated(String path,EntryWriter writer)throws IOException {var output=new ByteArrayOutputStream();writer.write(output);return write(path,new ByteArrayInputStream(output.toByteArray()));}
    }
}
