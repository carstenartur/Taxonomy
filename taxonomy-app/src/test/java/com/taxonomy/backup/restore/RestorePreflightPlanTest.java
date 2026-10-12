package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.runtime.*;
import com.taxonomy.backup.snapshot.*;
import com.taxonomy.security.backup.IdentityBackupContributor;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Actual verified archive and three physical HSQL databases; planning never stages owner rows. */
class RestorePreflightPlanTest {
    @TempDir Path root;
    private final PrincipalId actor=PrincipalId.create();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-11T08:00:00Z"),ZoneOffset.UTC);
    private final String application="1.5.1-SNAPSHOT";

    @Test void closedOwnerMatrixProducesConcretePrivatePlanAndCannotApproveOrWriteTarget()throws Exception {
        var source=source();var target=database();var policy=new Policy();
        var authority=new BackupAuthorizationService(policy,clock);
        try(var archive=archive(source,authority)) {
            var allocation=new RestorePrincipalAllocation(PrincipalId.create(),"explicit-private-actor",RestorePrincipalAllocation.Disposition.QUARANTINE);
            var generation=UUID.randomUUID();
            var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
            var plan=planner.plan(archive,source,target,generation,Map.of(),Map.of(actor,allocation),RestoreMode.EMPTY_TARGET,actor,()->{});
            assertThat(plan.archiveSha256()).isEqualTo(archive.archiveSha256());
            assertThat(plan.generationId()).isEqualTo(generation);
            assertThat(plan.sourceFamily()).isEqualTo(SourceDatabaseFamily.HSQLDB);
            assertThat(plan.targetIdentity()).isEqualTo(TargetDatabaseIdentity.inspect(target,()->{}));
            assertThat(plan.principals()).singleElement().satisfies(mapping->{
                assertThat(mapping.source()).isEqualTo(actor);assertThat(mapping.allocation()).isEqualTo(allocation);
            });
            assertThat(plan.blockers()).extracting(RestoreDiagnostic::code).contains("UNSUPPORTED_MATRIX","UNREGISTERED_OWNER");
            var control=database();var store=RestorePlanStore.open(source,target,control,authority,clock);
            store.save(plan,actor,()->{});
            assertThat(RestorePlanStore.open(source,target,control,authority,clock).load(plan.planId(),actor,()->{})).isEqualTo(plan);
            assertThatThrownBy(()->store.approve(plan.planId(),actor,plan.sha256(),archive,source,target,planner,()->{}))
                    .isInstanceOf(IOException.class).hasMessageContaining("unsupported");
            assertThat(TargetSchemaEvidence.inspect(target,()->{}).tableNames()).isEmpty();
            assertThat(new JdbcTemplate(source).queryForObject("select count(*) from app_principal",Long.class)).isEqualTo(1);
        }
    }
    @Test void sourceTargetAliasAndNonemptyTargetFailBeforeAnyPlanCanAuthorizeMutation()throws Exception {
        var source=source();var target=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
        try(var archive=archive(source,authority)) {
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            assertThatThrownBy(()->planner.plan(archive,source,source,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("isolated");
            new JdbcTemplate(target).execute("create table existing_private(id integer)");
            assertThatThrownBy(()->planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("empty");
            assertThat(new JdbcTemplate(target).queryForObject("select count(*) from existing_private",Long.class)).isZero();
        }
    }
    @Test void privateDurablePlanRejectsPayloadTamperAndCurrentAuthorityRevocation()throws Exception {
        var source=source();var target=database();var control=database();var policy=new Policy();var authority=new BackupAuthorizationService(policy,clock);
        var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
        try(var archive=archive(source,authority)) {
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            var plan=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            var store=RestorePlanStore.open(source,target,control,authority,clock);store.save(plan,actor,()->{});
            policy.enabled=false;
            assertThatThrownBy(()->store.load(plan.planId(),actor,()->{})).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            policy.enabled=true;
            var rows=new JdbcTemplate(control);String json=rows.queryForObject("select plan_json from restore_control_plan",String.class);
            rows.update("update restore_control_plan set plan_json=?",json.replace("explicit-target","other-namespace"));
            assertThatThrownBy(()->store.load(plan.planId(),actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("proof");
            assertThat(TargetSchemaEvidence.inspect(target,()->{}).tableNames()).isEmpty();
        }
    }
    @Test void changedTargetCannotBePersistedAndControlNeverAliasesSourceOrTarget()throws Exception {
        var source=source();var target=database();var control=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        try(var archive=archive(source,authority)) {
            var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            var plan=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            assertThatThrownBy(()->RestorePlanStore.open(source,target,source,authority,clock)).isInstanceOf(IOException.class).hasMessageContaining("isolated");
            assertThatThrownBy(()->RestorePlanStore.open(source,target,target,authority,clock)).isInstanceOf(IOException.class).hasMessageContaining("isolated");
            var store=RestorePlanStore.open(source,target,control,authority,clock);
            new JdbcTemplate(target).execute("create table unplanned_table(id integer)");
            assertThatThrownBy(()->store.save(plan,actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("target proof");
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_plan",Long.class)).isZero();
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_generation",Long.class)).isZero();
        }
    }
    @Test void expiredOrForeignPlansAndReusedPhysicalGenerationCannotBeAdopted()throws Exception {
        var source=source();var target=database();var control=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        try(var archive=archive(source,authority)) {
            var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            var plan=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            var store=RestorePlanStore.open(source,target,control,authority,clock);store.save(plan,actor,()->{});store.save(plan,actor,()->{});
            var expired=RestorePlanStore.open(source,target,control,authority,Clock.offset(clock,Duration.ofMinutes(16)));
            assertThatThrownBy(()->expired.load(plan.planId(),actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("expired");
            assertThatThrownBy(()->store.load(plan.planId(),PrincipalId.create(),()->{})).isInstanceOf(IOException.class).hasMessageContaining("unavailable");
            var next=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            assertThatThrownBy(()->store.save(next,actor,()->{})).isInstanceOf(IOException.class);
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_plan",Long.class)).isEqualTo(1);
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_generation",Long.class)).isEqualTo(1);
        }
    }
    @Test void actualControlConnectionCannotBeReroutedAfterOpeningPrivateStore()throws Exception {
        var source=source();var target=database();var control=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var routed=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{return (armed.get()?source:control).getConnection();}
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
        };
        try(var archive=archive(source,authority)) {
            var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            var plan=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            var store=RestorePlanStore.open(source,target,routed,authority,clock);armed.set(true);
            assertThatThrownBy(()->store.save(plan,actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("binding changed");
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_plan",Long.class)).isZero();
            assertThat(new JdbcTemplate(source).queryForObject("select count(*) from app_principal",Long.class)).isEqualTo(1);
            assertThat(TargetSchemaEvidence.inspect(target,()->{}).tableNames()).isEmpty();
        }
    }
    @Test void cancellationPreservesPrimaryFailureAndCommittedDraftResumesWithoutNewAllocation()throws Exception {
        var source=source();var target=database();var control=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        var begun=new java.util.concurrent.atomic.AtomicBoolean();
        var guarded=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                var connection=control.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,arguments)->{
                    if(method.getName().equals("prepareStatement")&&((String)arguments[0]).startsWith("insert into restore_control_plan"))begun.set(true);
                    if(method.getName().equals("rollback")&&begun.get()){connection.rollback();throw new java.sql.SQLException("Injected control cleanup failure");}
                    try{return method.invoke(connection,arguments);}catch(java.lang.reflect.InvocationTargetException wrapper){throw wrapper.getCause();}
                });
            }
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
        };
        try(var archive=archive(source,authority)) {
            var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            var plan=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            var store=RestorePlanStore.open(source,target,guarded,authority,clock);
            Throwable cancelled=catchThrowable(()->store.save(plan,actor,()->{if(begun.get())throw new InterruptedIOException("Cancel pending private draft");}));
            assertThat(cancelled).isInstanceOf(InterruptedIOException.class);
            assertThat(cancelled.getSuppressed()).anyMatch(error->error instanceof java.sql.SQLException&&error.getMessage().equals("Injected control cleanup failure"));
            assertThat(Thread.interrupted()).isTrue();var rows=new JdbcTemplate(control);
            assertThat(rows.queryForObject("select count(*) from restore_control_plan",Long.class)).isZero();
            assertThat(rows.queryForObject("select count(*) from restore_control_generation",Long.class)).isZero();
            var durable=RestorePlanStore.open(source,target,control,authority,clock);
            assertThatThrownBy(()->durable.save(plan,actor,()->{if(rows.queryForObject("select count(*) from restore_control_plan",Long.class)==1)throw new InterruptedIOException("Lost commit acknowledgement");})).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.interrupted()).isTrue();
            var reopened=RestorePlanStore.open(source,target,control,authority,clock);reopened.save(plan,actor,()->{});
            assertThat(reopened.load(plan.planId(),actor,()->{})).isEqualTo(plan);
            assertThat(rows.queryForObject("select count(*) from restore_control_plan",Long.class)).isEqualTo(1);
            assertThat(rows.queryForObject("select count(*) from restore_control_generation",Long.class)).isEqualTo(1);
        }
    }
    @Test void activeDatabaseCannotBeReroutedIntoControlAfterOpeningPrivateStore()throws Exception {
        var source=source();var target=database();var control=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var active=new org.springframework.jdbc.datasource.AbstractDataSource(){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException{return (armed.get()?control:source).getConnection();}
            @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
        };
        try(var archive=archive(source,authority)) {
            var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
            var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
            var plan=planner.plan(archive,active,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            var store=RestorePlanStore.open(active,target,control,authority,clock);armed.set(true);
            assertThatThrownBy(()->store.save(plan,actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("isolated");
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_plan",Long.class)).isZero();
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_generation",Long.class)).isZero();
            assertThat(new JdbcTemplate(source).queryForObject("select count(*) from app_principal",Long.class)).isEqualTo(1);
            assertThat(TargetSchemaEvidence.inspect(target,()->{}).tableNames()).isEmpty();
            armed.set(false);
            var lateControl=new org.springframework.jdbc.datasource.AbstractDataSource(){
                @Override public java.sql.Connection getConnection()throws java.sql.SQLException {
                    var connection=control.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,arguments)->{
                        if(method.getName().equals("prepareStatement")&&((String)arguments[0]).startsWith("insert into restore_control_plan"))armed.set(true);
                        try{return method.invoke(connection,arguments);}catch(java.lang.reflect.InvocationTargetException wrapper){throw wrapper.getCause();}
                    });
                }
                @Override public java.sql.Connection getConnection(String user,String password)throws java.sql.SQLException{throw new java.sql.SQLException("Explicit credentials unsupported");}
            };
            var lateStore=RestorePlanStore.open(active,target,lateControl,authority,clock);
            assertThatThrownBy(()->lateStore.save(plan,actor,()->{})).isInstanceOf(IOException.class).hasMessageContaining("isolated");
            assertThat(armed).isTrue();
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_plan",Long.class)).isZero();
            assertThat(new JdbcTemplate(control).queryForObject("select count(*) from restore_control_generation",Long.class)).isZero();
        }
    }
    @Test void legacySourceFamilyNeedsExactAuditedActorArchiveAndTimeBinding()throws Exception {
        var source=source();var target=database();var authority=new BackupAuthorizationService(new Policy(),clock);
        var planner=new RestorePlanner(authority,clock,application,RestoreSupportRegistry.closed());
        var mapping=Map.of(actor,new RestorePrincipalAllocation(PrincipalId.create(),"explicit-target",RestorePrincipalAllocation.Disposition.EXPLICIT_TARGET));
        try(var archive=archive(source,authority,false)) {
            var missing=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,()->{});
            assertThat(missing.sourceFamily()).isNull();assertThat(missing.blockers()).extracting(RestoreDiagnostic::code).contains("SOURCE_DATABASE_FAMILY_REQUIRED");
            var declaration=new AuditedSourceFamilyDeclaration(SourceDatabaseFamily.HSQLDB,archive.archiveSha256(),actor,clock.instant().toString(),"Operator inspected original installation database metadata");
            var declared=planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,declaration,()->{});
            assertThat(declared.sourceFamily()).isEqualTo(SourceDatabaseFamily.HSQLDB);assertThat(declared.sourceFamilyWitnessed()).isFalse();
            assertThat(declared.sourceFamilyDeclaration()).isEqualTo(declaration);
            assertThat(declared.blockers()).extracting(RestoreDiagnostic::code).doesNotContain("SOURCE_DATABASE_FAMILY_REQUIRED").contains("UNSUPPORTED_MATRIX");
            assertThat(RestorePlanCodec.decode(RestorePlanCodec.encode(declared))).isEqualTo(declared);
            assertThatThrownBy(()->planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,
                    new AuditedSourceFamilyDeclaration(SourceDatabaseFamily.HSQLDB,"a".repeat(64),actor,clock.instant().toString(),"Reviewed source"),()->{})).isInstanceOf(IOException.class).hasMessageContaining("declaration");
            assertThatThrownBy(()->planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,
                    new AuditedSourceFamilyDeclaration(SourceDatabaseFamily.HSQLDB,archive.archiveSha256(),PrincipalId.create(),clock.instant().toString(),"Reviewed source"),()->{})).isInstanceOf(IOException.class).hasMessageContaining("declaration");
            assertThatThrownBy(()->planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,
                    new AuditedSourceFamilyDeclaration(SourceDatabaseFamily.HSQLDB,archive.archiveSha256(),actor,clock.instant().minusSeconds(901).toString(),"Reviewed source"),()->{})).isInstanceOf(IOException.class).hasMessageContaining("declaration");
        }
        try(var archive=archive(source,authority)) {
            var declaration=new AuditedSourceFamilyDeclaration(SourceDatabaseFamily.POSTGRESQL,archive.archiveSha256(),actor,clock.instant().toString(),"Operator declaration cannot override witnessed source");
            assertThatThrownBy(()->planner.plan(archive,source,target,UUID.randomUUID(),Map.of(),mapping,RestoreMode.EMPTY_TARGET,actor,declaration,()->{})).isInstanceOf(IOException.class).hasMessageContaining("declaration");
        }
    }
    private JDBCDataSource source()throws Exception {
        var source=database();RestoreSchemaMigration.bootstrap(database(),source,()->{});
        new JdbcTemplate(source).update("insert into app_principal(principal_id,scope_key,enabled) values(?,'source-actor',1)",actor.value().toString());return source;
    }
    private VerifiedBackup archive(JDBCDataSource source,BackupAuthorizationService authority)throws Exception {
        return archive(source,authority,true);
    }
    private VerifiedBackup archive(JDBCDataSource source,BackupAuthorizationService authority,boolean sourceWitness)throws Exception {
        BackupMaintenanceLease.initialize(source);var prerequisites=BackupFeaturePrerequisites.discover();
        var request=new BackupRequest(BackupProfile.INSTALLATION_CURRENT,new BackupScope.Installation(),new BackupTime.Current(),GitRepresentation.NONE,SecretsSelection.EXCLUDE);
        String installation=new JdbcTemplate(source).queryForObject("select installation_id from principal_installation",String.class);
        var capture=new BackupSnapshotCoordinator.CapturePlan(application,"preflight-fixture",installation,List.of(),
                Map.of(new BackupComponentId("application"),new BackupSnapshotCoordinator.CaptureComponent(1,BackupCompleteness.COMPLETE,Set.of())),List.of(),List.of(),sourceWitness?new SourceDatabaseMetadata(1,SourceDatabaseFamily.HSQLDB):null);
        var coordinator=new BackupSnapshotCoordinator(new BackupMaintenanceLease(source,Duration.ofSeconds(30)),authority,ignored->capture,
                List.of(new IdentityBackupContributor(source,(snapshot,checkpoint)->Set.of(actor))),root.resolve("spool"),BackupSnapshotCoordinator.CaptureLimits.defaults(),clock,prerequisites);
        Path output=root.resolve(UUID.randomUUID()+".taxbackup");try(var captured=coordinator.capture(authority.authorize(actor,request))){new BackupArchiveWriter(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),prerequisites).write(captured,output);}
        return new BackupArchiveReader(ArchiveProtectionProvider.unprotected(),ArchiveLimits.defaults(),application,
                Map.of(new BackupComponentId("application"),1,new BackupComponentId("capture-proof"),1),prerequisites).verify(output);
    }
    private static JDBCDataSource database(){var ds=new JDBCDataSource();ds.setURL("jdbc:hsqldb:mem:restore-plan-"+UUID.randomUUID()+";hsqldb.tx=mvcc");ds.setUser("sa");return ds;}
    private static final class Policy implements BackupAccessPolicy {
        boolean enabled=true;
        @Override public boolean isEnabled(PrincipalId principal){return enabled;}
        @Override public boolean hasCapability(PrincipalId principal,BackupCapability capability,BackupScope scope){return true;}
        @Override public boolean canRead(PrincipalId principal,BackupRepositoryKey repository){return true;}
        @Override public boolean canReadVersion(PrincipalId principal,BackupRepositoryKey repository,String commit){return true;}
    }
}
