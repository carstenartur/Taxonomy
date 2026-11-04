package com.taxonomy.shared.service;

import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.HSQLDialect;
import org.hibernate.dialect.OracleDialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.dialect.SQLServerDialect;
import org.hibernate.type.StandardBasicTypes;

/** Read-only diagnostic expressions; no custom Dialect or mapped pseudo-entity. */
public final class DatabaseDiagnosticFunctions implements FunctionContributor {
    public static final String VERSION = "taxonomy_database_version";
    public static final String STORAGE = "taxonomy_database_storage";
    public static final String CURRENT_USER = "taxonomy_database_current_user";
    public static final String SESSION_USER = "taxonomy_database_session_user";
    public static final String CURRENT_DATABASE = "taxonomy_database_current_database";
    public static final String CURRENT_SCHEMA = "taxonomy_database_current_schema";
    public static final String ENGINE_START = "taxonomy_database_engine_start";

    @Override
    public void contributeFunctions(FunctionContributions contributions) {
        Dialect dialect = contributions.getDialect();
        String version = versionExpression(dialect);
        if (version == null) {
            return;
        }
        var text = contributions.getTypeConfiguration().getBasicTypeRegistry()
                .resolve(StandardBasicTypes.STRING);
        contributions.getFunctionRegistry().patternDescriptorBuilder(VERSION, version)
                .setExactArgumentCount(0).setInvariantType(text).register();
        var identity = identityExpressions(dialect);
        String[] functions = {CURRENT_USER, SESSION_USER, CURRENT_DATABASE, CURRENT_SCHEMA};
        for (int index = 0; index < functions.length; index++) {
            contributions.getFunctionRegistry().patternDescriptorBuilder(functions[index], identity[index])
                    .setExactArgumentCount(0).setInvariantType(text).register();
        }
        String engineStart = engineStartExpression(dialect);
        if (engineStart != null) {
            contributions.getFunctionRegistry().patternDescriptorBuilder(ENGINE_START, engineStart)
                    .setExactArgumentCount(0).setInvariantType(text).register();
        }
        if (!(dialect instanceof HSQLDialect)) {
            return;
        }
        String storage = "(select case when \"VALUE\" like 'mem:%' then 'IN_MEMORY' "
                    + "when \"VALUE\" like 'file:%' then 'FILE_BACKED' "
                    + "when \"VALUE\" like 'res:%' then 'READ_ONLY_RESOURCE' "
                    + "else 'UNKNOWN' end from INFORMATION_SCHEMA.SYSTEM_SESSIONINFO "
                    + "where \"KEY\" = 'DATABASE')";
        contributions.getFunctionRegistry().patternDescriptorBuilder(STORAGE, storage)
                .setExactArgumentCount(0).setInvariantType(text).register();
    }

    static String versionExpression(Dialect dialect) {
        if (dialect instanceof HSQLDialect) {
            return "database_version()";
        }
        if (dialect instanceof PostgreSQLDialect) {
            return "current_setting('server_version')";
        }
        if (dialect instanceof SQLServerDialect) {
            return "cast(serverproperty('ProductVersion') as varchar(128))";
        }
        if (dialect instanceof OracleDialect) {
            // VERSION_FULL identifies the database component; PRODUCT is a display name
            // and may start with "Oracle AI Database" rather than "Oracle Database".
            // DISTINCT also tolerates repeated component rows with the same version;
            // conflicting versions still fail the scalar query instead of picking one.
            return "(select distinct version_full from product_component_version "
                    + "where version_full is not null)";
        }
        return null;
    }

    private static String[] identityExpressions(Dialect dialect) {
        if (dialect instanceof SQLServerDialect) {
            // USER_NAME identifies the effective database principal, which can
            // differ from the server login (for example dbo or EXECUTE AS).
            return new String[]{"user_name()", "original_login()", "db_name()", "schema_name()"};
        }
        if (dialect instanceof OracleDialect) {
            // CON_NAME is the active PDB/container, not a JDBC catalog or service.
            return new String[]{"sys_context('USERENV', 'CURRENT_USER')", "sys_context('USERENV', 'SESSION_USER')",
                    "sys_context('USERENV', 'CON_NAME')", "sys_context('USERENV', 'CURRENT_SCHEMA')"};
        }
        // HSQLDB and PostgreSQL expose the SQL-standard session attributes.
        return new String[]{"current_user", "session_user", "current_catalog", "current_schema"};
    }

    private static String engineStartExpression(Dialect dialect) {
        if (dialect instanceof PostgreSQLDialect) {
            return "to_char(pg_postmaster_start_time() at time zone 'UTC', "
                    + "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
        }
        if (dialect instanceof SQLServerDialect) {
            // The DMV returns server-local datetime, without a timezone.
            return "(select convert(varchar(33), sqlserver_start_time, 126) from sys.dm_os_sys_info)";
        }
        if (dialect instanceof OracleDialect) {
            // DATE has no timezone; do not infer one from the session, DBTIMEZONE
            // or application host. Missing V$ access is an optional diagnostic.
            return "(select to_char(startup_time, 'YYYY-MM-DD\"T\"HH24:MI:SS') from v$instance)";
        }
        // The application JVM start is not a measured HSQL database start.
        return null;
    }
}
