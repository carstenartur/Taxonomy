package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.composition.persistence.CatalogueSourceSchemaMigration;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.workspace.storage.StagedJgitSchemaMigration;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Fresh, physically isolated HSQL staging only. Neither legacy adoption nor activation. */
public final class RestoreSchemaMigration {
    private static final String LOCATION="classpath:db/restore/hsqldb";
    private RestoreSchemaMigration() { }

    public static void bootstrap(DataSource active,DataSource candidate,BackupCheckpoint checkpoint) throws IOException {
        try {
            checkpoint.check();
            var activeIdentity=TargetDatabaseIdentity.inspect(active,checkpoint);
            // The very connection proved before DDL is used by every owner migration.
            try(var connection=candidate.getConnection()) {
                var target=TargetDatabaseIdentity.inspectConnection(connection);
                requireIsolated(activeIdentity,target); requireFresh(connection,checkpoint);
                var pinned=new SingleConnectionDataSource(connection,true);
                // Later restore ledgers refer to independently versioned principal tables.
                business(pinned,"1").migrate(); checkpoint.check();
                PrincipalSchemaMigration.migrate(pinned); checkpoint.check();
                business(pinned).migrate(); checkpoint.check();
                CatalogueSourceSchemaMigration.migrate(pinned); checkpoint.check();
                StagedJgitSchemaMigration.migrate(pinned,checkpoint);
                if(!target.equals(TargetDatabaseIdentity.inspectConnection(connection)))throw new IOException("Staged restore database binding changed");
                checkpoint.check();
            }
        } catch(InterruptedIOException cancelled) { throw cancelled(); }
        catch(IOException failure) { throw failure; }
        catch(SQLException|RuntimeException failure) { throw new IOException("Staged restore schema migration failed"); }
    }

    public static SessionFactory openValidatedFactory(DataSource active,DataSource candidate,Collection<Class<?>> model,
                                                       BackupCheckpoint checkpoint) throws IOException {
        requireModel(model); SessionFactory factory=null;
        try {
            var target=TargetDatabaseIdentity.proveIsolated(active,candidate,checkpoint);
            var bound=bound(candidate,target);
            checkpoint.check(); var business=business(bound); business.validate();
            var applied=business.info().applied();
            if(!Arrays.stream(applied).map(migration->migration.getVersion().toString()).toList().equals(List.of("1","2","3","4","5","6","7","8","9","10","11","12","13")))
                throw new IOException("Staged restore schema validation failed");
            // Structural proof rejects unsupported triggers/views before any factory can import rows.
            TargetSchemaEvidence.inspect(bound,checkpoint);
            var configuration=new org.hibernate.cfg.Configuration();
            configuration.getProperties().put("hibernate.connection.datasource",bound);
            configuration.setProperty("hibernate.hbm2ddl.auto","validate");
            configuration.setProperty("hibernate.jpa_callbacks.enabled","false");
            configuration.setProperty("hibernate.search.enabled","false");
            configuration.setPhysicalNamingStrategy(new org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy());
            for(var type:model) { checkpoint.check(); configuration.addAnnotatedClass(type); }
            StagedJgitSchemaMigration.configureModel(configuration);
            factory=configuration.buildSessionFactory(); checkpoint.check(); return factory;
        } catch(InterruptedIOException cancelled) { closeAfterFailure(factory,cancelled); throw cancelled(); }
        catch(IOException|RuntimeException failure) {
            closeAfterFailure(factory,failure); throw new IOException("Staged restore schema validation failed");
        }
    }

    private static Flyway business(DataSource database) {
        return Flyway.configure().dataSource(database).table("restore_business_schema_history")
                .locations(LOCATION).baselineOnMigrate(false).load();
    }
    private static Flyway business(DataSource database,String target) {
        return Flyway.configure().dataSource(database).table("restore_business_schema_history")
                .locations(LOCATION).baselineOnMigrate(false).target(target).load();
    }
    private static void requireFresh(Connection connection,BackupCheckpoint checkpoint) throws SQLException,IOException {
        if(!"PUBLIC".equals(connection.getSchema()))throw new IOException("Staged restore target must be an empty supported schema");
        try(var tables=connection.getMetaData().getTables(null,null,"%",new String[]{"TABLE","VIEW"})) {
            while(tables.next()) { checkpoint.check();
                if(!Set.of("INFORMATION_SCHEMA","SYSTEM_LOBS").contains(tables.getString("TABLE_SCHEM")))
                    throw new IOException("Staged restore target must be empty");
            }
        }
        try(var statement=connection.prepareStatement("select sequence_name from information_schema.sequences where sequence_schema not in ('INFORMATION_SCHEMA','SYSTEM_LOBS')")) {
            statement.setMaxRows(1);statement.setQueryTimeout(60);checkpoint.check();
            try(var rows=statement.executeQuery()) {if(rows.next())throw new IOException("Staged restore target must be empty");}
        }
        checkpoint.check();
    }
    private static void requireIsolated(TargetDatabaseIdentity active,TargetDatabaseIdentity target)throws IOException {
        if(active.destinationFingerprint().equals(target.destinationFingerprint()))throw new IOException("Restore target is not isolated from the active database");
    }
    private static void requireModel(Collection<Class<?>> model)throws IOException {
        if(model==null || model.isEmpty() || model.size()>BackupLimits.MAX_ITEMS)throw new IOException("Unsupported staged restore model");
        var actual=new TreeSet<String>();
        for(var type:model)if(type==null || !type.isAnnotationPresent(jakarta.persistence.Entity.class) || !actual.add(type.getName()))
            throw new IOException("Unsupported staged restore model");
        try(var input=RestoreSchemaMigration.class.getResourceAsStream("/db/restore/hsqldb/approved-business-model.txt")) {
            if(input==null)throw new IOException("Unsupported staged restore model");
            var expected=new TreeSet<String>();
            byte[] bytes=input.readNBytes(65_537);
            if(bytes.length>65_536)throw new IOException("Unsupported staged restore model");
            new String(bytes,StandardCharsets.UTF_8).lines().filter(line->!line.isBlank()&&!line.startsWith("#")).forEach(expected::add);
            if(!expected.equals(actual))throw new IOException("Unsupported staged restore model");
        }
    }
    /** Every factory connection is checked before it is returned, including after mutable DS rerouting. */
    private static DataSource bound(DataSource delegate,TargetDatabaseIdentity expected) {
        return new AbstractDataSource() {
            @Override public Connection getConnection()throws SQLException { return verified(delegate.getConnection()); }
            @Override public Connection getConnection(String username,String password)throws SQLException { throw new SQLException("Staged restore connection credentials are fixed"); }
            private Connection verified(Connection connection)throws SQLException {
                try {
                    if(!expected.equals(TargetDatabaseIdentity.inspectConnection(connection)))throw new IOException("Changed restore database binding");
                    return connection;
                } catch(IOException|SQLException|RuntimeException failure) {
                    try{connection.close();}catch(SQLException cleanup){failure.addSuppressed(cleanup);}
                    throw new SQLException("Staged restore database binding changed");
                }
            }
        };
    }
    private static void closeAfterFailure(SessionFactory factory,Throwable primary) {
        if(factory!=null)try{factory.close();}catch(RuntimeException cleanup){primary.addSuppressed(cleanup);}
    }
    private static InterruptedIOException cancelled(){Thread.currentThread().interrupt();return new InterruptedIOException("Staged restore schema operation was cancelled");}
}
