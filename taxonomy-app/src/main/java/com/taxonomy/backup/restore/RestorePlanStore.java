package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.backup.runtime.BackupAuthorizationService;
import com.taxonomy.identity.StableIdentityHash;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Versioned private plans in a third physical database. No staging or source entity writes. */
public final class RestorePlanStore {
    private static final String HISTORY="restore_control_schema_history";
    private final DataSource active,target,control;private final TargetDatabaseIdentity identity;private final BackupAuthorizationService authority;private final Clock clock;
    private RestorePlanStore(DataSource active,DataSource target,DataSource control,TargetDatabaseIdentity identity,BackupAuthorizationService authority,Clock clock) {
        this.active=active;this.target=target;this.control=control;this.identity=identity;this.authority=authority;this.clock=clock;
    }
    public static RestorePlanStore open(DataSource active,DataSource target,DataSource control,BackupAuthorizationService authority,Clock clock)throws IOException {
        var identity=TargetDatabaseIdentity.proveIsolated(active,control,()->{});
        if(identity.destinationFingerprint().equals(TargetDatabaseIdentity.inspect(target,()->{}).destinationFingerprint()))throw new IOException("Restore control database is not isolated from staging");
        try(var connection=control.getConnection()) {
            if(!identity.equals(TargetDatabaseIdentity.inspectConnection(connection)))throw new IOException("Restore control database binding changed");
            requireControlIsolation(active,target,connection);
            var pinned=new SingleConnectionDataSource(connection,true);var evidence=TargetSchemaEvidence.inspect(pinned,()->{});
            var expected=Set.of(HISTORY,"RESTORE_CONTROL_PLAN","RESTORE_CONTROL_GENERATION");
            if(!evidence.tableNames().isEmpty()&&!evidence.tableNames().equals(expected))throw new IOException("Restore control schema is not a supported private store");
            connection.setReadOnly(false);connection.setAutoCommit(true);
            var migrations=Flyway.configure().dataSource(pinned).table(HISTORY).locations("classpath:db/restore-control/hsqldb").baselineOnMigrate(false).load();
            migrations.migrate();migrations.validate();var applied=migrations.info().applied();
            if(applied.length!=1||!"1".equals(applied[0].getVersion().toString()))throw new IOException("Restore control schema validation failed");
            if(!TargetSchemaEvidence.inspect(pinned,()->{}).tableNames().equals(expected))throw new IOException("Restore control schema validation failed");
            return new RestorePlanStore(active,target,control,identity,Objects.requireNonNull(authority),Objects.requireNonNull(clock));
        } catch(SQLException|RuntimeException failure){throw new IOException("Restore control schema migration failed");}
    }
    public void save(RestorePlan plan,PrincipalId actor,BackupCheckpoint checkpoint)throws IOException {
        requireActor(plan,actor);requireLive(plan);checkpoint.check();
        if(!plan.targetIdentity().equals(TargetDatabaseIdentity.proveIsolated(active,target,checkpoint))
                ||!plan.targetSchemaSha256().equals(TargetSchemaEvidence.inspect(target,checkpoint).schemaSha256()))throw new IOException("Restore plan target proof changed");
        if(identity.destinationFingerprint().equals(plan.targetIdentity().destinationFingerprint()))throw new IOException("Restore control database is not isolated from staging");
        String json=RestorePlanCodec.encode(plan),sha=plan.sha256();
        try(var connection=connection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var rollback=new Rollback(connection)) {
                var old=read(connection,plan.planId(),actor,checkpoint);
                if(old!=null){if(!old.plan().equals(plan))throw new IOException("Restore plan identity is already bound to another payload");return;}
                update(connection,"insert into restore_control_plan(plan_id,actor_principal,plan_sha256,plan_json,created_at,expires_at,approval_state,approved_sha256,row_version) values(?,?,?,?,?,?,'DRAFT',null,0)",
                        plan.planId().toString(),actor.value().toString(),sha,json,plan.createdAt(),plan.expiresAt());
                update(connection,"insert into restore_control_generation(target_database_sha256,generation_id,plan_id) values(?,?,?)",plan.targetIdentity().destinationFingerprint(),plan.generationId().toString(),plan.planId().toString());
                checkpoint.check();requireControlIsolation(active,target,connection);connection.commit();rollback.committed=true;checkpoint.check();
            }
        } catch(InterruptedIOException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        catch(SQLException|RuntimeException failure){throw new IOException("Restore plan persistence failed");}
    }
    public RestorePlan load(UUID planId,PrincipalId actor,BackupCheckpoint checkpoint)throws IOException {
        requireAuthority(actor);checkpoint.check();
        try(var connection=connection()) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            try(var rollback=new Rollback(connection)) {
                var row=read(connection,planId,actor,checkpoint);if(row==null)throw new IOException("Restore plan is unavailable");
                requireActor(row.plan(),actor);requireLive(row.plan());checkpoint.check();return row.plan();
            }
        } catch(InterruptedIOException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        catch(SQLException|RuntimeException failure){throw new IOException("Restore plan access failed");}
    }
    public void approve(UUID planId,PrincipalId actor,String expectedSha,VerifiedBackup archive,DataSource active,DataSource target,
                        RestorePlanner planner,BackupCheckpoint checkpoint)throws IOException {
        var plan=load(planId,actor,checkpoint);planner.requireAuthority(actor);
        if(!plan.sha256().equals(expectedSha)||!plan.archiveSha256().equals(archive.archiveSha256()))throw new IOException("Restore approval payload proof changed");
        if(!plan.targetIdentity().equals(TargetDatabaseIdentity.proveIsolated(active,target,checkpoint))
                ||!plan.targetSchemaSha256().equals(TargetSchemaEvidence.inspect(target,checkpoint).schemaSha256()))throw new IOException("Restore approval target proof changed");
        // Closed registry refuses before any approval update. Concrete owner/matrix composition must replace it.
        planner.requireSupported(plan);
        try(var connection=connection()) {
            connection.setAutoCommit(false);connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try(var rollback=new Rollback(connection)) {
                var row=read(connection,planId,actor,checkpoint);if(row==null||!row.plan().equals(plan))throw new IOException("Restore approval payload proof changed");
                requireLive(row.plan());requireAuthority(actor);checkpoint.check();
                update(connection,"update restore_control_plan set approval_state='APPROVED',approved_sha256=?,row_version=row_version+1 where plan_id=? and actor_principal=? and plan_sha256=? and row_version=? and approval_state='DRAFT'",
                        expectedSha,planId.toString(),actor.value().toString(),expectedSha,row.version());
                checkpoint.check();requireControlIsolation(active,target,connection);connection.commit();rollback.committed=true;checkpoint.check();
            }
        } catch(InterruptedIOException cancelled){Thread.currentThread().interrupt();throw cancelled;}
        catch(SQLException|RuntimeException failure){throw new IOException("Restore approval failed");}
    }
    private Connection connection()throws SQLException,IOException {
        var connection=control.getConnection();
        try{if(!identity.equals(TargetDatabaseIdentity.inspectConnection(connection)))throw new IOException("Restore control database binding changed");requireControlIsolation(active,target,connection);return connection;}
        catch(IOException|SQLException|RuntimeException failure){try{connection.close();}catch(SQLException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private Row read(Connection connection,UUID planId,PrincipalId actor,BackupCheckpoint checkpoint)throws SQLException,IOException {
        try(var statement=statement(connection,"select plan_sha256,plan_json,created_at,expires_at,approval_state,approved_sha256,row_version from restore_control_plan where plan_id=? and actor_principal=?")) {
            statement.setString(1,planId.toString());statement.setString(2,actor.value().toString());checkpoint.check();
            try(var rows=statement.executeQuery()) {
                if(!rows.next())return null;String sha=rows.getString(1),json=bounded(rows.getCharacterStream(2),checkpoint);var plan=RestorePlanCodec.decode(json);
                if(!StableIdentityHash.sha256(json).equals(sha)||!plan.sha256().equals(sha)||!plan.planId().equals(planId)||!plan.actor().equals(actor)
                        ||!plan.createdAt().equals(rows.getString(3))||!plan.expiresAt().equals(rows.getString(4)))throw new IOException("Restore plan payload proof changed");
                String state=rows.getString(5),approved=rows.getString(6);long version=rows.getLong(7);
                if(version<0||!(state.equals("DRAFT")&&approved==null||state.equals("APPROVED")&&sha.equals(approved)||state.equals("REVOKED")&&approved==null))throw new IOException("Restore plan approval proof changed");
                if(rows.next())throw new IOException("Restore plan inventory proof changed");
                try(var binding=statement(connection,"select generation_id,plan_id from restore_control_generation where target_database_sha256=?")) {
                    binding.setString(1,plan.targetIdentity().destinationFingerprint());
                    try(var allocation=binding.executeQuery()) {
                        if(!allocation.next()||!plan.generationId().toString().equals(allocation.getString(1))||!planId.toString().equals(allocation.getString(2))||allocation.next())throw new IOException("Restore plan generation proof changed");
                    }
                }
                checkpoint.check();return new Row(plan,state,version);
            }
        }
    }
    private void requireActor(RestorePlan plan,PrincipalId actor){requireAuthority(actor);if(!plan.actor().equals(actor))throw new org.springframework.security.access.AccessDeniedException("Restore plan access denied");}
    private void requireAuthority(PrincipalId actor){authority.require(actor,BackupCapability.RESTORE_INSTALLATION,new BackupScope.Installation());}
    private void requireLive(RestorePlan plan)throws IOException {
        Instant now=clock.instant();if(now.isBefore(Instant.parse(plan.createdAt()))||!now.isBefore(Instant.parse(plan.expiresAt())))throw new IOException("Restore plan has expired or its clock binding is invalid");
    }
    private static PreparedStatement statement(Connection connection,String sql)throws SQLException {var statement=connection.prepareStatement(sql);statement.setMaxRows(2);statement.setQueryTimeout(60);return statement;}
    private void update(Connection connection,String sql,Object...values)throws SQLException,IOException {
        try(var statement=statement(connection,sql)){for(int index=0;index<values.length;index++)statement.setObject(index+1,values[index]);requireControlIsolation(active,target,connection);if(statement.executeUpdate()!=1)throw new SQLException("Changed restore plan version");}
    }
    private static void requireControlIsolation(DataSource active,DataSource target,Connection control)throws SQLException,IOException {
        String actual=TargetDatabaseIdentity.inspectConnection(control).destinationFingerprint();
        if(actual.equals(TargetDatabaseIdentity.inspect(active,()->{}).destinationFingerprint())
                ||actual.equals(TargetDatabaseIdentity.inspect(target,()->{}).destinationFingerprint()))throw new IOException("Restore control database is not isolated from active or staging");
    }
    private static String bounded(Reader reader,BackupCheckpoint checkpoint)throws IOException {
        if(reader==null)throw new IOException("Restore plan payload proof is missing");
        try(reader) {
            var result=new StringBuilder();int bytes=0,pendingHigh=0;
            for(;;) {
                if((result.length()&4095)==0)checkpoint.check();int value=reader.read();
                if(value<0){if(pendingHigh!=0)bytes+=3;if(bytes>RestorePlanCodec.MAX_BYTES)throw budget();return result.toString();}
                char character=(char)value;
                if(pendingHigh!=0){if(Character.isLowSurrogate(character)){bytes+=4;pendingHigh=0;}else{bytes+=3;pendingHigh=0;bytes+=character<=127?1:character<=2047?2:3;}}
                else if(Character.isHighSurrogate(character))pendingHigh=character;
                else bytes+=character<=127?1:character<=2047?2:3;
                if(bytes>RestorePlanCodec.MAX_BYTES||result.length()>=RestorePlanCodec.MAX_BYTES)throw budget();result.append(character);
            }
        }
    }
    private static IOException budget(){return new IOException("Restore plan payload proof exceeds its budget");}
    private record Row(RestorePlan plan,String state,long version) { }
    private static final class Rollback implements AutoCloseable {
        private final Connection connection;boolean committed;Rollback(Connection connection){this.connection=connection;}
        @Override public void close()throws SQLException{if(!committed)connection.rollback();}
    }
}
