package com.petsa;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The ONLY place that opens JDBC connections. If you already have your own connection
 * class, replace the body of get() with a call to it - nothing else in the project
 * touches DriverManager.
 */
public final class Db {
    private Db() {}

    public interface SqlFunction<T> {
        T apply(Connection c) throws Exception;
    }

    public static void init() {
        // Connector/J 8+/9 and 5.1.x use different class names; JDBC 4 auto-loading covers both too.
        for (String cls : new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"}) {
            try {
                Class.forName(cls);
                return;
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    public static Connection get() throws SQLException {
        Config cfg = Config.get();
        return DriverManager.getConnection(cfg.dbUrl, cfg.dbUser, cfg.dbPassword);
    }

    /** Runs work in one transaction: commit on success, rollback on any failure. */
    public static <T> T tx(SqlFunction<T> work) throws Exception {
        try (Connection c = get()) {
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (Throwable t) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw t;
            }
        }
    }

    /** Runs read-only work on a fresh connection. */
    public static <T> T read(SqlFunction<T> work) throws Exception {
        try (Connection c = get()) {
            return work.apply(c);
        }
    }

    public static boolean isDuplicateKey(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof SQLException se && (se.getErrorCode() == 1062 || "23000".equals(se.getSQLState()))) return true;
        }
        return false;
    }
}
