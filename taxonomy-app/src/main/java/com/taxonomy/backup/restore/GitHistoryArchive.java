package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.workspace.backup.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;

/** Verified original Git payload, held privately until a durable target allocation is prepared. */
public final class GitHistoryArchive implements AutoCloseable {
    private static final BackupComponentId OWNER = new BackupComponentId("workspace");
    private static final int BUFFER_BYTES = 8_192;
    private static final int MAX_HEADER_LINE_BYTES = 4_096;
    private final Repository payload;
    private final Path directory;
    private final GitHistoryRestore.Source source;
    private final GitHistoryRestore.PreparedSource prepared;
    private boolean closed;

    private GitHistoryArchive(Repository payload, Path directory, GitHistoryRestore.Source source,
                              GitHistoryRestore.PreparedSource prepared) {
        this.payload=payload;this.directory=directory;this.source=source;this.prepared=prepared;
    }

    /** No archive pathname is extracted or used to open a repository, config, hook, or external object source. */
    public static GitHistoryArchive open(VerifiedBackup archive, BackupManifest.Repository repository,
            Path privateSpool, GitHistoryCapture.Limits limits, BackupCheckpoint checkpoint) throws IOException {
        Repository payload=null;Path directory=null;
        try {
            Objects.requireNonNull(archive);Objects.requireNonNull(repository);Objects.requireNonNull(limits);
            BackupCheckpoint check=checked(checkpoint);check.check();
            if(!archive.manifest().request().profile().includesHistory()
                    ||!archive.manifest().repositories().contains(repository)
                    ||repository.representation()==GitRepresentation.NONE||repository.sourceRepositoryId()!=null||repository.sourceCommit()!=null)
                throw invalid();
            var metadata=metadata(archive,repository,limits,check);
            var source=new GitHistoryRestore.Source(new GitReflogProvenance.Origin(archive.archiveSha256(),repository.id(),repository.archiveId()),
                    metadata.repository.source(),metadata.repository.backupRefs(),metadata.repository.symbolicRefs(),metadata.logs,metadata.originals,
                    metadata.repository.objectCount());
            var entries=entries(archive,repository,check);
            var paths=container(repository,entries,check);
            Path spool=privateSpool.toAbsolutePath().normalize();
            if(!Files.isDirectory(spool,LinkOption.NOFOLLOW_LINKS)||!spool.toRealPath().equals(spool))throw invalid();
            directory=Files.createTempDirectory(spool,"history-");
            if(Files.getFileStore(directory).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
            payload=new FileRepositoryBuilder().setGitDir(directory.resolve("payload.git").toFile()).setBare().build();
            payload.create(true);check.check();
            Path pack=directory.resolve("verified.pack");
            var inspection=pack(archive,paths.pack,pack,payload,source,repository.representation(),limits,check);
            // Only the private file repository receives verified pack bytes. The database target does not exist yet.
            try(var input=new BufferedInputStream(Files.newInputStream(pack),BUFFER_BYTES);var inserter=payload.newObjectInserter()) {
                var parser=inserter.newPackParser(checking(input,check));
                parser.setAllowThin(false);parser.setObjectChecking(true);parser.setCheckEofAfterPackFooter(true);
                parser.setMaxObjectSizeLimit(limits.maxObjectBytes());
                parser.parse(NullProgressMonitor.INSTANCE);inserter.flush();check.check();
                if(parser.getObjectCount()!=inspection.objectCount())throw invalid();
            }
            var prepared=GitHistoryRestore.prepare(payload,source,limits,check);
            if(prepared.objectCount()!=inspection.objectCount()||prepared.expandedBytes()!=inspection.expandedBytes())throw invalid();
            if(repository.representation()==GitRepresentation.BUNDLE) {
                if(metadata.repository.workingCopy()!=null||!Objects.equals(repository.exportedHead(),source.state().refs().get(Constants.HEAD)))throw invalid();
            } else nativeContainer(archive,repository,metadata,source,payload,entries,paths,inspection,limits,check);
            Files.delete(pack);check.check();
            return new GitHistoryArchive(payload,directory,source,prepared);
        } catch(IOException|RuntimeException|Error failure) {
            if(payload!=null)try{payload.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            if(directory!=null)try{remove(directory);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof Error error)throw error;
            IOException sanitized;
            if(failure instanceof InterruptedIOException||Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();sanitized=new InterruptedIOException("Original Git preparation cancelled");
            } else sanitized=invalid();
            for(var cleanup:failure.getSuppressed())sanitized.addSuppressed(cleanup);
            throw sanitized;
        }
    }
    public GitHistoryRestore.Source source() { requireOpen();return source; }
    public GitHistoryRestore.PreparedSource prepared() { requireOpen();return prepared; }
    private void requireOpen(){if(closed)throw new IllegalStateException("Original Git preparation is closed");}
    @Override public void close() throws IOException {
        if(!closed){closed=true;try{payload.close();}finally{remove(directory);}}
    }

    private static final class Metadata {
        GitHistoryCapture.HistoryRecord repository;
        final List<GitHistoryCapture.Reflog> logs=new ArrayList<>();
        final List<GitReflogProvenance.Event> originals=new ArrayList<>();
    }
    private static Metadata metadata(VerifiedBackup archive,BackupManifest.Repository repository,
            GitHistoryCapture.Limits limits,BackupCheckpoint check)throws IOException {
        var result=new Metadata();
        RestoreArchiveRecords.readVariants(archive,OWNER,"data/workspace/git-history-"+repository.archiveId()+".ndjson","git-history",
                Map.of("repository",GitHistoryCapture.HistoryRecord.class,"reflog",GitHistoryCapture.LogRecord.class,
                        "original-reflog",GitHistoryCapture.OriginalLogRecord.class),limits.maxReflogEntries()+1,check,row->{
                    if(row instanceof GitHistoryCapture.HistoryRecord history) {
                        if(result.repository!=null||!repository.archiveId().equals(history.archiveId())
                                ||!repository.captured().equals(history.source())||history.objectCount()<0||history.objectCount()>limits.maxObjects())throw invalid();
                        result.repository=history;
                    } else {
                        if(result.repository==null)throw invalid();
                        if(row instanceof GitHistoryCapture.LogRecord log)result.logs.add(Objects.requireNonNull(log.entry()));
                        else if(row instanceof GitHistoryCapture.OriginalLogRecord log)result.originals.add(Objects.requireNonNull(log.event()));
                        else throw invalid();
                    }
                });
        if(result.repository==null)throw invalid();return result;
    }
    private static Map<String,BackupEntry> entries(VerifiedBackup archive,BackupManifest.Repository repository,BackupCheckpoint check)throws IOException {
        String prefix="repositories/"+repository.archiveId()+"/";var result=new HashMap<String,BackupEntry>();
        var owner=archive.manifest().components().stream().filter(component->component.id().equals(OWNER)).findFirst().orElseThrow(GitHistoryArchive::invalid);
        if(owner.version()!=1)throw invalid();var owned=new HashSet<>(owner.entryPaths());
        for(var entry:archive.manifest().entries()) {
            check.check();if(!entry.path().startsWith(prefix))continue;
            if(!owned.contains(entry.path()))throw invalid();
            result.put(entry.path().substring(prefix.length()),entry);
        }
        if(result.isEmpty())throw invalid();return result;
    }
    private record Container(String pack,String index,String git) { }
    private static Container container(BackupManifest.Repository repository,Map<String,BackupEntry> entries,BackupCheckpoint check)throws IOException {
        if(repository.representation()==GitRepresentation.BUNDLE) {
            if(!entries.keySet().equals(Set.of("history.bundle")))throw invalid();
            return new Container(entries.get("history.bundle").path(),null,null);
        }
        String git=repository.representation()==GitRepresentation.WORKTREE?".git/":"";
        String pack=null;
        for(String path:entries.keySet()) {
            check.check();
            if(path.startsWith(git+"objects/pack/")&&path.endsWith(".pack")) {
                if(pack!=null||!path.substring((git+"objects/pack/").length()).matches("pack-[0-9a-f]{40}\\.pack"))throw invalid();
                pack=path;
            }
        }
        if(pack==null)throw invalid();String index=pack.substring(0,pack.length()-5)+".idx";
        if(!entries.containsKey(index))throw invalid();return new Container(entries.get(pack).path(),index,git);
    }
    private static GitPackVerification.Result pack(VerifiedBackup archive,String path,Path destination,Repository payload,
            GitHistoryRestore.Source source,GitRepresentation representation,GitHistoryCapture.Limits limits,BackupCheckpoint check)throws IOException {
        var guard=new EntryGuard(check);
        try(var input=new BufferedInputStream(archive.openEntry(path,guard),BUFFER_BYTES);var output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)) {
            try {
                if(representation==GitRepresentation.BUNDLE)bundleHeader(input,source,limits,check);
                var tee=new FilterInputStream(input) {
                    @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&0xff;}
                    @Override public int read(byte[] b,int offset,int length)throws IOException {
                        check.check();int count=in.read(b,offset,Math.min(length,BUFFER_BYTES));
                        if(count>0)output.write(b,offset,count);check.check();return count;
                    }
                };
                var result=GitPackVerification.inspect(payload,tee,source.objectCount(),limits,check);
                output.flush();check.check();return result;
            } catch(IOException|RuntimeException|Error failure) {
                // An invalid pack/header must not cause CheckedInput.close() to drain a rejected large payload.
                guard.abandoned=true;throw failure;
            }
        }
    }
    private static void bundleHeader(InputStream input,GitHistoryRestore.Source source,GitHistoryCapture.Limits limits,BackupCheckpoint check)throws IOException {
        if(!line(input,check).equals("# v2 git bundle"))throw invalid();var actual=new HashMap<String,String>();
        for(;;) {
            check.check();String line=line(input,check);if(line.isEmpty())break;
            if(actual.size()>=limits.maxRefs()||line.length()<42||line.charAt(40)!=' '||!line.substring(0,40).matches("[0-9a-f]{40}"))throw invalid();
            if(actual.putIfAbsent(line.substring(41),line.substring(0,40))!=null)throw invalid();
        }
        if(!actual.equals(refs(source)))throw invalid();
    }
    private static String line(InputStream input,BackupCheckpoint check)throws IOException {
        var bytes=new ByteArrayOutputStream();
        for(;;) {
            check.check();int value=input.read();if(value<0||value=='\r'||value==0)throw invalid();
            if(value=='\n')break;if(bytes.size()>=MAX_HEADER_LINE_BYTES)throw invalid();bytes.write(value);
        }
        var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    private static Map<String,String> refs(GitHistoryRestore.Source source) {
        var result=new TreeMap<>(source.state().refs());result.putAll(source.backupRefs());return result;
    }

    private static void nativeContainer(VerifiedBackup archive,BackupManifest.Repository repository,Metadata metadata,
            GitHistoryRestore.Source source,Repository payload,Map<String,BackupEntry> entries,Container paths,
            GitPackVerification.Result inspection,GitHistoryCapture.Limits limits,BackupCheckpoint check)throws IOException {
        boolean working=repository.representation()==GitRepresentation.WORKTREE;String git=paths.git;var expected=new HashSet<String>();
        require(entries,expected,git+"refs/",out->{ });
        require(entries,expected,git+"config",out->text(out,"[core]\n\trepositoryformatversion = 0\n\tbare = "+!working
                +"\n\tfilemode = false\n\tlogallrefupdates = true\n\tautocrlf = false\n"));
        var selected=metadata.repository.workingCopy();
        if(selected!=null&&!working)throw invalid();
        String selectedHead=selected==null?source.state().refs().get(Constants.HEAD):selected.refs().get(selected.symbolicHead());
        String symbolic=selected==null?source.state().symbolicHead():selected.symbolicHead();
        if(!Objects.equals(repository.exportedHead(),selectedHead))throw invalid();
        if(selected!=null) {
            if(symbolic==null||!symbolic.startsWith(Constants.R_HEADS)||!Repository.isValidRefName(symbolic)
                    ||!Objects.equals(source.state().refs().get(symbolic),selectedHead)
                    ||!selected.refs().equals(selectedHead==null?Map.of():Map.of(symbolic,selectedHead))
                    ||!selected.requiredCommits().equals(selectedHead==null?Set.of():Set.of(selectedHead))
                    ||!source.state().workingStates().entrySet().containsAll(selected.workingStates().entrySet())
                    ||selected.workingStates().size()>1
                    ||!selected.workingStates().isEmpty()&&!selected.workingStates().containsKey(symbolic.substring(Constants.R_HEADS.length())))throw invalid();
        }
        String head=symbolic!=null?"ref: "+symbolic:selectedHead;if(head==null)throw invalid();
        require(entries,expected,git+"HEAD",out->text(out,head+"\n"));
        require(entries,expected,git+"packed-refs",out->{
            text(out,"# pack-refs with: sorted\n");var sorted=new TreeMap<String,String>((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)));
            sorted.putAll(refs(source));for(var ref:sorted.entrySet()) {
                check.check();if(ref.getKey().equals(Constants.HEAD)||source.symbolicRefs().containsKey(ref.getKey()))continue;
                text(out,ref.getValue()+" "+ref.getKey()+"\n");
            }
        });
        for(var alias:source.symbolicRefs().entrySet()){check.check();require(entries,expected,git+alias.getKey(),out->text(out,"ref: "+alias.getValue()+"\n"));}
        var logs=new TreeMap<String,List<GitHistoryCapture.Reflog>>();
        for(var log:source.reflogs())logs.computeIfAbsent(log.ref(),unused->new ArrayList<>()).add(log);
        for(var group:logs.entrySet())require(entries,expected,git+"logs/"+group.getKey(),out->{
            for(var log:group.getValue()) {
                check.check();var who=new PersonIdent(log.name(),log.email(),Instant.ofEpochSecond(log.epochSeconds()),ZoneOffset.ofTotalSeconds(Math.multiplyExact(log.offsetMinutes(),60)));
                text(out,log.oldId()+" "+log.newId()+" "+who.toExternalString()+"\t"+log.message()+"\n");
            }
        });
        String packName=nativePackName(inspection.objectIds());
        String expectedIndex=git+"objects/pack/pack-"+packName+".idx";
        if(!paths.index.equals(expectedIndex))throw invalid();expected.add(expectedIndex.substring(0,expectedIndex.length()-4)+".pack");
        Path installed=payload.getDirectory().toPath().resolve("objects/pack/pack-"+packName+".idx");
        if(Files.isRegularFile(installed,LinkOption.NOFOLLOW_LINKS))
            require(entries,expected,expectedIndex,out->{try(var input=Files.newInputStream(installed)){copy(input,out,check);}});
        else if(inspection.objectCount()==0)require(entries,expected,expectedIndex,out->emptyPackIndex(out,inspection.packChecksum()));
        else throw invalid();
        if(working) {
            var tree=selectedHead==null?null:GitTreeCapture.capture(payload,selectedHead,
                    new GitTreeCapture.Limits(BackupLimits.MAX_ITEMS,limits.maxObjectBytes(),limits.maxTotalBytes()),check);
            List<GitTreeCapture.File> files=tree==null?List.of():tree.files();
            require(entries,expected,git+"index",out->{if(tree==null)GitTreeCapture.writeEmptyIndex(out,check);else tree.writeIndex(out,check);});
            var originals=new HashMap<String,GitTreeCapture.File>();for(var file:files)originals.put(file.path(),file);
            var raw=selected==null||selected.workingStates().isEmpty()?null:workingProof(archive,repository,selected,check);
            for(var file:files) {
                check.check();if(raw!=null&&file.path().equals("architecture.taxdsl"))continue;
                var entry=entries.get(file.path());if(entry==null||entry.length()!=file.length()||!entry.sha256().equals(file.sha256()))throw invalid();expected.add(file.path());
            }
            if(raw!=null) {
                var entry=entries.get("architecture.taxdsl");
                if(entry==null||entry.length()>16L*1024*1024||!raw.sha256().equals(entry.sha256()))throw invalid();
                var original=originals.get("architecture.taxdsl");
                if(raw.state().semanticRevision()==raw.state().checkpointRevision()
                        &&!raw.sha256().equals(original==null?hex(sha256().digest()):original.sha256()))throw invalid();
                expected.add("architecture.taxdsl");
            }
        }
        if(!entries.keySet().equals(expected))throw invalid();check.check();
    }
    private static WorkspaceGitHistoryReferences.WorkingCopyProof workingProof(VerifiedBackup archive,BackupManifest.Repository repository,
            SnapshotContext.RepositoryState selected,BackupCheckpoint check)throws IOException {
        String branch=selected.symbolicHead().substring(Constants.R_HEADS.length());
        var matching=new ArrayList<WorkspaceGitHistoryReferences.WorkingCopyProof>();var seen=new HashSet<SourceRecordId>();
        RestoreArchiveRecords.read(archive,OWNER,"data/workspace/git-working-copy-proofs.ndjson","git-working-copy-proofs",
                WorkspaceGitHistoryReferences.WorkingCopyProof.class,BackupLimits.MAX_ITEMS,check,row->{
                    if(!seen.add(row.sourceId()))throw invalid();
                    if(row.repository().equals(repository.id())&&row.branch().equals(branch))matching.add(row);
                });
        if(matching.size()!=1||!matching.getFirst().state().equals(selected.workingStates().get(branch))
                ||!Objects.equals(matching.getFirst().state().checkpointCommit(),selected.refs().get(selected.symbolicHead())))throw invalid();
        return matching.getFirst();
    }
    private static String nativePackName(Set<String> ids) {
        var digest=sha1();for(String id:new TreeSet<>(ids))digest.update(HexFormat.of().parseHex(id));return hex(digest.digest());
    }
    /** Standard empty pack index v2; JGit discards an empty received pack rather than writing this optional file. */
    private static void emptyPackIndex(OutputStream output,String packChecksum)throws IOException {
        var digest=sha1();var bytes=new DataOutputStream(new DigestOutputStream(output,digest));
        bytes.writeInt(0xff744f63);bytes.writeInt(2);for(int i=0;i<256;i++)bytes.writeInt(0);
        bytes.write(HexFormat.of().parseHex(packChecksum));bytes.flush();output.write(digest.digest());
    }
    @FunctionalInterface private interface Writer {void write(OutputStream output)throws IOException;}
    private static void require(Map<String,BackupEntry> entries,Set<String> expected,String path,Writer writer)throws IOException {
        var entry=entries.get(path);if(entry==null||!expected.add(path))throw invalid();
        var output=new Fingerprint(entry.length());writer.write(output);
        if(output.length!=entry.length()||!hex(output.digest.digest()).equals(entry.sha256()))throw invalid();
    }
    private static final class Fingerprint extends OutputStream {
        final MessageDigest digest=sha256();final long maximum;long length;
        Fingerprint(long maximum){this.maximum=maximum;}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] bytes,int offset,int count)throws IOException {
            if(count>maximum-length)throw invalid();digest.update(bytes,offset,count);length+=count;
        }
    }
    private static final class EntryGuard implements BackupCheckpoint {
        final BackupCheckpoint check;boolean abandoned;
        EntryGuard(BackupCheckpoint check){this.check=check;}
        @Override public void check()throws IOException{if(abandoned)throw invalid();check.check();}
    }
    private static BackupCheckpoint checked(BackupCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint);return ()->{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Original Git preparation cancelled");checkpoint.check();};
    }
    private static InputStream checking(InputStream input,BackupCheckpoint check) {
        return new FilterInputStream(input) {
            @Override public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&0xff;}
            @Override public int read(byte[] bytes,int offset,int length)throws IOException {
                check.check();int count=in.read(bytes,offset,Math.min(length,BUFFER_BYTES));check.check();return count;
            }
        };
    }
    private static void copy(InputStream input,OutputStream output,BackupCheckpoint check)throws IOException {
        byte[] bytes=new byte[BUFFER_BYTES];for(;;){check.check();int count=input.read(bytes);if(count<0)break;output.write(bytes,0,count);check.check();}
    }
    private static void text(OutputStream output,String value)throws IOException{output.write(value.getBytes(StandardCharsets.UTF_8));}
    private static String hex(byte[] value){return HexFormat.of().formatHex(value);}
    private static MessageDigest sha256(){return digest("SHA-256");}
    private static MessageDigest sha1(){return digest("SHA-1");}
    private static MessageDigest digest(String algorithm){try{return MessageDigest.getInstance(algorithm);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static IOException invalid(){return new IOException("Invalid original Git archive evidence");}
    private static void remove(Path directory)throws IOException {
        boolean interrupted=Thread.interrupted();
        try {
            Files.walkFileTree(directory,EnumSet.noneOf(FileVisitOption.class),32,new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult postVisitDirectory(Path path,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(path);return FileVisitResult.CONTINUE;}
            });
        } finally{if(interrupted)Thread.currentThread().interrupt();}
    }
}
