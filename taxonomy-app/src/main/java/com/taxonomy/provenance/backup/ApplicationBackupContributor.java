package com.taxonomy.provenance.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.model.LinkType;
import com.taxonomy.model.SourceType;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Source records and retained bytes. Other application categories are supplied by separate adapters. */
public final class ApplicationBackupContributor implements BackupDataContributor {
    private static final int MAX_DEPENDENCIES=100_000;
    private final PortableRows rows;
    private final BackupSourceReference.Selector references;
    private final SourceContentFiles files;
    public ApplicationBackupContributor(DataSource database, BackupSourceReference.Selector references, Path contentRoot) {
        this.rows=new PortableRows(database); this.references=Objects.requireNonNull(references);
        this.files=new SourceContentFiles(contentRoot);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("application"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of(
            "com.taxonomy.provenance.model.SourceArtifact", "com.taxonomy.provenance.model.SourceVersion",
            "com.taxonomy.provenance.model.SourceFragment", "com.taxonomy.provenance.model.RequirementSourceLink",
            "storage.files.source-content"); }
    @Override public List<String> omissions(BackupProfile profile) {
        return List.of("application.sources: scoped exports use explicit authorized source references; unscoped legacy business-key links are installation data only",
                "application.sources: source paths are replaced by portable entries; originals never retained by the source installation are marked NOT_RETAINED",
                "application.sources: current installation exports include latest source versions and versions referenced by current records; older legacy links are excluded");
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        BackupProfile profile=snapshot.authorization().request().profile();
        List<BackupSourceReference> requested=references.select(snapshot);
        if (requested.size()>MAX_DEPENDENCIES) throw new IOException("Source dependency limit exceeded");
        var artifacts=new TreeSet<Long>(); var versions=new TreeSet<Long>(); var fragments=new TreeSet<Long>();
        for (var ref:requested) {
            add(artifacts,ref.artifact()); add(versions,ref.version());
            for (var fragment:ref.fragments()) add(fragments,fragment);
        }
        if (profile.isInstallation()) {
            ids("select id from source_artifact order by id",artifacts);
            ids("select v.id from source_version v"+(profile.includesHistory()?"":" where not exists (select 1 from source_version newer where newer.source_artifact_id=v.source_artifact_id and (newer.retrieved_at>v.retrieved_at or (newer.retrieved_at=v.retrieved_at and newer.id>v.id)))")+" order by v.id",versions);
        }
        // Resolve explicit fragment-only references first, then close complete selected versions.
        var fragmentOwners=new HashMap<Long,FragmentDependency>();
        discoverFragments(batches("select id,source_version_id,parent_fragment_id from source_fragment where id in (",fragments),fragmentOwners,versions);
        requireFound(fragments,fragmentOwners.keySet());
        var versionOwners=new HashMap<Long,Long>();
        for (Query query:batches("select id,source_artifact_id from source_version where id in (",versions))
            rows.visit(query,r->new VersionDependency(r.getLong("id"),r.getLong("source_artifact_id")),ref->{
                versionOwners.put(ref.id(),ref.artifact()); add(artifacts,ref.artifact());
            });
        requireFound(versions,versionOwners.keySet());
        discoverFragments(batches("select id,source_version_id,parent_fragment_id from source_fragment where source_version_id in (",versions),fragmentOwners,null);
        for (var fragment:fragmentOwners.values()) {
            add(fragments,fragment.id());
            if (fragment.parent()!=null) {
                FragmentDependency parent=fragmentOwners.get(fragment.parent());
                if (parent==null || parent.version()!=fragment.version()) throw new IOException("Invalid source fragment ancestry");
            }
        }
        validateAncestry(fragmentOwners);
        var foundArtifacts=new HashSet<Long>();
        for (Query query:batches("select id from source_artifact where id in (",artifacts))
            rows.visit(query,r->new Id(r.getLong("id")),ref->foundArtifacts.add(ref.id()));
        requireFound(artifacts,foundArtifacts);
        for (var reference:requested) validate(reference,versionOwners,fragmentOwners);

        // A ComponentSink may be a sequential archive. Never open a file entry from inside another entry's reader.
        var content=new HashMap<Long,CapturedFiles>();
        for (Query query:batches("select id,content_hash,storage_location,raw_text_location from source_version where id in (",versions))
            rows.visit(query,r->{
                long id=r.getLong("id");
                return new CapturedFiles(id,
                        files.capture(text(r,"storage_location"),"files/source-"+id+"-original",text(r,"content_hash"),true,sink),
                        files.capture(text(r,"raw_text_location"),"files/source-"+id+"-text",null,false,sink));
            },captured->content.put(captured.id(),captured));
        requireFound(versions,content.keySet());

        rows.writeBatches(sink,"application","source-artifact",profile,
                batches("select id,source_type,title,canonical_identifier,canonical_url,origin_system,author,description,language,created_at,updated_at from source_artifact where id in (",artifacts),
                r->new ArtifactRecord(reference(r,"application.source-artifact","id"),SourceType.valueOf(text(r,"source_type")),
                        text(r,"title"),text(r,"canonical_identifier"),text(r,"canonical_url"),text(r,"origin_system"),text(r,"author"),text(r,"description"),text(r,"language"),instant(r,"created_at"),instant(r,"updated_at")));
        rows.writeBatches(sink,"application","source-version",profile,
                batches("select id,source_artifact_id,version_label,retrieved_at,effective_date,content_hash,mime_type from source_version where id in (",versions),r->{
                    var captured=content.get(r.getLong("id"));
                    var original=captured.original(); var rawText=captured.rawText();
                    var effective=r.getDate("effective_date");
                    return new VersionRecord(reference(r,"application.source-version","id"),reference(r,"application.source-artifact","source_artifact_id"),
                            text(r,"version_label"),instant(r,"retrieved_at"),effective==null?null:effective.toLocalDate().toString(),text(r,"content_hash"),text(r,"mime_type"),
                            original==null?ContentAvailability.NOT_RETAINED:ContentAvailability.INCLUDED,original,rawText);
                });
        rows.writeBatches(sink,"application","source-fragment",profile,
                batches("select id,source_version_id,section_path,paragraph_ref,page_from,page_to,fragment_text,fragment_hash,parent_fragment_id,chunk_level from source_fragment where id in (",fragments),
                r->new FragmentRecord(reference(r,"application.source-fragment","id"),reference(r,"application.source-version","source_version_id"),
                        text(r,"section_path"),text(r,"paragraph_ref"),number(r,"page_from"),number(r,"page_to"),text(r,"fragment_text"),text(r,"fragment_hash"),
                        reference(r,"application.source-fragment","parent_fragment_id"),number(r,"chunk_level")));
        rows.write(sink,"application","legacy-source-link",profile,profile.isInstallation()?new Query(
                "select id,requirement_id,source_artifact_id,source_version_id,source_fragment_id,link_type,confidence,note from requirement_source_link order by id",List.of()):null,r->{
                    Long version=number(r,"source_version_id"),fragment=number(r,"source_fragment_id");
                    if ((version!=null && !versions.contains(version)) || (fragment!=null && !fragments.contains(fragment))) {
                        if (profile.includesHistory()) throw new IOException("Missing legacy source dependency");
                        return null;
                    }
                    var dependency=new BackupSourceReference(reference(r,"application.legacy-source-link","id"),
                            reference(r,"application.source-artifact","source_artifact_id"),reference(r,"application.source-version","source_version_id"),
                            fragment==null?List.of():List.of(new SourceRecordId("application.source-fragment",fragment.toString())));
                    if (!artifacts.contains(id(dependency.artifact()))) throw new IOException("Missing legacy source artifact");
                    validate(dependency,versionOwners,fragmentOwners);
                    return new LegacyLinkRecord(dependency.owner(),text(r,"requirement_id"),dependency.artifact(),dependency.version(),
                            fragment==null?null:dependency.fragments().getFirst(),LinkType.valueOf(text(r,"link_type")),
                            r.getObject("confidence",Double.class),text(r,"note"));
                });
    }
    private void ids(String sql,Set<Long> result) throws IOException { rows.visit(new Query(sql,List.of()),r->new Id(r.getLong("id")),ref->add(result,ref.id())); }
    private void discoverFragments(List<Query> queries,Map<Long,FragmentDependency> result,Set<Long> versions) throws IOException {
        for (Query query:queries) rows.visit(query,r->new FragmentDependency(r.getLong("id"),r.getLong("source_version_id"),number(r,"parent_fragment_id")),ref->{
            if (result.size()>=MAX_DEPENDENCIES && !result.containsKey(ref.id())) throw new IOException("Source dependency limit exceeded");
            result.put(ref.id(),ref); if (versions!=null) add(versions,ref.version());
        });
    }
    private static void validate(BackupSourceReference ref,Map<Long,Long> versions,Map<Long,FragmentDependency> fragments) throws IOException {
        Long artifact=ref.artifact()==null?null:id(ref.artifact()); Long version=ref.version()==null?null:id(ref.version());
        if (version!=null && (versions.get(version)==null || (artifact!=null && !artifact.equals(versions.get(version))))) throw new IOException("Inconsistent source version dependency");
        for (var fragmentId:ref.fragments()) {
            var fragment=fragments.get(id(fragmentId));
            if (fragment==null || (version!=null && version.longValue()!=fragment.version())
                    || (artifact!=null && !artifact.equals(versions.get(fragment.version())))) throw new IOException("Inconsistent source fragment dependency");
        }
    }
    private static void validateAncestry(Map<Long,FragmentDependency> fragments) throws IOException {
        var completed=new HashSet<Long>();
        for (Long start:fragments.keySet()) {
            var path=new HashSet<Long>(); Long next=start;
            while (next!=null && !completed.contains(next)) {
                if (!path.add(next)) throw new IOException("Cyclic source fragment ancestry");
                next=fragments.get(next).parent();
            }
            completed.addAll(path);
        }
    }
    private static void requireFound(Set<Long> requested,Set<Long> found) throws IOException {
        if (!found.containsAll(requested)) throw new IOException("Missing referenced source record");
    }
    private static long id(SourceRecordId ref) throws IOException {
        try { long value=Long.parseLong(ref.value()); if (value<1) throw new NumberFormatException(); return value; }
        catch (NumberFormatException failure) { throw new IOException("Invalid source record reference"); }
    }
    private static void add(Set<Long> ids,SourceRecordId ref) throws IOException { if (ref!=null) add(ids,id(ref)); }
    private static void add(Set<Long> ids,long id) throws IOException {
        if (id<1 || (ids.size()>=MAX_DEPENDENCIES && !ids.contains(id))) throw new IOException("Source dependency limit exceeded"); ids.add(id);
    }
    private static List<Query> batches(String select,Set<Long> ids) {
        var result=new ArrayList<Query>(); var values=new ArrayList<>(ids);
        for (int start=0;start<values.size();start+=200) {
            var batch=values.subList(start,Math.min(start+200,values.size()));
            result.add(new Query(select+String.join(",",Collections.nCopies(batch.size(),"?"))+") order by id",batch));
        }
        return result;
    }
    private record Id(long id) { }
    private record CapturedFiles(long id,BackupEntry original,BackupEntry rawText) { }
    private record VersionDependency(long id,long artifact) { }
    private record FragmentDependency(long id,long version,Long parent) { }
    public enum ContentAvailability { INCLUDED, NOT_RETAINED }
    public record ArtifactRecord(SourceRecordId sourceId,SourceType type,String title,String canonicalIdentifier,String canonicalUrl,
                                 String originSystem,String author,String description,String language,String createdAt,String updatedAt) { }
    public record VersionRecord(SourceRecordId sourceId,SourceRecordId artifactId,String versionLabel,String retrievedAt,String effectiveDate,
                                String contentHash,String mimeType,ContentAvailability originalAvailability,BackupEntry original,BackupEntry rawText) { }
    public record FragmentRecord(SourceRecordId sourceId,SourceRecordId versionId,String sectionPath,String paragraphReference,Long pageFrom,Long pageTo,
                                 String text,String contentHash,SourceRecordId parentId,Long chunkLevel) { }
    /** Remains in a legacy global namespace; restore must not bind this key to an arbitrary tenant requirement. */
    public record LegacyLinkRecord(SourceRecordId sourceId,String legacyRequirementKey,SourceRecordId artifactId,SourceRecordId versionId,
                                   SourceRecordId fragmentId,LinkType type,Double confidence,String note) { }
}
