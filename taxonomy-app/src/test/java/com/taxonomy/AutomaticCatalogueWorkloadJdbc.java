package com.taxonomy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;

/** Delegates to the original pool/driver with one observer at the returned Connection. */
public final class AutomaticCatalogueWorkloadJdbc {
    private AutomaticCatalogueWorkloadJdbc() { }

    public static Connection connection(Connection delegate) {
        if (AutomaticCatalogueWorkloadProbe.metrics() == null) return delegate;
        class ObservedConnection implements java.lang.reflect.InvocationHandler {
            private long transactionStarted;

            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                var metrics = AutomaticCatalogueWorkloadProbe.metrics();
                String name = method.getName();
                boolean transactionBoundary = name.equals("commit") || name.equals("rollback") || name.equals("setAutoCommit");
                long started = transactionBoundary ? System.nanoTime() : 0;
                Object result = AutomaticCatalogueWorkloadJdbc.invoke(delegate, method, args);
                if (metrics == null) return result;
                if (transactionBoundary) observeTransaction(metrics, name, args, started);
                if (result instanceof Statement statement) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String literal ? literal : "";
                    return statement(statement, sql);
                }
                return result;
            }

            private void observeTransaction(AutomaticCatalogueWorkloadProbe.Metrics metrics, String name, Object[] args,
                                            long started) {
                long finished = System.nanoTime();
                metrics.add("jdbc." + name, 1);
                metrics.add("jdbc." + name + "Nanos", finished - started);
                if (name.equals("setAutoCommit")) {
                    transactionStarted = Boolean.FALSE.equals(args[0]) ? started : 0;
                } else if (transactionStarted != 0 && (args == null || args.length == 0)) {
                    metrics.add("jdbc." + name + "TransactionNanos", finished - transactionStarted);
                    transactionStarted = 0;
                }
            }
        }
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, new ObservedConnection());
    }

    private static Statement statement(Statement delegate, String preparedSql) {
        class ObservedStatement implements java.lang.reflect.InvocationHandler {
            private final Sql prepared = Sql.of(preparedSql);
            private String lastSource = "unknown";

            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                var metrics = AutomaticCatalogueWorkloadProbe.metrics();
                if (metrics == null) return AutomaticCatalogueWorkloadJdbc.invoke(delegate, method, args);
                if (name.startsWith("set") && args != null && args.length >= 2
                        && args[0] instanceof Integer && delegate instanceof PreparedStatement) {
                    AutomaticCatalogueWorkloadProbe.jdbcBind();
                    if (prepared.payload() && args[1] instanceof String hash) {
                        String kind = AutomaticCatalogueWorkloadProbe.sourceKind(hash);
                        if (!kind.equals("unknown")) lastSource = kind;
                    }
                }
                if (name.equals("clearParameters")) lastSource = "unknown";
                if (name.equals("addBatch")) metrics.add("jdbc.addBatch", 1);
                Sql sql = args != null && args.length > 0 && args[0] instanceof String literal
                        && name.startsWith("execute") ? Sql.of(literal) : prepared;
                long started = System.nanoTime();
                Object result;
                try { result = AutomaticCatalogueWorkloadJdbc.invoke(delegate, method, args); }
                finally {
                    if (name.startsWith("execute")) {
                        long elapsed = System.nanoTime() - started;
                        metrics.add("jdbc." + name, 1);
                        metrics.add("jdbc.executionNanos", elapsed);
                        if (sql.payload()) AutomaticCatalogueWorkloadProbe.captureMetric("jdbc.payloadSelects", 1);
                        if (sql.compatible()) {
                            AutomaticCatalogueWorkloadProbe.compatibilityExecuted();
                            metrics.add("jdbc.compatibilityExecutions", 1);
                            metrics.add("jdbc.compatibilityExecutionNanos", elapsed);
                        }
                        if (sql.capacity() && AutomaticCatalogueWorkloadProbe.preparing()) {
                            metrics.add("jdbc.capacityCountExecutions", 1);
                            metrics.add("jdbc.capacityCountExecutionNanos", elapsed);
                        }
                    }
                }
                return result instanceof ResultSet rows ? resultSet(rows, sql, lastSource) : result;
            }
        }
        Class<?> type = delegate instanceof CallableStatement ? CallableStatement.class
                : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new ObservedStatement());
    }

    private static ResultSet resultSet(ResultSet delegate, Sql sql, String source) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    long started = System.nanoTime();
                    Object result = invoke(delegate, method, args);
                    var metrics = AutomaticCatalogueWorkloadProbe.metrics();
                    if (metrics != null && method.getName().equals("next") && Boolean.TRUE.equals(result)) {
                        metrics.add("jdbc.rowsAdvanced", 1);
                        if (sql.compatible()) {
                            metrics.add("jdbc.compatibilityRowsReturned", 1);
                            AutomaticCatalogueWorkloadProbe.compatibilityRow();
                        }
                    }
                    if (metrics != null && sql.compatible() && method.getName().equals("next"))
                        metrics.add("jdbc.compatibilityNextNanos", System.nanoTime() - started);
                    if (metrics != null && sql.capacity() && result instanceof Number count
                            && (method.getName().equals("getLong") || method.getName().equals("getInt")))
                        AutomaticCatalogueWorkloadProbe.capacityCount(count.intValue());
                    if (metrics != null && sql.payload() && result instanceof byte[] bytes) {
                        String name = "jdbc.sourceBytes." + source;
                        metrics.add(name, bytes.length);
                        AutomaticCatalogueWorkloadProbe.captureMetric(name, bytes.length);
                    }
                    return result;
                });
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private record Sql(boolean payload, boolean compatible, boolean capacity) {
        static Sql of(String text) {
            String sql = text.toLowerCase(Locale.ROOT);
            boolean questionSelect = sql.contains("select ") && sql.contains("analysis_question_checkpoint");
            int where = sql.indexOf(" where ");
            String predicate = where < 0 ? "" : sql.substring(where);
            return new Sql(sql.contains("catalogue_source_blob") && sql.contains(".payload") && sql.contains("select "),
                    questionSelect && predicate.contains(".input_hash=") && predicate.contains(".provider="),
                    questionSelect && sql.contains("count(") && !predicate.contains(".state"));
        }
    }
}
