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
}
