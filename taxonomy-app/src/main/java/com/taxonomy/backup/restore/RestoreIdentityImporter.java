package com.taxonomy.backup.restore;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.security.backup.IdentityBackupContributor.*;
import com.taxonomy.security.model.PrincipalBinding;
import java.io.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Imports explicit disabled namespaces and inert typed evidence, never source login/admin authority. */
public final class RestoreIdentityImporter {
    private static final BackupComponentId OWNER=new BackupComponentId("application");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final int MAX_ROWS=100_000,MAX_BYTES=32*1024*1024;
    public record Result(Map<PrincipalId,PrincipalId> targetPrincipals,Map<String,String> sourceScopes,Set<PrincipalId> privatePrincipals,int evidenceCount) {
        public Result {targetPrincipals=Map.copyOf(targetPrincipals);sourceScopes=Map.copyOf(sourceScopes);privatePrincipals=Set.copyOf(privatePrincipals);}
    }
    /** Uses the exact import DTO/reference checks without opening any target or writing rows. */
    public Result preview(VerifiedBackup archive,Map<PrincipalId,RestorePrincipalAllocation> approvedMapping,BackupCheckpoint checkpoint)throws IOException {
        Map<PrincipalId,RestorePrincipalAllocation> mapping;
        try{mapping=Map.copyOf(approvedMapping);}catch(NullPointerException malformed){throw new IOException("Restore identity mapping is incomplete or foreign");}
        return allocations(inspect(archive,checkpoint),mapping);
    }
    public Result restore(VerifiedBackup archive,RestoreTargetGeneration generation,
                          Map<PrincipalId,RestorePrincipalAllocation> approvedMapping,BackupCheckpoint checkpoint)throws IOException {
        var model=inspect(archive,checkpoint);Map<PrincipalId,RestorePrincipalAllocation> mapping;
        try{mapping=Map.copyOf(approvedMapping);}catch(NullPointerException malformed){throw new IOException("Restore identity mapping is incomplete or foreign");}
        var proof=allocations(model,mapping);String mappingHash=mappingHash(mapping);
        generation.requireUnchanged(checkpoint);
        try(var connection=generation.dataSource().getConnection()) {
            generation.requireConnection(connection,checkpoint);
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var rollback=new Rollback(connection)) {
                var existing=owner(connection,checkpoint);
                if(existing!=null) {
                    if(!existing.equals(List.of(generation.generationId().toString(),archive.archiveSha256(),mappingHash)))throw new IOException("Identity owner is bound to another restore plan");
                    verify(connection,model,mapping,checkpoint);generation.requireConnection(connection,checkpoint);return proof;
                }
                requireEmpty(connection,checkpoint);
                for(var entry:mapping.entrySet()) {
                    checkpoint.check();var allocation=entry.getValue();String target=allocation.targetPrincipal().value().toString();
                    update(connection,"insert into app_principal(principal_id,scope_key,enabled) values(?,?,0)",target,allocation.targetScope());
                    update(connection,"insert into restore_identity_mapping(source_principal,target_principal,target_scope,disposition,row_sha256) values(?,?,?,?,?)",
                            entry.getKey().value().toString(),target,allocation.targetScope(),allocation.disposition().name(),principalHash(allocation));
                }
                for(var evidence:model.evidence.values()) {
                    checkpoint.check();update(connection,"insert into restore_identity_evidence(record_kind,record_key,record_json,sha256,classification) values(?,?,?,?,?)",
                            evidence.kind(),evidence.key(),evidence.json(),StableIdentityHash.sha256(evidence.json()),evidence.classification());
                }
                update(connection,"insert into restore_identity_owner(owner_id,generation_id,archive_sha256,mapping_sha256) values('identities',?,?,?)",generation.generationId().toString(),archive.archiveSha256(),mappingHash);
                generation.requireConnection(connection,checkpoint);connection.commit();rollback.committed=true;checkpoint.check();return proof;
            }
        } catch(InterruptedIOException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        catch(SQLException|RuntimeException failure){throw new IOException("Staged identity owner import failed");}
    }
    private static Model inspect(VerifiedBackup archive,BackupCheckpoint checkpoint)throws IOException {
        if(archive.manifest().components().stream().noneMatch(component->component.id().equals(OWNER)&&component.version()==1))throw invalid();
        var model=new Model();
        read(archive,"principals",PrincipalRecord.class,checkpoint,record->{
            if(record.id()==null||record.registryState()==null||record.restorePolicy()!=RestorePolicy.EXPLICIT_MAPPING
                    ||(record.registryState()==RegistryState.REGISTERED)!=(record.sourceScope()!=null))throw invalid();
            text(record.sourceScope(),255);text(record.displayName(),255);text(record.email(),255);
            if(record.sourceScope()!=null&&record.sourceScope().isBlank())throw invalid();
            if(model.principals.putIfAbsent(record.id(),record)!=null)throw invalid();model.evidence("principals",record.id().value().toString(),record,"PRIVATE_PRIMARY");
        });
        read(archive,"bindings",BindingRecord.class,checkpoint,record->{
            id(record.sourceId(),"identity-binding");if(record.principal()==null||record.binding()==null||!record.sourceId().value().equals(PrincipalBinding.key(record.binding())))throw invalid();
            text(record.binding().issuer(),2048);text(record.binding().subject(),1024);
            if(!model.principals.containsKey(record.principal()))throw invalid();model.evidence("bindings",record.sourceId().value(),record,"PRIVATE_PRIMARY");
        });
        read(archive,"local-accounts",AccountRecord.class,checkpoint,record->{
            id(record.sourceId(),"local-account");if(record.principal()==null||!model.principals.containsKey(record.principal()))throw invalid();
            required(record.username(),255);text(record.displayName(),255);text(record.email(),255);
            if(model.accounts.putIfAbsent(record.sourceId(),record)!=null)throw invalid();model.evidence("local-accounts",record.sourceId().value(),record,"PRIVATE_PRIMARY");
        });
        read(archive,"roles",RoleRecord.class,checkpoint,record->{
            id(record.sourceId(),"role");required(record.name(),255);if(model.roles.putIfAbsent(record.sourceId(),record)!=null)throw invalid();model.evidence("roles",record.sourceId().value(),record,"PRIVATE_PRIMARY");
        });
        read(archive,"user-roles",UserRoleRecord.class,checkpoint,record->{
            id(record.account(),"local-account");id(record.role(),"role");if(!model.accounts.containsKey(record.account())||!model.roles.containsKey(record.role()))throw invalid();
            model.evidence("user-roles",StableIdentityHash.sha256(JSON.writeValueAsString(record)),record,"INERT_SOURCE_AUTHORITY");
        });
        read(archive,"capability-grants",CapabilityGrant.class,checkpoint,record->{
            if(record.principal()==null||record.capability()==null||!model.principals.containsKey(record.principal()))throw invalid();
            model.evidence("capability-grants",StableIdentityHash.sha256(JSON.writeValueAsString(record)),record,"INERT_SOURCE_AUTHORITY");
        });
        read(archive,"version-grants",VersionGrant.class,checkpoint,record->{
            if(record.principal()==null||!model.principals.containsKey(record.principal())||record.repository()==null||record.commit()==null||!record.commit().matches("[a-f0-9]{40}"))throw invalid();
            // Historical source grants are evidence, not executable target ACLs or proof of target Git objects.
            model.evidence("version-grants",StableIdentityHash.sha256(JSON.writeValueAsString(record)),record,"INERT_SOURCE_AUTHORITY");
        });
        read(archive,"access-audit",AccessAudit.class,checkpoint,record->{
            id(record.sourceId(),"identity-audit");if(!model.principals.containsKey(record.actor())||!model.principals.containsKey(record.target()))throw invalid();
            required(record.decision(),40);text(record.detail(),2048);instant(record.occurredAt());model.evidence("access-audit",record.sourceId().value(),record,"HISTORICAL_EVIDENCE");
        });
        var installation=RestoreArchiveRecords.document(archive,OWNER,"identities/installation.json",InstallationRecord.class,checkpoint);
        if(installation.installationId()==null||!installation.installationId().equals(archive.manifest().sourceInstallationId()))throw invalid();
        canonicalUuid(installation.installationId());model.evidence("installation",installation.installationId(),installation,"HISTORICAL_EVIDENCE");
        String passwords="protected/identities/password-hashes.ndjson";
        if(archive.manifest().request().secrets()==SecretsSelection.INCLUDE_ENCRYPTED) {
            RestoreArchiveRecords.read(archive,OWNER,passwords,"password-hashes",PasswordHash.class,MAX_ROWS,checkpoint,record->{
                id(record.account(),"local-account");var account=model.accounts.get(record.account());
                if(account==null||!account.principal().equals(record.principal()))throw invalid();required(record.passwordHash(),255);
                model.evidence("password-hashes",record.account().value(),record,"PROTECTED_PRIMARY");
            });
            long hashes=model.evidence.values().stream().filter(row->row.kind().equals("password-hashes")).count();
            if(hashes!=model.accounts.size())throw invalid();
        } else if(RestoreArchiveRecords.declares(archive,OWNER,passwords))throw invalid();
        var usernames=new HashSet<String>();var accountPrincipals=new HashSet<PrincipalId>();
        for(var account:model.accounts.values()) {
            checkpoint.check();if(!usernames.add(account.username())||!accountPrincipals.add(account.principal()))throw invalid();
            var principal=model.principals.get(account.principal());
            if(principal.registryState()==RegistryState.PENDING_LOCAL_ACCOUNT)model.pendingScopes.put(account.principal(),account.username());
        }
        for(var principal:model.principals.values())if(principal.registryState()==RegistryState.PENDING_LOCAL_ACCOUNT&&!model.pendingScopes.containsKey(principal.id()))throw invalid();
        checkpoint.check();return model;
    }
    private static Result allocations(Model model,Map<PrincipalId,RestorePrincipalAllocation> mapping)throws IOException {
        if(!mapping.keySet().equals(model.principals.keySet()))throw new IOException("Restore identity mapping is incomplete or foreign");
        var targets=new HashMap<PrincipalId,PrincipalId>();var scopes=new HashMap<String,String>();var privatePrincipals=new HashSet<PrincipalId>();
        var seenTargets=new HashSet<PrincipalId>();var seenScopes=new HashSet<String>();
        for(var entry:mapping.entrySet()) {
            var allocation=entry.getValue();if(!seenTargets.add(allocation.targetPrincipal())||!seenScopes.add(allocation.targetScope()))throw new IOException("Restore identity mapping merges distinct source identities");
            targets.put(entry.getKey(),allocation.targetPrincipal());if(allocation.disposition()==RestorePrincipalAllocation.Disposition.QUARANTINE)privatePrincipals.add(allocation.targetPrincipal());
            var principal=model.principals.get(entry.getKey());String scope=principal.sourceScope()==null?model.pendingScopes.get(entry.getKey()):principal.sourceScope();
            if(scope!=null&&scopes.putIfAbsent(scope,allocation.targetScope())!=null)throw new IOException("Restore identity mapping has conflicting source scopes");
        }
        return new Result(targets,scopes,privatePrincipals,model.evidence.size());
    }
    private static String mappingHash(Map<PrincipalId,RestorePrincipalAllocation> mapping)throws IOException {
        var sorted=new TreeMap<String,RestorePrincipalAllocation>();mapping.forEach((source,target)->sorted.put(source.value().toString(),target));
        return StableIdentityHash.sha256(JSON.writeValueAsString(sorted));
    }
    private static String principalHash(RestorePrincipalAllocation allocation)throws IOException {
        return StableIdentityHash.sha256(JSON.writeValueAsString(new PrincipalProof(allocation.targetPrincipal().value().toString(),allocation.targetScope(),0)));
    }
    private record PrincipalProof(String principal,String scope,int enabled) { }
    private static <T extends Record>void read(VerifiedBackup archive,String kind,Class<T> type,BackupCheckpoint checkpoint,RestoreArchiveRecords.Visitor<T> visitor)throws IOException {
        try{RestoreArchiveRecords.read(archive,OWNER,"identities/"+kind+".ndjson",kind,type,MAX_ROWS,checkpoint,visitor);}
        catch(IllegalArgumentException|NullPointerException malformed){throw invalid();}
    }
    private static void id(SourceRecordId id,String kind)throws IOException {if(id==null||!id.kind().equals("application."+kind)||id.value().length()>4096)throw invalid();}
    private static void text(String value,int limit)throws IOException {if(value!=null&&value.length()>limit)throw invalid();}
    private static void required(String value,int limit)throws IOException {if(value==null||value.isBlank())throw invalid();text(value,limit);}
    private static void instant(String value)throws IOException {try{if(value==null)throw invalid();Instant.parse(value);}catch(RuntimeException malformed){throw invalid();}}
    private static void canonicalUuid(String value)throws IOException {try{if(!UUID.fromString(value).toString().equals(value))throw invalid();}catch(RuntimeException malformed){throw invalid();}}
    private static IOException invalid(){return new IOException("Invalid typed source identity evidence");}
    private record Evidence(String kind,String key,String json,String classification) { }
    private static final class Model {
        final Map<PrincipalId,PrincipalRecord> principals=new LinkedHashMap<>();final Map<SourceRecordId,AccountRecord> accounts=new LinkedHashMap<>();
        final Map<SourceRecordId,RoleRecord> roles=new LinkedHashMap<>();final Map<PrincipalId,String> pendingScopes=new HashMap<>();
        final Map<String,Evidence> evidence=new LinkedHashMap<>();int bytes;
        void evidence(String kind,String key,Record value,String classification)throws IOException {
            String json=JSON.writeValueAsString(value);int size=json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(size>MAX_BYTES-bytes||evidence.size()>=MAX_ROWS)throw new IOException("Identity restore evidence budget exceeded");bytes+=size;
            if(evidence.putIfAbsent(kind+":"+key,new Evidence(kind,key,json,classification))!=null)throw invalid();
        }
    }
    private static PreparedStatement statement(Connection connection,String sql)throws SQLException {
        var statement=connection.prepareStatement(sql);statement.setMaxRows(MAX_ROWS+1);statement.setQueryTimeout(60);return statement;
    }
    private static void update(Connection connection,String sql,Object...values)throws SQLException {
        try(var statement=statement(connection,sql)) {
            for(int index=0;index<values.length;index++)statement.setObject(index+1,values[index]);
            if(statement.executeUpdate()!=1)throw new SQLException("Unexpected identity restore row count");
        }
    }
    private static List<String> owner(Connection connection,BackupCheckpoint checkpoint)throws SQLException,IOException {
        try(var statement=statement(connection,"select generation_id,archive_sha256,mapping_sha256 from restore_identity_owner where owner_id='identities'");var rows=statement.executeQuery()) {
            checkpoint.check();if(!rows.next())return null;var result=List.of(rows.getString(1),rows.getString(2),rows.getString(3));if(rows.next())throw invalid();return result;
        }
    }
    private static void requireEmpty(Connection connection,BackupCheckpoint checkpoint)throws SQLException,IOException {
        for(String table:List.of("app_principal","principal_binding","app_user","app_role","user_roles","backup_capability_grant","backup_version_grant","principal_access_audit","restore_identity_owner","restore_identity_mapping","restore_identity_evidence")) {
            checkpoint.check();if(count(connection,table)!=0)throw new IOException("Unjournaled identity target is not empty");
        }
    }
    private static long count(Connection connection,String table)throws SQLException {
        try(var statement=statement(connection,"select count(*) from "+table);var rows=statement.executeQuery()) {if(!rows.next())throw new SQLException("Missing identity inventory");return rows.getLong(1);}
    }
    private static void verify(Connection connection,Model model,Map<PrincipalId,RestorePrincipalAllocation> mapping,BackupCheckpoint checkpoint)throws SQLException,IOException {
        if(count(connection,"restore_identity_owner")!=1)throw new IOException("Staged identity owner inventory proof changed");
        var found=new HashSet<PrincipalId>();
        try(var statement=statement(connection,"select m.source_principal,m.target_principal,m.target_scope,m.disposition,m.row_sha256,p.scope_key,p.enabled from restore_identity_mapping m join app_principal p on p.principal_id=m.target_principal");var rows=statement.executeQuery()) {
            while(rows.next()) {
                checkpoint.check();if(found.size()>=MAX_ROWS)throw invalid();var source=new PrincipalId(UUID.fromString(rows.getString(1)));var allocation=mapping.get(source);
                if(allocation==null||!source.value().toString().equals(rows.getString(1))||!found.add(source)||!allocation.targetPrincipal().value().toString().equals(rows.getString(2))||!allocation.targetScope().equals(rows.getString(3))
                        ||!allocation.disposition().name().equals(rows.getString(4))||!principalHash(allocation).equals(rows.getString(5))||!allocation.targetScope().equals(rows.getString(6))||rows.getInt(7)!=0)
                    throw new IOException("Staged identity principal proof changed");
            }
        }
        if(!found.equals(mapping.keySet())||count(connection,"app_principal")!=mapping.size()||count(connection,"restore_identity_mapping")!=mapping.size())throw new IOException("Staged identity principal inventory proof changed");
        var evidence=new HashSet<String>();
        try(var statement=statement(connection,"select record_kind,record_key,record_json,sha256,classification from restore_identity_evidence");var rows=statement.executeQuery()) {
            while(rows.next()) {
                checkpoint.check();if(evidence.size()>=MAX_ROWS)throw invalid();String key=rows.getString(1)+":"+rows.getString(2);var expected=model.evidence.get(key);
                if(expected==null||!evidence.add(key)||!sameJson(rows.getCharacterStream(3),expected.json(),checkpoint)||!StableIdentityHash.sha256(expected.json()).equals(rows.getString(4))||!expected.classification().equals(rows.getString(5)))
                    throw new IOException("Staged identity private evidence proof changed");
            }
        }
        if(!evidence.equals(model.evidence.keySet()))throw new IOException("Staged identity private evidence inventory proof changed");
        for(String table:List.of("principal_binding","app_user","app_role","user_roles","backup_capability_grant","backup_version_grant","principal_access_audit")) {
            checkpoint.check();if(count(connection,table)!=0)throw new IOException("Staged identity authority proof changed");
        }
    }
    /** A changed LOB can cost at most the expected bounded record plus one character. */
    private static boolean sameJson(Reader source,String expected,BackupCheckpoint checkpoint)throws IOException {
        if(source==null)return false;
        try(source) {
            char[] buffer=new char[4096];int offset=0;
            while(offset<expected.length()) {
                checkpoint.check();int count=source.read(buffer,0,Math.min(buffer.length,expected.length()-offset));
                if(count<0)return false;if(count==0)throw new IOException("Staged identity private evidence proof changed");
                for(int index=0;index<count;index++)if(buffer[index]!=expected.charAt(offset+index))return false;
                offset+=count;
            }
            checkpoint.check();return source.read()==-1;
        }
    }
    private static final class Rollback implements AutoCloseable {
        private final Connection connection;boolean committed;
        Rollback(Connection connection){this.connection=connection;}
        @Override public void close()throws SQLException {if(!committed)connection.rollback();}
    }
}
