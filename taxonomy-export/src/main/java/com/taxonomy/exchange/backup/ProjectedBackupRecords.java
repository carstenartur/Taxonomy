package com.taxonomy.exchange.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.backup.*;
import java.io.*;
import java.security.*;
import java.util.*;

/** Inspects the owner's actual portable projection, without retaining an entry or duplicating its row selection. */
public final class ProjectedBackupRecords {
    private ProjectedBackupRecords() { }
    @FunctionalInterface public interface Visitor { void record(String dataset, JsonNode record) throws IOException; }
    public static void inspect(BackupDataContributor owner, SnapshotContext snapshot, BackupCheckpoint checkpoint, Visitor visitor) throws IOException {
        owner.write(snapshot, new ComponentSink() {
            long total; int entries;
            @Override public void checkpoint() throws IOException { checkpoint.check(); }
            @Override public BackupEntry write(String path, InputStream input) throws IOException {
                return writeGenerated(path, output -> input.transferTo(output));
            }
            @Override public BackupEntry writeGenerated(String path, EntryWriter producer) throws IOException {
                checkpoint();
                if (++entries > BackupLimits.MAX_ITEMS || !path.matches("data/[a-z][a-z0-9-]*/[a-z][a-z0-9-]*\\.ndjson"))
                    throw new IOException("Unsupported projected reference dataset");
                String dataset=path.substring(path.lastIndexOf('/')+1,path.length()-7);
                var digest=sha256();var record=new ByteArrayOutputStream();long[] length={0};int[] lines={0};
                var output=new OutputStream() {
                    @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
                    @Override public void write(byte[] bytes,int offset,int count) throws IOException {
                        Objects.checkFromIndexSize(offset,count,bytes.length);checkpoint();
                        if(count>(10L<<30)-total)throw new IOException("Reference inspection byte limit exceeded");
                        total+=count;length[0]+=count;digest.update(bytes,offset,count);
                        for(int i=offset;i<offset+count;i++) {
                            if(bytes[i]=='\n') {
                                if(++lines[0]>1_000_001 || record.size()==0)throw new IOException("Invalid projected reference records");
                                var document=PortableRows.json().readTree(record.toByteArray());record.reset();
                                if(document==null || !document.isObject())throw new IOException("Invalid projected reference record");
                                if(lines[0]==1) {
                                    if(document.path("schemaVersion").asInt()!=1 || !dataset.equals(document.path("kind").asText())
                                            || !snapshot.authorization().request().profile().name().equals(document.path("profile").asText()))
                                        throw new IOException("Projected reference header differs from capture");
                                } else visitor.record(dataset,document);
                                checkpoint();
                            } else {
                                if(record.size()>=PortableRows.MAX_RECORD_BYTES)throw new IOException("Projected reference record limit exceeded");
                                record.write(bytes[i]);
                            }
                        }
                    }
                };
                producer.write(output);checkpoint();
                if(record.size()!=0 || lines[0]==0)throw new IOException("Incomplete projected reference records");
                return new BackupEntry(path,length[0],HexFormat.of().formatHex(digest.digest()));
            }
        });
    }
    private static MessageDigest sha256() { try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);} }
}
