package com.petsa;

import com.petsa.http.ApiServer;
import com.petsa.http.Sessions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

public final class Main {
    public static void main(String[] args) throws Exception {
        try {
            Config.get();
        } catch (IllegalStateException e) {
            System.err.println("CONFIG ERROR: " + e.getMessage());
            System.exit(1);
        }
        Db.init();
        try {
            Db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'");
                     ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) == 0)
                        System.out.println("[notice] No admin account exists yet. Create one with:  scripts/manage-users.sh create-admin ADMIN-001 \"Administrator\"  (and a finance login with create-finance FIN-001 \"Finance Admin\")");
                }
                return null;
            });
        } catch (Exception e) {
            System.err.println("DATABASE ERROR: could not use the database (" + e.getMessage() + ").");
            System.err.println("Check db.url / db.user / db.password, that the MySQL JDBC driver JAR is in backend/lib, and that sql/schema.sql has been run.");
            System.exit(1);
        }
        Sessions.purgeExpired();
        new ApiServer().start();
    }
}
