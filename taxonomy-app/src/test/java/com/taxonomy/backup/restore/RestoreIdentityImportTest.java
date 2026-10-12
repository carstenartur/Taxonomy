package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.security.backup.IdentityBackupContributor;
import com.taxonomy.security.model.PrincipalBinding;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Identity import retains private evidence; neither source names nor archived grants confer authority. */
class RestoreIdentityImportTest {
    @TempDir Path root;
    private final PrincipalId first=new PrincipalId(UUID.fromString("00000000-0000-0000-0000-0000000000ab")),second=PrincipalId.create();
    @Test void sameNameDifferentIssuerStaysExplicitlyMappedOrPrivateWithNoLoginOrGrantCopy()throws Exception {
        var source=source();var staged=database();RestoreSchemaMigration.bootstrap(source,staged,()->{});
        var targetFirst=PrincipalId.create();var targetSecond=PrincipalId.create();
        var allocations=Map.of(first,new RestorePrincipalAllocation(targetFirst,"approved-target-alex",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET),
                second,new RestorePrincipalAllocation(targetSecond,"restored-private-"+targetSecond.value(),RestorePrincipalAllocation.Disposition.QUARANTINE));
        try(var factory=RestoreSchemaMigration.openValidatedFactory(source,staged,model(),()->{});var archive=export(source)) {
            var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,staged,factory,Map.of(),()->{});
            var importer=new RestoreIdentityImporter();var result=importer.restore(archive,generation,allocations,()->{});
            assertThat(result.targetPrincipals()).containsEntry(first,targetFirst).containsEntry(second,targetSecond);
            var rows=new JdbcTemplate(staged);
            assertThat(rows.queryForList("select principal_id from app_principal order by scope_key",String.class)).containsExactlyInAnyOrder(targetFirst.value().toString(),targetSecond.value().toString());
            assertThat(rows.queryForObject("select count(*) from app_principal where enabled<>0",Long.class)).isZero();
            assertThat(rows.queryForObject("select scope_key from app_principal where principal_id=?",String.class,targetSecond.value().toString())).isEqualTo(allocations.get(second).targetScope());
            for(String table:List.of("principal_binding","app_user","app_role","user_roles","backup_capability_grant","backup_version_grant"))
                assertThat(rows.queryForObject("select count(*) from "+table,Long.class)).as(table).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_identity_evidence where record_kind='bindings'",Long.class)).isEqualTo(2);
            assertThat(rows.queryForObject("select count(*) from restore_identity_evidence where record_kind='local-accounts'",Long.class)).isEqualTo(2);
            assertThat(rows.queryForObject("select record_json from restore_identity_evidence where record_kind='roles'",String.class)).contains("ADMIN");
            assertThat(new RestoreIdentityImporter().restore(archive,generation,allocations,()->{})).isEqualTo(result);
            assertThat(rows.queryForObject("select count(*) from app_principal",Long.class)).isEqualTo(2);
            rows.update("update restore_identity_evidence set classification='PUBLIC' where record_kind='bindings'");
            assertThatThrownBy(()->importer.restore(archive,generation,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("private evidence proof");
            rows.update("update restore_identity_evidence set classification='PRIVATE_PRIMARY' where record_kind='bindings'");
            var armed=new java.util.concurrent.atomic.AtomicBoolean();var writes=new java.util.concurrent.atomic.AtomicInteger();
            var rerouted=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException{return armed.get()&&writes.incrementAndGet()>1?source.getConnection():staged.getConnection();}
                @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
            };
            var reroutedGeneration=RestoreTargetGeneration.verified(generation.generationId(),source,rerouted,factory,Map.of(),()->{});armed.set(true);
            assertThatThrownBy(()->importer.restore(archive,reroutedGeneration,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("database binding");
            assertThat(writes).hasValue(2);
            rows.update("update app_principal set enabled=1 where principal_id=?",targetFirst.value().toString());
            assertThatThrownBy(()->importer.restore(archive,generation,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("proof");
        }
        var original=new JdbcTemplate(source);
        assertThat(original.queryForObject("select count(*) from principal_binding where enabled=1",Long.class)).isEqualTo(2);
        assertThat(original.queryForObject("select count(*) from user_roles",Long.class)).isEqualTo(1);
        assertThat(original.queryForObject("select count(*) from app_principal where enabled=1",Long.class)).isEqualTo(2);
    }
    @Test void activeAliasOnActualStagedWriteBorrowFailsBeforeIdentityMutation()throws Exception {
        var source=source();var staged=database();RestoreSchemaMigration.bootstrap(source,staged,()->{});
        var active=new org.springframework.jdbc.datasource.DelegatingDataSource(source);
        var armed=new java.util.concurrent.atomic.AtomicBoolean();var borrows=new java.util.concurrent.atomic.AtomicInteger();
        var guarded=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                if(armed.get()&&borrows.incrementAndGet()==2)active.setTargetDataSource(staged);
                return staged.getConnection();
            }
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
        };
        var allocations=Map.of(first,new RestorePrincipalAllocation(PrincipalId.create(),"target-one",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET),
                second,new RestorePrincipalAllocation(PrincipalId.create(),"private-two",RestorePrincipalAllocation.Disposition.QUARANTINE));
        try(var factory=RestoreSchemaMigration.openValidatedFactory(source,staged,model(),()->{});var archive=export(source)) {
            var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),active,guarded,factory,Map.of(),()->{});armed.set(true);
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("binding");
            assertThat(borrows).hasValue(2);var rows=new JdbcTemplate(staged);
            assertThat(rows.queryForObject("select count(*) from app_principal",Long.class)).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_identity_owner",Long.class)).isZero();
            assertThat(new JdbcTemplate(source).queryForObject("select count(*) from app_principal",Long.class)).isEqualTo(2);
        }
    }
    @Test void unresolvedOrMergedMappingsFailBeforeAnyPrincipalOrEvidenceWrites()throws Exception {
        var source=source();var staged=database();RestoreSchemaMigration.bootstrap(source,staged,()->{});
        try(var factory=RestoreSchemaMigration.openValidatedFactory(source,staged,model(),()->{});var archive=export(source)) {
            var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,staged,factory,Map.of(),()->{});
            var target=PrincipalId.create();var allocation=new RestorePrincipalAllocation(target,"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET);
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,Map.of(first,allocation),()->{})).isInstanceOf(IOException.class).hasMessageContaining("mapping");
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,Map.of(first,allocation,second,allocation),()->{})).isInstanceOf(IOException.class).hasMessageContaining("mapping");
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,null,()->{})).isInstanceOf(IOException.class).hasMessageContaining("mapping");
            var nullAllocation=new HashMap<PrincipalId,RestorePrincipalAllocation>();nullAllocation.put(first,allocation);nullAllocation.put(second,null);
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,nullAllocation,()->{})).isInstanceOf(IOException.class).hasMessageContaining("mapping");
            var rows=new JdbcTemplate(staged);
            assertThat(rows.queryForObject("select count(*) from app_principal",Long.class)).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_identity_evidence",Long.class)).isZero();
        }
    }
    @Test void postcommitRestartUsesOnlyExactDurableMappingAndGeneration()throws Exception {
        var source=source();var staged=database();RestoreSchemaMigration.bootstrap(source,staged,()->{});
        var allocations=Map.of(first,new RestorePrincipalAllocation(PrincipalId.create(),"target-one",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET),
                second,new RestorePrincipalAllocation(PrincipalId.create(),"private-two",RestorePrincipalAllocation.Disposition.QUARANTINE));
        try(var factory=RestoreSchemaMigration.openValidatedFactory(source,staged,model(),()->{});var archive=export(source)) {
            var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,staged,factory,Map.of(),()->{});
            var rows=new JdbcTemplate(staged);
            var begun=new java.util.concurrent.atomic.AtomicBoolean();
            var guarded=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                    var connection=staged.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,arguments)->{
                        if(method.getName().equals("prepareStatement")&&((String)arguments[0]).startsWith("insert into restore_identity_mapping"))begun.set(true);
                        if(method.getName().equals("rollback")&&begun.get()){connection.rollback();throw new java.sql.SQLException("Injected identity cleanup failure");}
                        try{return method.invoke(connection,arguments);}catch(java.lang.reflect.InvocationTargetException wrapper){throw wrapper.getCause();}
                    });
                }
                @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
            };
            var cleanupGeneration=RestoreTargetGeneration.verified(generation.generationId(),source,guarded,factory,Map.of(),()->{});
            Throwable cancelled=catchThrowable(()->new RestoreIdentityImporter().restore(archive,cleanupGeneration,allocations,()->{if(begun.get())throw new InterruptedIOException("Stop after private principal write");}));
            assertThat(cancelled).isInstanceOf(InterruptedIOException.class);
            assertThat(cancelled.getSuppressed()).anyMatch(error->error instanceof java.sql.SQLException&&error.getMessage().equals("Injected identity cleanup failure"));
            assertThat(Thread.interrupted()).isTrue();assertThat(rows.queryForObject("select count(*) from app_principal",Long.class)).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_identity_mapping",Long.class)).isZero();
            BackupCheckpoint crash=()->{
                if(rows.queryForObject("select count(*) from restore_identity_owner",Long.class)==1)throw new InterruptedIOException("Stop after identity owner commit");
            };
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,allocations,crash)).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.interrupted()).isTrue();
            var resumed=new RestoreIdentityImporter().restore(archive,generation,allocations,()->{});
            assertThat(resumed.targetPrincipals()).hasSize(2);assertThat(rows.queryForObject("select count(*) from app_principal",Long.class)).isEqualTo(2);
            var changed=new HashMap<>(allocations);changed.put(first,new RestorePrincipalAllocation(PrincipalId.create(),"different-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            assertThatThrownBy(()->new RestoreIdentityImporter().restore(archive,generation,changed,()->{})).isInstanceOf(IOException.class).hasMessageContaining("plan");
        }
    }
    @Test void retryProofRejectsNoncanonicalSourceIdsForeignOwnersAndReadsOnlyExpectedPrivateLobCharacters()throws Exception {
        var source=source();var staged=database();RestoreSchemaMigration.bootstrap(source,staged,()->{});
        var allocations=Map.of(first,new RestorePrincipalAllocation(PrincipalId.create(),"target-one",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET),
                second,new RestorePrincipalAllocation(PrincipalId.create(),"private-two",RestorePrincipalAllocation.Disposition.QUARANTINE));
        try(var factory=RestoreSchemaMigration.openValidatedFactory(source,staged,model(),()->{});var archive=export(source)) {
            var generation=RestoreTargetGeneration.verified(UUID.randomUUID(),source,staged,factory,Map.of(),()->{});
            var importer=new RestoreIdentityImporter();importer.restore(archive,generation,allocations,()->{});var rows=new JdbcTemplate(staged);
            org.junit.jupiter.api.Assertions.assertAll(
                    ()->{
                        rows.update("update restore_identity_mapping set source_principal=upper(source_principal) where source_principal=?",first.value().toString());
                        try{assertThatThrownBy(()->importer.restore(archive,generation,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("proof");}
                        finally{rows.update("update restore_identity_mapping set source_principal=lower(source_principal)");}
                    },
                    ()->{
                        rows.update("insert into restore_identity_owner select 'foreign',generation_id,archive_sha256,mapping_sha256 from restore_identity_owner where owner_id='identities'");
                        try{assertThatThrownBy(()->importer.restore(archive,generation,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("inventory");}
                        finally{rows.update("delete from restore_identity_owner where owner_id='foreign'");}
                    },
                    ()->{
                        String expected=rows.queryForObject("select record_json from restore_identity_evidence where record_kind='installation'",String.class);
                        rows.update("update restore_identity_evidence set record_json=? where record_kind='installation'",expected+" ".repeat(1_048_576));
                        var materialized=new java.util.concurrent.atomic.AtomicBoolean();var readCount=new java.util.concurrent.atomic.AtomicInteger();
                        var guarded=lobObserved(staged,materialized,readCount);
                        var guardedGeneration=RestoreTargetGeneration.verified(generation.generationId(),source,guarded,factory,Map.of(),()->{});
                        try {
                            assertThatThrownBy(()->importer.restore(archive,guardedGeneration,allocations,()->{})).isInstanceOf(IOException.class).hasMessageContaining("private evidence proof");
                            assertThat(materialized).as("large real HSQL CLOB must not be materialized with getString").isFalse();
                            assertThat(readCount.get()).isPositive().isLessThanOrEqualTo(expected.length()+1);
                        } finally{rows.update("update restore_identity_evidence set record_json=? where record_kind='installation'",expected);}
                    });
        }
    }
    private static javax.sql.DataSource lobObserved(JDBCDataSource database,java.util.concurrent.atomic.AtomicBoolean materialized,java.util.concurrent.atomic.AtomicInteger readCount) {
        return new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{
                var connection=database.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,arguments)->{
                    Object value=invoke(method,connection,arguments);
                    if(!method.getName().equals("prepareStatement")||!((String)arguments[0]).startsWith("select record_kind,record_key,record_json"))return value;
                    var statement=(java.sql.PreparedStatement)value;return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(statementProxy,statementMethod,statementArguments)->{
                        Object result=invoke(statementMethod,statement,statementArguments);
                        if(!statementMethod.getName().equals("executeQuery"))return result;
                        var rows=(java.sql.ResultSet)result;return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(rowsProxy,rowsMethod,rowsArguments)->{
                            boolean lob=rowsArguments!=null&&rowsArguments.length==1&&Objects.equals(rowsArguments[0],3)&&"installation".equals(rows.getString(1));
                            if(lob&&rowsMethod.getName().equals("getString"))materialized.set(true);
                            Object field=invoke(rowsMethod,rows,rowsArguments);
                            if(!lob||!rowsMethod.getName().equals("getCharacterStream"))return field;
                            return new FilterReader((Reader)field){
                                @Override public int read(char[] buffer,int offset,int length)throws IOException{int count=super.read(buffer,offset,length);if(count>0)readCount.addAndGet(count);return count;}
                                @Override public int read()throws IOException{int value=super.read();if(value>=0)readCount.incrementAndGet();return value;}
                            };
                        });
                    });
                });
            }
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
        };
    }
    private static Object invoke(java.lang.reflect.Method method,Object target,Object[] arguments)throws Throwable {
        try{return method.invoke(target,arguments);}catch(java.lang.reflect.InvocationTargetException wrapper){throw wrapper.getCause();}
    }
    private JDBCDataSource source()throws Exception {
        var source=database();RestoreSchemaMigration.bootstrap(database(),source,()->{});var rows=new JdbcTemplate(source);
        for(var id:List.of(first,second)) {
            String scope=id.equals(first)?"source-alex":"source-other-alex";
            rows.update("insert into app_principal(principal_id,scope_key,enabled) values(?,?,1)",id.value().toString(),scope);
            rows.update("insert into app_user(principal_id,username,display_name,email,enabled,must_change_password,password_hash) values(?,?,'Alex','same@example.test',true,false,'source-private-hash')",id.value().toString(),scope);
            var binding=new IdentityBinding(IdentityBinding.Kind.OIDC,id.equals(first)?"https://issuer-a.test":"https://issuer-b.test","same-subject");
            rows.update("insert into principal_binding values(?,?,?,?,?,1)",PrincipalBinding.key(binding),id.value().toString(),binding.kind().name(),binding.issuer(),binding.subject());
        }
        rows.update("insert into app_role(name) values('ADMIN')");rows.update("insert into user_roles(user_id,role_id) select u.id,r.id from app_user u,app_role r where u.principal_id=?",first.value().toString());
        rows.update("insert into backup_capability_grant values(?,'RESTORE_INSTALLATION')",first.value().toString());return source;
    }
    private VerifiedBackup export(JDBCDataSource source)throws Exception {
        BackupMaintenanceLease.initialize(source);var prerequisite=BackupFeaturePrerequisites.discover();
        var contributor=new IdentityBackupContributor(source,(snapshot,checkpoint)->Set.of(first,second));
        var policy=new BackupAccessPolicy(){public boolean isEnabled(PrincipalId id){return true;}public boolean hasCapability(PrincipalId p,BackupCapability c,BackupScope s){return true;}public boolean canRead(PrincipalId p,BackupRepositoryKey k){return true;}public boolean canReadVersion(PrincipalId p,BackupRepositoryKey k,String c){return true;}};
        var authorization=new BackupAuthorizationService(policy,Clock.systemUTC());var request=new BackupRequest(BackupProfile.INSTALLATION_CURRENT,new BackupScope.Installation(),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        String installation=new JdbcTemplate(source).queryForObject("select installation_id from principal_installation",String.class);
        var plan=new BackupSnapshotCoordinator.CapturePlan("1.5.1-SNAPSHOT","identity-fixture",installation,List.of(),Map.of(new BackupComponentId("application"),new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of())),List.of(),List.of(),new SourceDatabaseMetadata(1,SourceDatabaseFamily.HSQLDB));
        var coordinator=new BackupSnapshotCoordinator(new BackupMaintenanceLease(source,Duration.ofSeconds(30)),authorization,ignored->plan,List.of(contributor),root.resolve("spool"),BackupSnapshotCoordinator.CaptureLimits.defaults(),Clock.systemUTC(),prerequisite);
        Path output=root.resolve(UUID.randomUUID()+".taxbackup");try(var captured=coordinator.capture(authorization.authorize(first,request))) {new BackupArchiveWriter(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),prerequisite).write(captured,output);}
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),"1.5.1-SNAPSHOT",Map.of(new BackupComponentId("application"),1,new BackupComponentId("capture-proof"),1),prerequisite).verify(output);
    }
    private static List<Class<?>> model()throws Exception {
        try(var input=RestoreIdentityImportTest.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")) {
            var result=new ArrayList<Class<?>>();for(String name:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().toList())if(!name.isBlank()&&!name.startsWith("#"))result.add(Class.forName(name));return result;
        }
    }
    private static JDBCDataSource database(){var ds=new JDBCDataSource();ds.setURL("jdbc:hsqldb:mem:identity-restore-"+UUID.randomUUID()+";hsqldb.tx=mvcc");ds.setUser("sa");return ds;}
}
