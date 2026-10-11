package com.taxonomy.workspace.backup;

import com.taxonomy.backup.*;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.ObjectWalk;
import java.io.IOException;
import java.util.*;
import static com.taxonomy.workspace.backup.GitTreeCapture.failure;
import static com.taxonomy.workspace.backup.GitTreeCapture.sanitized;

/** Imports original objects through public Git APIs into an explicitly allocated private target. */
public final class GitHistoryRestore {
    private GitHistoryRestore() { }
    public record Source(GitReflogProvenance.Origin origin, SnapshotContext.RepositoryState state,
                         Map<String,String> backupRefs, Map<String,String> symbolicRefs,
                         List<GitHistoryCapture.Reflog> reflogs, List<GitReflogProvenance.Event> importedReflogs,
                         int objectCount) {
        public Source {
            Objects.requireNonNull(origin); Objects.requireNonNull(state); backupRefs=Map.copyOf(backupRefs);
            symbolicRefs=Map.copyOf(symbolicRefs); reflogs=List.copyOf(reflogs); importedReflogs=List.copyOf(importedReflogs);
        }
        public static Source captured(GitHistoryCapture capture, GitReflogProvenance.Origin origin) {
            var retained=new TreeMap<>(capture.exportedRefs()); retained.keySet().removeAll(capture.state().refs().keySet());
            return new Source(origin,capture.state(),retained,capture.symbolicRefs(),capture.reflogs(),capture.importedReflogs(),capture.objectCount());
        }
    }
    /** Original-event persistence and the durable completed-owner ledger remain the host's transaction. */
    public record Proof(SnapshotContext.RepositoryState state, Map<String,String> commitMappings,
                        List<GitReflogProvenance.Event> originalEvents, String repositorySha256) {
        public Proof {
            Objects.requireNonNull(state);commitMappings=Map.copyOf(commitMappings);originalEvents=List.copyOf(originalEvents);
            if(repositorySha256==null||!repositorySha256.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid restored Git proof");
        }
    }
    /** Read-only source validation can complete before the host allocates any target repository. */
    public static final class PreparedSource {
        private final Repository payload;
        private final Source source;
        private final GitHistoryCapture.Limits limits;
        private final SortedMap<String,String> refs;
        private final Map<ObjectId,GitHistoryObjects.Proof> objects;
        private final Proof expected;
        private PreparedSource(Repository payload, Source source, GitHistoryCapture.Limits limits,
                SortedMap<String,String> refs, Map<ObjectId,GitHistoryObjects.Proof> objects, Proof expected) {
            this.payload=payload;this.source=source;this.limits=limits;
            this.refs=Collections.unmodifiableSortedMap(new TreeMap<>(refs));this.objects=Map.copyOf(objects);this.expected=expected;
        }
        public int objectCount() { return objects.size(); }
        public long expandedBytes() { return objects.values().stream().mapToLong(GitHistoryObjects.Proof::length).reduce(0,Math::addExact); }
        public String inputSha256() { return expected.repositorySha256(); }
    }
    public static PreparedSource prepare(Repository payload, Source source, GitHistoryCapture.Limits limits,
                                         BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(payload);Objects.requireNonNull(source);Objects.requireNonNull(limits);checkpoint.check();
            var refs=refs(source,limits,checkpoint);
            var events=new ArrayList<>(source.importedReflogs());events.addAll(GitReflogProvenance.original(source.origin(),source.reflogs()));
            var originals=GitReflogProvenance.canonical(events,limits.maxReflogEntries(),checkpoint);
            var objects=objects(payload,source,refs,originals,limits,checkpoint);
            var mappings=new TreeMap<String,String>();source.state().requiredCommits().forEach(id->mappings.put(id,id));
            var restored=new SnapshotContext.RepositoryState(refs,source.state().symbolicHead(),Map.of(),source.state().requiredCommits());
            var objectEvidence=new TreeMap<String,GitHistoryObjects.Proof>();objects.forEach((id,proof)->objectEvidence.put(id.name(),proof));
            String digest=GitReflogProvenance.digest(GitReflogProvenance.encoded(new RepositoryDigest(source.origin(),new TreeMap<>(refs),
                    restored.symbolicHead(),new TreeMap<>(source.symbolicRefs()),new TreeSet<>(restored.requiredCommits()),objectEvidence,originals)));
            checkpoint.check();return new PreparedSource(payload,source,limits,refs,objects,new Proof(restored,mappings,originals,digest));
        } catch(IOException|RuntimeException problem){throw sanitized(problem);}
    }
    public static Proof restore(PreparedSource prepared, Repository target, UUID operation, PersonIdent actor,
                                BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(prepared);Objects.requireNonNull(target);
            Objects.requireNonNull(operation);Objects.requireNonNull(actor);checkpoint.check();
            var source=prepared.source;var refs=prepared.refs;var limits=prepared.limits;var objects=prepared.objects;
            compatible(target,source,refs,limits,checkpoint);
            GitHistoryObjects.insertVerified(prepared.payload,objects,target,checkpoint);
            // Each public library mutation may commit independently; callers journal PREPARED first
            // and retain original events with the completed-owner proof only after this verification.
            verifyObjects(target,objects,limits,checkpoint);
            compatible(target,source,refs,limits,checkpoint);
            String message="Restore original Git history "+operation;
            if(source.state().symbolicHead()!=null)link(target,Constants.HEAD,source.state().symbolicHead(),actor,message,checkpoint);
            else if(refs.containsKey(Constants.HEAD))direct(target,Constants.HEAD,refs.get(Constants.HEAD),actor,message,checkpoint);
            for(var ref:refs.entrySet()) {
                if(ref.getKey().equals(Constants.HEAD)||source.symbolicRefs().containsKey(ref.getKey()))continue;
                direct(target,ref.getKey(),ref.getValue(),actor,message,checkpoint);
            }
            // Every alias has a finite, checked chain. Its immediate target is preserved, not flattened.
            for(var alias:new TreeMap<>(source.symbolicRefs()).entrySet())link(target,alias.getKey(),alias.getValue(),actor,message,checkpoint);
            requireExact(target,source,refs,limits,checkpoint);
            verifyObjects(target,objects,limits,checkpoint);
            checkpoint.check();return prepared.expected;
        } catch(IOException|RuntimeException problem){throw sanitized(problem);}
    }
    public static Proof restore(Repository payload, Source source, Repository target, GitHistoryCapture.Limits limits,
                                UUID operation, PersonIdent actor, BackupCheckpoint checkpoint) throws IOException {
        return restore(prepare(payload,source,limits,checkpoint),target,operation,actor,checkpoint);
    }
    /** Readback for an already completed owner: never repairs missing objects or references. */
    public static Proof verify(PreparedSource prepared, Repository target, BackupCheckpoint checkpoint) throws IOException {
        try {
            Objects.requireNonNull(prepared);Objects.requireNonNull(target);checkpoint.check();
            requireExact(target,prepared.source,prepared.refs,prepared.limits,checkpoint);
            verifyObjects(target,prepared.objects,prepared.limits,checkpoint);
            requireExact(target,prepared.source,prepared.refs,prepared.limits,checkpoint);
            checkpoint.check();return prepared.expected;
        } catch(IOException|RuntimeException problem){throw sanitized(problem);}
    }
    private static SortedMap<String,String> refs(Source source,GitHistoryCapture.Limits limits,BackupCheckpoint checkpoint)throws IOException {
        if(source.objectCount()<0||source.objectCount()>limits.maxObjects())throw failure("Invalid restored Git object count");
        var result=new TreeMap<String,String>(source.state().refs());
        for(var ref:result.entrySet()){checkpoint.check();refName(ref.getKey(),true);id(ref.getValue());}
        for(var ref:source.backupRefs().entrySet()) {
            checkpoint.check();id(ref.getValue());
            if(!ref.getKey().equals("refs/taxonomy-backup/required/"+ref.getValue())||result.putIfAbsent(ref.getKey(),ref.getValue())!=null)
                throw failure("Invalid restored Git retention ref");
        }
        if(result.size()>limits.maxRefs()||source.symbolicRefs().size()>limits.maxRefs())throw failure("Restored Git ref limit exceeded");
        for(var alias:source.symbolicRefs().entrySet()) {
            checkpoint.check();refName(alias.getKey(),false);refName(alias.getValue(),false);
            if(!Objects.equals(result.get(alias.getKey()),resolve(alias.getValue(),result,source.symbolicRefs(),checkpoint))
                    ||result.get(alias.getKey())==null)throw failure("Restored symbolic Git ref differs from its object proof");
        }
        if(source.state().symbolicHead()!=null) {
            refName(source.state().symbolicHead(),false);
            if(!Objects.equals(result.get(Constants.HEAD),resolve(source.state().symbolicHead(),result,source.symbolicRefs(),checkpoint)))
                throw failure("Restored symbolic HEAD differs from its object proof");
        }
        for(var working:source.state().workingStates().entrySet()) {
            checkpoint.check();refName(Constants.R_HEADS+working.getKey(),false);
            if(working.getValue().checkpointCommit()!=null&&!source.state().requiredCommits().contains(working.getValue().checkpointCommit()))
                throw failure("Restored working-copy checkpoint is outside original Git history");
        }
        return result;
    }
    private static String resolve(String name,Map<String,String> refs,Map<String,String> aliases,BackupCheckpoint checkpoint)throws IOException {
        var visited=new HashSet<String>();int depth=1;
        while(aliases.containsKey(name)) {
            checkpoint.check();if(!visited.add(name))throw failure("Cyclic restored symbolic Git refs");
            if(depth++>=RefDatabase.MAX_SYMBOLIC_REF_DEPTH)throw failure("Restored symbolic Git ref depth limit exceeded");
            name=aliases.get(name);
        }
        return refs.get(name);
    }
    private static Map<ObjectId,GitHistoryObjects.Proof> objects(Repository payload,Source source,Map<String,String> refs,
            List<GitReflogProvenance.Event> events,GitHistoryCapture.Limits limits,BackupCheckpoint checkpoint)throws IOException {
        var starts=new TreeSet<String>(refs.values());starts.addAll(source.state().requiredCommits());
        for(var event:events) {
            checkpoint.check();if(!event.entry().oldId().equals(ObjectId.zeroId().name()))starts.add(event.entry().oldId());
            if(!event.entry().newId().equals(ObjectId.zeroId().name()))starts.add(event.entry().newId());
        }
        var result=new LinkedHashMap<ObjectId,GitHistoryObjects.Proof>();var commits=new HashSet<String>();
        try(var reader=GitHistoryObjects.inventoryReader(payload,limits,checkpoint);var walk=new ObjectWalk(reader)) {
            walk.setRetainBody(false);
            for(String commit:source.state().requiredCommits()){checkpoint.check();id(commit);walk.parseCommit(ObjectId.fromString(commit));}
            for(String start:starts){checkpoint.check();id(start);walk.markStart(walk.parseAny(ObjectId.fromString(start)));}
            for(var commit=walk.next();commit!=null;commit=walk.next()) {
                result.put(commit.copy(),GitHistoryObjects.verify(reader,commit,checkpoint));commits.add(commit.name());
            }
            for(var object=walk.nextObject();object!=null;object=walk.nextObject())result.put(object.copy(),GitHistoryObjects.verify(reader,object,checkpoint));
        }
        if(result.size()!=source.objectCount()||!commits.equals(source.state().requiredCommits()))
            throw failure("Restored Git closure differs from original object evidence");
        for(var ref:refs.entrySet()) {
            checkpoint.check();var proof=result.get(ObjectId.fromString(ref.getValue()));
            if(proof==null||(ref.getKey().equals(Constants.HEAD)||ref.getKey().startsWith(Constants.R_HEADS))&&proof.type()!=Constants.OBJ_COMMIT)
                throw failure("Restored Git branch does not identify an original commit");
        }
        return Map.copyOf(result);
    }
    private static void compatible(Repository target,Source source,Map<String,String> refs,GitHistoryCapture.Limits limits,
                                    BackupCheckpoint checkpoint)throws IOException {
        // A second public repository handle may have committed since this handle was opened.
        // The pre-write check must use persisted refs, not that handle's cached ref table.
        checkpoint.check();target.scanForRepoChanges();checkpoint.check();
        var existing=target.getRefDatabase().getRefsByPrefix(Constants.R_REFS);
        if(existing.size()>limits.maxRefs())throw failure("Unexpected staged Git refs");
        for(var ref:existing) {
            checkpoint.check();
            if(ref.getObjectId()==null||!Objects.equals(refs.get(ref.getName()),ref.getObjectId().name())
                    ||ref.isSymbolic()&&!Objects.equals(source.symbolicRefs().get(ref.getName()),ref.getTarget().getName()))
                throw failure("Staged Git ref is outside the prepared restore plan");
        }
        Ref head=target.exactRef(Constants.HEAD);
        if(head!=null&&head.getObjectId()!=null&&!Objects.equals(refs.get(Constants.HEAD),head.getObjectId().name()))
            throw failure("Staged Git HEAD is outside the prepared restore plan");
        checkpoint.check();
    }
    private static void requireExact(Repository target,Source source,Map<String,String> refs,GitHistoryCapture.Limits limits,
                                     BackupCheckpoint checkpoint)throws IOException {
        compatible(target,source,refs,limits,checkpoint);var actual=new TreeMap<String,String>();
        for(var ref:target.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
            checkpoint.check();actual.put(ref.getName(),ref.getObjectId().name());
            String alias=ref.isSymbolic()?ref.getTarget().getName():null;
            if(!Objects.equals(alias,source.symbolicRefs().get(ref.getName())))throw failure("Restored Git alias proof differs");
        }
        var head=target.exactRef(Constants.HEAD);
        if(head!=null&&head.getObjectId()!=null)actual.put(Constants.HEAD,head.getObjectId().name());
        if(!actual.equals(refs)||!Objects.equals(source.state().symbolicHead(),head!=null&&head.isSymbolic()?head.getTarget().getName():null))
            throw failure("Restored Git reference proof differs");
    }
    private static void direct(Repository target,String name,String value,PersonIdent actor,String message,BackupCheckpoint checkpoint)throws IOException {
        checkpoint.check();var before=target.exactRef(name);
        if(before!=null&&!before.isSymbolic()&&before.getObjectId()!=null&&before.getObjectId().name().equals(value))return;
        var update=target.updateRef(name,true);update.setExpectedOldObjectId(before==null||before.getObjectId()==null?ObjectId.zeroId():before.getObjectId());
        update.setNewObjectId(ObjectId.fromString(value));update.setRefLogIdent(actor);update.setRefLogMessage(message,false);
        requireResult(update.update());checkpoint.check();
    }
    private static void link(Repository target,String name,String value,PersonIdent actor,String message,BackupCheckpoint checkpoint)throws IOException {
        checkpoint.check();var before=target.exactRef(name);
        if(before!=null&&before.isSymbolic()&&before.getTarget().getName().equals(value))return;
        var update=target.updateRef(name);update.setExpectedOldObjectId(before==null||before.getObjectId()==null?ObjectId.zeroId():before.getObjectId());
        update.setRefLogIdent(actor);update.setRefLogMessage(message,false);requireResult(update.link(value));checkpoint.check();
    }
    private static void requireResult(RefUpdate.Result result)throws IOException {
        if(result!=RefUpdate.Result.NEW&&result!=RefUpdate.Result.NO_CHANGE&&result!=RefUpdate.Result.FORCED&&result!=RefUpdate.Result.FAST_FORWARD)
            throw failure("Original Git ref update was not confirmed");
    }
    private static void verifyObjects(Repository target,Map<ObjectId,GitHistoryObjects.Proof> expected,GitHistoryCapture.Limits limits,
                                       BackupCheckpoint checkpoint)throws IOException {
        try(var reader=GitHistoryObjects.inventoryReader(target,limits,checkpoint)) {
            for(var entry:expected.entrySet())if(!entry.getValue().equals(GitHistoryObjects.verify(reader,entry.getKey(),checkpoint)))
                throw failure("Restored Git object differs from its original proof");
        }
    }
    private static void refName(String name,boolean head)throws IOException {
        if(name==null||name.length()>1024||!(head&&name.equals(Constants.HEAD))&&(!name.startsWith(Constants.R_REFS)||!Repository.isValidRefName(name)))
            throw failure("Invalid restored Git ref name");
    }
    private static void id(String id)throws IOException {
        if(id==null||!id.matches("[0-9a-f]{40}")||id.equals(ObjectId.zeroId().name()))throw failure("Invalid restored Git object identity");
    }
    private record RepositoryDigest(GitReflogProvenance.Origin origin,SortedMap<String,String> refs,String symbolicHead,
            SortedMap<String,String> aliases,SortedSet<String> commits,SortedMap<String,GitHistoryObjects.Proof> objects,
            List<GitReflogProvenance.Event> originalEvents) { }
}
