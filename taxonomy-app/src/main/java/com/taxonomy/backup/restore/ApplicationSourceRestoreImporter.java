package com.taxonomy.backup.restore;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.provenance.backup.ApplicationBackupContributor.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Typed source owner import into an already migrated, isolated inactive generation. Never activates it. */
public final class ApplicationSourceRestoreImporter {
    private static final BackupComponentId OWNER=new BackupComponentId("application");
    private static final int MAX_ROWS=100_000, MAX_MODEL_BYTES=32*1024*1024;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final Path activeRoot,targetRoot;
    public ApplicationSourceRestoreImporter(Path activeRoot,Path targetRoot) {
        this.activeRoot=Objects.requireNonNull(activeRoot);this.targetRoot=Objects.requireNonNull(targetRoot);
    }
    public record Result(Map<SourceRecordId,Long> artifactIds,Map<SourceRecordId,Long> versionIds,
                         Map<SourceRecordId,Long> fragmentIds,Set<SourceRecordId> quarantinedLegacyLinks) {
        public Result {artifactIds=Map.copyOf(artifactIds);versionIds=Map.copyOf(versionIds);
            fragmentIds=Map.copyOf(fragmentIds);quarantinedLegacyLinks=Set.copyOf(quarantinedLegacyLinks);}
    }
    public Result restore(VerifiedBackup archive,RestoreTargetGeneration generation,BackupCheckpoint checkpoint)throws IOException {
        checkpoint.check(); Path active=activeRoot.toRealPath(),target=targetRoot.toRealPath();
        if(target.startsWith(active)||active.startsWith(target))throw new IOException("Source content target is not isolated");
        if(Files.isSymbolicLink(targetRoot)||Files.isSymbolicLink(activeRoot))throw invalid();
        var rootKey=directoryKey(target);
        var model=inspect(archive,checkpoint); // All owner records and ancestry are checked before SQL or file writes.
        generation.requireUnchanged(checkpoint);
        try(var directory=Files.newDirectoryStream(target)) {
            if(!(directory instanceof SecureDirectoryStream<Path> secure))throw new IOException("Safe staged source storage is unavailable");
            if(!rootKey.equals(secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey()))
                throw new IOException("Source target directory binding changed");
            requireDirectory(target,rootKey);
            try(var connection=generation.dataSource().getConnection()) {
                // Prove the actual write connection, then pin it for the entire transaction.
                generation.requireConnection(connection,checkpoint);
                connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try(var rollback=new Rollback(connection)) {
                    String generationId=generation.generationId().toString(), rootHash=StableIdentityHash.sha256(target+"|"+rootKey);
                    var existing=owner(connection,checkpoint);
                    if(existing!=null) {
                        if(!existing.equals(List.of(generationId,archive.archiveSha256(),rootHash)))throw new IOException("Source owner is bound to another restore plan");
                        var restored=resume(connection,model,secure,checkpoint);
                        requireDirectory(target,rootKey);generation.requireConnection(connection,checkpoint);return restored;
                    }
                    requireEmpty(connection,checkpoint);
                    var files=new LinkedHashMap<String,String>();
                    for(var entry:model.files.values()) { checkpoint.check(); files.put(entry.path(),writeFile(archive,entry,secure,checkpoint)); }
                    var artifacts=new LinkedHashMap<SourceRecordId,Long>();var versions=new LinkedHashMap<SourceRecordId,Long>();var fragments=new LinkedHashMap<SourceRecordId,Long>();
                    for(var record:model.artifacts.values()) {
                        checkpoint.check(); long id=insert(connection,"source_artifact",
                                "source_type,title,canonical_identifier,canonical_url,origin_system,author,description,language,created_at,updated_at",
                                record.type().name(),record.title(),record.canonicalIdentifier(),record.canonicalUrl(),record.originSystem(),record.author(),record.description(),record.language(),timestamp(record.createdAt()),timestamp(record.updatedAt()));
                        artifacts.put(record.sourceId(),id);
                    }
                    for(var record:model.versions.values()) {
                        checkpoint.check(); long id=insert(connection,"source_version",
                                "source_artifact_id,version_label,retrieved_at,effective_date,content_hash,mime_type,storage_location,raw_text_location",
                                artifacts.get(record.artifactId()),record.versionLabel(),timestamp(record.retrievedAt()),date(record.effectiveDate()),record.contentHash(),record.mimeType(),
                                record.original()==null?null:files.get(record.original().path()),record.rawText()==null?null:files.get(record.rawText().path()));
                        versions.put(record.sourceId(),id);
                    }
                    for(var record:model.fragments.values()) {
                        checkpoint.check(); long id=insert(connection,"source_fragment",
                                "source_version_id,section_path,paragraph_ref,page_from,page_to,fragment_text,fragment_hash,chunk_level",
                                versions.get(record.versionId()),record.sectionPath(),record.paragraphReference(),record.pageFrom(),record.pageTo(),record.text(),record.contentHash(),record.chunkLevel());
                        fragments.put(record.sourceId(),id);
                    }
                    for(var record:model.fragments.values())if(record.parentId()!=null) {
                        checkpoint.check(); update(connection,"update source_fragment set parent_fragment_id=? where id=?",fragments.get(record.parentId()),fragments.get(record.sourceId()));
                    }
                    saveMappings(connection,artifacts,checkpoint);saveMappings(connection,versions,checkpoint);saveMappings(connection,fragments,checkpoint);
                    for(var record:model.legacy.values()) {
                        checkpoint.check();String json=JSON.writeValueAsString(record);
                        // No reviewed target-global namespace mapping exists. Preserve private data, never attach it to an arbitrary tenant.
                        update(connection,"insert into restore_source_quarantine(record_kind,source_value,record_json,sha256,reason) values(?,?,?,?,?)",
                                record.sourceId().kind(),record.sourceId().value(),json,StableIdentityHash.sha256(json),"UNMAPPED_LEGACY_GLOBAL_NAMESPACE");
                    }
                    for(var entry:model.files.values()) {
                        checkpoint.check();update(connection,"insert into restore_source_file(entry_path,content_name,length,sha256) values(?,?,?,?)",entry.path(),files.get(entry.path()),entry.length(),entry.sha256());
                    }
                    update(connection,"insert into restore_source_owner(owner_id,generation_id,archive_sha256,content_root_sha256) values('sources',?,?,?)",generationId,archive.archiveSha256(),rootHash);
                    checkpoint.check();requireDirectory(target,rootKey);
                    try(var directoryChannel=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){directoryChannel.force(true);}
                    requireDirectory(target,rootKey);generation.requireConnection(connection,checkpoint);connection.commit();rollback.committed=true;
                    checkpoint.check();
                    // Outer restore journal may crash after this commit. This owner's rows and mappings are already atomic.
                    return new Result(artifacts,versions,fragments,model.legacy.keySet());
                }
            }
        } catch(InterruptedIOException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        catch(SQLException|RuntimeException failure){throw new IOException("Staged source owner import failed");}
    }

    private static Model inspect(VerifiedBackup archive,BackupCheckpoint checkpoint)throws IOException {
        if(archive.manifest().components().stream().noneMatch(component->component.id().equals(OWNER)&&component.version()==1))throw invalid();
        var model=new Model();
        read(archive,"source-artifact",ArtifactRecord.class,checkpoint,record->{
            id(record.sourceId(),"source-artifact");required(record.title(),500);Objects.requireNonNull(record.type());required(record.createdAt(),100);
            length(record.canonicalIdentifier(),500);length(record.canonicalUrl(),1000);length(record.originSystem(),200);length(record.author(),200);
            length(record.description(),2000);length(record.language(),10);timestamp(record.createdAt());timestamp(record.updatedAt());model.add(model.artifacts,record.sourceId(),record);
        });
        read(archive,"source-version",VersionRecord.class,checkpoint,record->{
            id(record.sourceId(),"source-version");id(record.artifactId(),"source-artifact");required(record.retrievedAt(),100);timestamp(record.retrievedAt());date(record.effectiveDate());
            length(record.versionLabel(),200);length(record.contentHash(),128);length(record.mimeType(),100);
            if(record.originalAvailability()==null || (record.originalAvailability()==ContentAvailability.INCLUDED)!=(record.original()!=null))throw invalid();
            if(record.original()!=null && (record.contentHash()==null || !record.contentHash().matches("[a-fA-F0-9]{64}") || !record.contentHash().equalsIgnoreCase(record.original().sha256())))throw invalid();
            entry(archive,model,record.original());entry(archive,model,record.rawText());model.add(model.versions,record.sourceId(),record);
        });
        read(archive,"source-fragment",FragmentRecord.class,checkpoint,record->{
            id(record.sourceId(),"source-fragment");id(record.versionId(),"source-version");if(record.parentId()!=null)id(record.parentId(),"source-fragment");
            length(record.sectionPath(),500);length(record.paragraphReference(),100);length(record.text(),4000);length(record.contentHash(),128);
            integer(record.pageFrom());integer(record.pageTo());integer(record.chunkLevel());model.add(model.fragments,record.sourceId(),record);
        });
        read(archive,"legacy-source-link",LegacyLinkRecord.class,checkpoint,record->{
            id(record.sourceId(),"legacy-source-link");id(record.artifactId(),"source-artifact");if(record.versionId()!=null)id(record.versionId(),"source-version");if(record.fragmentId()!=null)id(record.fragmentId(),"source-fragment");
            required(record.legacyRequirementKey(),255);length(record.note(),1000);Objects.requireNonNull(record.type());
            if(record.confidence()!=null && !Double.isFinite(record.confidence()))throw invalid();model.add(model.legacy,record.sourceId(),record);
        });
        for(var version:model.versions.values()) {checkpoint.check();if(!model.artifacts.containsKey(version.artifactId()))throw invalid();}
        var completed=new HashSet<SourceRecordId>();
        for(var fragment:model.fragments.values()) {
            checkpoint.check();if(!model.versions.containsKey(fragment.versionId()))throw invalid();
            var path=new HashSet<SourceRecordId>();var next=fragment;
            while(next!=null&&!completed.contains(next.sourceId())) {
                checkpoint.check();if(!path.add(next.sourceId()))throw new IOException("Invalid source fragment ancestry");
                if(next.parentId()==null)next=null;
                else {var parent=model.fragments.get(next.parentId());if(parent==null||!parent.versionId().equals(fragment.versionId()))throw new IOException("Invalid source fragment ancestry");next=parent;}
            } completed.addAll(path);
        }
        for(var link:model.legacy.values()) {
            checkpoint.check();if(!model.artifacts.containsKey(link.artifactId()))throw invalid();
            var version=link.versionId()==null?null:model.versions.get(link.versionId());var fragment=link.fragmentId()==null?null:model.fragments.get(link.fragmentId());
            if(link.versionId()!=null&&(version==null||!version.artifactId().equals(link.artifactId())))throw invalid();
            if(link.fragmentId()!=null&&(fragment==null||link.versionId()!=null&&!fragment.versionId().equals(link.versionId())
                    ||!model.versions.get(fragment.versionId()).artifactId().equals(link.artifactId())))throw invalid();
        }
        checkpoint.check();return model;
    }
    private static <T extends Record>void read(VerifiedBackup archive,String kind,Class<T> type,BackupCheckpoint checkpoint,RestoreArchiveRecords.Visitor<T> visitor)throws IOException {
        try {RestoreArchiveRecords.read(archive,OWNER,"data/application/"+kind+".ndjson",kind,type,MAX_ROWS,checkpoint,visitor);}
        catch(IllegalArgumentException|NullPointerException failure){throw invalid();}
    }
    private static void entry(VerifiedBackup archive,Model model,BackupEntry entry)throws IOException {
        if(entry==null)return;
        if(!entry.path().matches("files/source-[1-9][0-9]*-(original|text)")||!RestoreArchiveRecords.declares(archive,OWNER,entry.path())
                ||!archive.manifest().entries().contains(entry))throw invalid();
        if(model.files.size()>=MAX_ROWS&&!model.files.containsKey(entry.path()))throw invalid();
        var previous=model.files.putIfAbsent(entry.path(),entry);if(previous!=null&&!previous.equals(entry))throw invalid();
    }
    private static void id(SourceRecordId id,String kind)throws IOException {if(id==null||!id.kind().equals("application."+kind)||id.value().length()>4096)throw invalid();}
    private static void required(String value,int max)throws IOException {if(value==null||value.isBlank())throw invalid();length(value,max);}
    private static void length(String value,int max)throws IOException {if(value!=null&&value.length()>max)throw invalid();}
    private static void integer(Long value)throws IOException {if(value!=null&&(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE))throw invalid();}
    private static Timestamp timestamp(String value)throws IOException {try{return value==null?null:Timestamp.from(Instant.parse(value));}catch(RuntimeException failure){throw invalid();}}
    private static java.sql.Date date(String value)throws IOException {try{return value==null?null:java.sql.Date.valueOf(LocalDate.parse(value));}catch(RuntimeException failure){throw invalid();}}
    private static IOException invalid(){return new IOException("Invalid typed source restore data");}
    private static final class Model {
        final Map<SourceRecordId,ArtifactRecord> artifacts=new LinkedHashMap<>();final Map<SourceRecordId,VersionRecord> versions=new LinkedHashMap<>();
        final Map<SourceRecordId,FragmentRecord> fragments=new LinkedHashMap<>();final Map<SourceRecordId,LegacyLinkRecord> legacy=new LinkedHashMap<>();
        final Map<String,BackupEntry> files=new LinkedHashMap<>();int bytes,rows;
        <T extends Record>void add(Map<SourceRecordId,T> target,SourceRecordId id,T value)throws IOException {
            if(++rows>MAX_ROWS||target.putIfAbsent(id,value)!=null)throw invalid();
            int length=JSON.writeValueAsBytes(value).length;if(length>MAX_MODEL_BYTES-bytes)throw new IOException("Source restore model budget exceeded");bytes+=length;
        }
    }

    private static Object directoryKey(Path directory)throws IOException {
        var attributes=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isDirectory()||attributes.fileKey()==null)throw new IOException("Source target directory binding is unproven");
        return attributes.fileKey();
    }
    private static void requireDirectory(Path directory,Object expected)throws IOException {
        if(!expected.equals(directoryKey(directory)))throw new IOException("Source target directory binding changed");
    }
    private static String writeFile(VerifiedBackup archive,BackupEntry entry,SecureDirectoryStream<Path> directory,BackupCheckpoint checkpoint)throws IOException {
        String name=entry.sha256()+".source";
        try {
            verifyFile(directory,name,entry,checkpoint);return name;
        } catch(NoSuchFileException absent) { /* A new file is published only after its bytes are verified and forced. */ }
        Path temporary=Path.of("."+entry.sha256()+".partial-"+UUID.randomUUID());
        try(var cleanup=new TemporaryFile(directory,temporary)) {
            try(var output=directory.newByteChannel(temporary,Set.of(StandardOpenOption.WRITE,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                // Register the actual file before an independently cancellable archive open can fail.
                cleanup.created=true;
                try(var input=archive.openEntry(entry.path(),checkpoint)) {
                    byte[] bytes=new byte[8192];long count=0;
                    for(;;) {checkpoint.check();int read=input.read(bytes);if(read<0)break;count+=read;
                        var buffer=ByteBuffer.wrap(bytes,0,read);while(buffer.hasRemaining()){checkpoint.check();output.write(buffer);}}
                    if(count!=entry.length())throw invalid();
                    if(!(output instanceof FileChannel file))throw new IOException("Durable staged source storage is unavailable");
                    file.force(true);
                }
            }
            checkpoint.check();directory.move(temporary,directory,Path.of(name));cleanup.published=true;
            verifyFile(directory,name,entry,checkpoint);return name;
        }
    }
    private static final class TemporaryFile implements AutoCloseable {
        private final SecureDirectoryStream<Path> directory;private final Path path;boolean created,published;
        TemporaryFile(SecureDirectoryStream<Path> directory,Path path){this.directory=directory;this.path=path;}
        @Override public void close()throws IOException {if(created&&!published)directory.deleteFile(path);}
    }
    private static void verifyFile(SecureDirectoryStream<Path> directory,String name,BackupEntry entry,BackupCheckpoint checkpoint)throws IOException {
        if(!name.equals(entry.sha256()+".source"))throw invalid();
        try(var input=directory.newByteChannel(Path.of(name),Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            var hash=sha256();var buffer=ByteBuffer.allocate(8192);long count=0;
            for(;;){checkpoint.check();buffer.clear();int read=input.read(buffer);if(read<0)break;count+=read;
                if(count>entry.length())throw new IOException("Staged source file proof changed");buffer.flip();hash.update(buffer);}
            if(count!=entry.length()||!HexFormat.of().formatHex(hash.digest()).equals(entry.sha256()))throw new IOException("Staged source file proof changed");
        }
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static PreparedStatement statement(Connection connection,String sql)throws SQLException {
        var statement=connection.prepareStatement(sql);statement.setMaxRows(MAX_ROWS+1);statement.setQueryTimeout(60);return statement;
    }
    private static void parameters(PreparedStatement statement,Object...values)throws SQLException {for(int index=0;index<values.length;index++)statement.setObject(index+1,values[index]);}
    private static void update(Connection connection,String sql,Object...values)throws SQLException {
        try(var statement=statement(connection,sql)){parameters(statement,values);if(statement.executeUpdate()!=1)throw new SQLException("Unexpected source restore update count");}
    }
    private static long insert(Connection connection,String table,String columns,Object...values)throws SQLException {
        String placeholders=String.join(",",Collections.nCopies(values.length,"?"));
        try(var statement=connection.prepareStatement("insert into "+table+"("+columns+") values("+placeholders+")",Statement.RETURN_GENERATED_KEYS)) {
            statement.setQueryTimeout(60);parameters(statement,values);if(statement.executeUpdate()!=1)throw new SQLException("Missing source restore row");
            try(var keys=statement.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing target identity");long id=keys.getLong(1);if(id<=0||keys.next())throw new SQLException("Invalid target identity");return id;}
        }
    }
    private static String table(String kind)throws IOException {return switch(kind){case "application.source-artifact"->"source_artifact";case "application.source-version"->"source_version";case "application.source-fragment"->"source_fragment";default->throw invalid();};}
    private static String rowHash(Connection connection,String table,long id,BackupCheckpoint checkpoint)throws SQLException,IOException {
        try(var statement=statement(connection,"select * from "+table+" where id=?")) {parameters(statement,id);
            try(var rows=statement.executeQuery()) {
                checkpoint.check();if(!rows.next())throw new IOException("Staged source row proof changed");var hash=sha256();
                for(int column=1;column<=rows.getMetaData().getColumnCount();column++) {
                    String value=rows.getString(column);String framed=value==null?"N;":"V"+value.length()+":"+value;
                    hash.update(framed.getBytes(StandardCharsets.UTF_8));
                }
                if(rows.next())throw invalid();checkpoint.check();return HexFormat.of().formatHex(hash.digest());
            }
        }
    }
    private static void saveMappings(Connection connection,Map<SourceRecordId,Long> ids,BackupCheckpoint checkpoint)throws SQLException,IOException {
        for(var mapping:ids.entrySet()){checkpoint.check();var id=mapping.getKey();
            update(connection,"insert into restore_source_mapping(record_kind,source_value,target_id,row_sha256) values(?,?,?,?)",id.kind(),id.value(),mapping.getValue(),rowHash(connection,table(id.kind()),mapping.getValue(),checkpoint));}
    }
    private static List<String> owner(Connection connection,BackupCheckpoint checkpoint)throws SQLException,IOException {
        try(var statement=statement(connection,"select generation_id,archive_sha256,content_root_sha256 from restore_source_owner where owner_id='sources'" );var rows=statement.executeQuery()) {
            checkpoint.check();if(!rows.next())return null;var result=List.of(rows.getString(1),rows.getString(2),rows.getString(3));if(rows.next())throw invalid();return result;
        }
    }
    private static void requireEmpty(Connection connection,BackupCheckpoint checkpoint)throws SQLException,IOException {
        for(String table:List.of("source_artifact","source_version","source_fragment","requirement_source_link","restore_source_owner","restore_source_mapping","restore_source_file","restore_source_quarantine")) {
            checkpoint.check();try(var statement=statement(connection,"select count(*) from "+table);var rows=statement.executeQuery()) {
                if(!rows.next()||rows.getLong(1)!=0)throw new IOException("Unjournaled source target is not empty");}
        }
    }
    private static Result resume(Connection connection,Model model,SecureDirectoryStream<Path> directory,BackupCheckpoint checkpoint)throws SQLException,IOException {
        var artifacts=new LinkedHashMap<SourceRecordId,Long>();var versions=new LinkedHashMap<SourceRecordId,Long>();var fragments=new LinkedHashMap<SourceRecordId,Long>();int count=0;
        try(var statement=statement(connection,"select record_kind,source_value,target_id,row_sha256 from restore_source_mapping order by record_kind,source_value");var rows=statement.executeQuery()) {
            while(rows.next()){checkpoint.check();if(++count>MAX_ROWS)throw invalid();var source=new SourceRecordId(rows.getString(1),rows.getString(2));long id=rows.getLong(3);
                if(!rowHash(connection,table(source.kind()),id,checkpoint).equals(rows.getString(4)))throw new IOException("Staged source row proof changed");
                var target=switch(source.kind()){case "application.source-artifact"->artifacts;case "application.source-version"->versions;case "application.source-fragment"->fragments;default->throw invalid();};
                if(target.putIfAbsent(source,id)!=null)throw invalid();}
        }
        if(!artifacts.keySet().equals(model.artifacts.keySet())||!versions.keySet().equals(model.versions.keySet())||!fragments.keySet().equals(model.fragments.keySet()))throw invalid();
        var files=new HashSet<String>();
        try(var statement=statement(connection,"select entry_path,content_name,length,sha256 from restore_source_file");var rows=statement.executeQuery()) {
            while(rows.next()){checkpoint.check();if(files.size()>=MAX_ROWS)throw invalid();var entry=model.files.get(rows.getString(1));
                if(entry==null||entry.length()!=rows.getLong(3)||!entry.sha256().equals(rows.getString(4))||!files.add(entry.path()))throw invalid();verifyFile(directory,rows.getString(2),entry,checkpoint);}
        }
        if(!files.equals(model.files.keySet()))throw invalid();var quarantine=new HashSet<SourceRecordId>();
        try(var statement=statement(connection,"select record_kind,source_value,record_json,sha256,reason from restore_source_quarantine");var rows=statement.executeQuery()) {
            while(rows.next()){checkpoint.check();if(quarantine.size()>=MAX_ROWS)throw invalid();var id=new SourceRecordId(rows.getString(1),rows.getString(2));var expected=model.legacy.get(id);
                if(expected==null||!quarantine.add(id)||!JSON.writeValueAsString(expected).equals(rows.getString(3))||!StableIdentityHash.sha256(rows.getString(3)).equals(rows.getString(4))||!"UNMAPPED_LEGACY_GLOBAL_NAMESPACE".equals(rows.getString(5)))throw invalid();}
        }
        if(!quarantine.equals(model.legacy.keySet()))throw invalid();
        for(var entry:Map.of("source_artifact",artifacts.size(),"source_version",versions.size(),"source_fragment",fragments.size(),"requirement_source_link",0).entrySet()) {
            checkpoint.check();try(var statement=statement(connection,"select count(*) from "+entry.getKey());var rows=statement.executeQuery()) {
                if(!rows.next()||rows.getLong(1)!=entry.getValue())throw new IOException("Staged source row inventory changed");}
        }
        return new Result(artifacts,versions,fragments,quarantine);
    }
    private static final class Rollback implements AutoCloseable {
        private final Connection connection;boolean committed;
        Rollback(Connection connection){this.connection=connection;}
        @Override public void close()throws SQLException {if(!committed)connection.rollback();}
    }
}
