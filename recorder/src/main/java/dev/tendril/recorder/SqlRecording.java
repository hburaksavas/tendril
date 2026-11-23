package dev.tendril.recorder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Counts synchronous JDBC executions on the servlet request thread. */
final class SqlRecording {
    private static final ThreadLocal<SqlRecording> CURRENT = new ThreadLocal<>();
    private static final Pattern SIMPLE_SQL = Pattern.compile("[a-zA-Z0-9_?,.()=*+<>!/%\\s-]+");
    private static final int MAX_GROUPS = 20;
    private final Map<String, Stats> groups = new LinkedHashMap<>();
    private final Stats other = new Stats();

    static SqlRecording begin() {
        SqlRecording recording = new SqlRecording();
        CURRENT.set(recording);
        return recording;
    }

    static void end() {
        CURRENT.remove();
    }

    static Connection wrap(Connection connection) {
        if (CURRENT.get() == null || connection == null) return connection;
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    if ((method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))
                            && result instanceof PreparedStatement statement && args[0] instanceof String sql) {
                        return wrap(statement, sql, (Connection) proxy);
                    }
                    return result;
                });
    }

    private static PreparedStatement wrap(PreparedStatement statement, String sql, Connection connection) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class : PreparedStatement.class;
        return (PreparedStatement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getConnection")) return connection;
                    SqlRecording recording = CURRENT.get();
                    if (recording == null || method.getParameterCount() != 0 || !name.startsWith("execute")) {
                        return invoke(statement, method, args);
                    }
                    long started = System.nanoTime();
                    try {
                        return invoke(statement, method, args);
                    } finally {
                        recording.add(sql, System.nanoTime() - started);
                    }
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private void add(String sql, long nanos) {
        try {
            String template = displayTemplate(sql);
            Stats existing = groups.get(template);
            Stats stats = existing != null ? existing : groups.size() < MAX_GROUPS
                    ? groups.computeIfAbsent(template, ignored -> new Stats()) : other;
            stats.count++;
            stats.nanos += nanos;
        } catch (RuntimeException ignored) {
            // Recording must never affect a JDBC call.
        }
    }

    private static String displayTemplate(String sql) {
        if (sql.length() > 512 || !SIMPLE_SQL.matcher(sql).matches() || sql.contains("--") || sql.contains("/*")) {
            return "SQL text omitted";
        }
        return sql.replaceAll("(?i)(?<![a-z0-9_])(?:0x[0-9a-f]+|0b[01]+|[0-9]+(?:\\.[0-9]+)?(?:e[+-]?[0-9]+)?)", "?")
                .replaceAll("\\s+", " ").strip();
    }

    void writeTo(Json event) {
        if (groups.isEmpty()) return;
        var summaries = groups.entrySet().stream().map(entry -> entry.getValue().summary(entry.getKey()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (other.count > 0) summaries.add(other.summary("Other SQL templates"));
        event.objects("sql", summaries);
    }

    private static final class Stats {
        private long count;
        private long nanos;

        Json summary(String template) {
            return Json.object().str("template", template).num("count", count).num("durationMicros", nanos / 1_000);
        }
    }
}
