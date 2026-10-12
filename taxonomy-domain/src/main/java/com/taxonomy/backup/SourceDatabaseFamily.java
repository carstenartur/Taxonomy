package com.taxonomy.backup;

import java.util.Locale;

/** Portable source-family evidence; never a connection address or a compatibility promise. */
public enum SourceDatabaseFamily {
    HSQLDB, POSTGRESQL, SQL_SERVER, ORACLE;

    public static SourceDatabaseFamily fromProductName(String product) {
        if (product == null) throw new IllegalArgumentException("Unsupported database family");
        return switch (product.toUpperCase(Locale.ROOT)) {
            case "HSQL DATABASE ENGINE" -> HSQLDB;
            case "POSTGRESQL" -> POSTGRESQL;
            case "MICROSOFT SQL SERVER" -> SQL_SERVER;
            case "ORACLE" -> ORACLE;
            default -> throw new IllegalArgumentException("Unsupported database family");
        };
    }
}
