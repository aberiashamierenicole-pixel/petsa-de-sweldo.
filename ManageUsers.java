package com.petsa.tools;

import com.petsa.Config;
import com.petsa.Db;
import com.petsa.util.Passwords;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;

/**
 * Command-line tool for creating logins. Passwords are typed at a prompt (or taken from the
 * PDS_NEW_PASSWORD environment variable) and are only ever stored as PBKDF2 hashes.
 *
 *   create-admin  <LOGIN_ID> "<Display Name>"   create a System Admin login
 *   create-finance <LOGIN_ID> "<Display Name>"  create a Finance Admin login
 *   create-login  <EMPLOYEE_ID>                 create a login for an existing employee that has none
 *   set-password  <LOGIN_ID>                    change the password of an existing login
 */
public final class ManageUsers {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        try {
            Config.get();
        } catch (IllegalStateException e) {
            fail("CONFIG ERROR: " + e.getMessage());
        }
        Db.init();
        String cmd = args[0];
        String id = args[1].trim().toUpperCase(Locale.ROOT);
        try (Connection c = Db.get()) {
            run(c, cmd, id, args);
        } catch (java.sql.SQLException e) {
            fail("DATABASE ERROR: " + e.getMessage() + "\nCheck db.url / db.user / db.password, that the JDBC driver JAR is in backend/lib, and that sql/schema.sql has been run.");
        }
    }

    private static void run(Connection c, String cmd, String id, String[] args) throws Exception {
        {
            switch (cmd) {
                case "create-admin", "create-finance" -> {
                    String role = cmd.equals("create-admin") ? "ADMIN" : "FINANCE";
                    if (args.length < 3) usage();
                    if (!id.matches("^[A-Z0-9][A-Z0-9-]{2,29}$")) fail("Login ID must be 3-30 characters: letters, numbers, hyphens.");
                    String name = args[2].trim();
                    if (name.length() < 2 || name.length() > 100) fail("Display name must be 2-100 characters.");
                    String hash = Passwords.hash(readPassword());
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO users (login_id, role, employee_id, display_name, password_hash) VALUES (?, ?, NULL, ?, ?)")) {
                        ps.setString(1, id);
                        ps.setString(2, role);
                        ps.setString(3, name);
                        ps.setString(4, hash);
                        ps.executeUpdate();
                    } catch (java.sql.SQLException e) {
                        if (Db.isDuplicateKey(e)) fail("A login named " + id + " already exists. Use set-password to change its password.");
                        throw e;
                    }
                    System.out.println((role.equals("ADMIN") ? "System Admin" : "Finance Admin") + " login " + id + " created.");
                }
                case "create-login" -> {
                    String name = null;
                    try (PreparedStatement ps = c.prepareStatement("SELECT full_name FROM employees WHERE id = ?")) {
                        ps.setString(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) name = rs.getString(1);
                        }
                    }
                    if (name == null) fail("No employee with ID " + id + " exists.");
                    String hash = Passwords.hash(readPassword());
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO users (login_id, role, employee_id, display_name, password_hash) VALUES (?, 'EMPLOYEE', ?, ?, ?)")) {
                        ps.setString(1, id);
                        ps.setString(2, id);
                        ps.setString(3, name);
                        ps.setString(4, hash);
                        ps.executeUpdate();
                    } catch (java.sql.SQLException e) {
                        if (Db.isDuplicateKey(e)) fail(id + " already has a login. Use set-password to change its password.");
                        throw e;
                    }
                    System.out.println("Login created for " + id + " (" + name + ").");
                }
                case "set-password" -> {
                    String hash = Passwords.hash(readPassword());
                    int n;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE users SET password_hash = ?, failed_attempts = 0, locked_until = NULL WHERE login_id = ?")) {
                        ps.setString(1, hash);
                        ps.setString(2, id);
                        n = ps.executeUpdate();
                    }
                    if (n == 0) fail("No login named " + id + " exists.");
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM sessions WHERE user_id = (SELECT id FROM (SELECT id FROM users WHERE login_id = ?) t)")) {
                        ps.setString(1, id);
                        ps.executeUpdate();
                    }
                    System.out.println("Password updated for " + id + ". Existing sessions were signed out.");
                }
                default -> usage();
            }
        }
    }

    private static String readPassword() throws Exception {
        String env = System.getenv("PDS_NEW_PASSWORD");
        String pw;
        if (env != null && !env.isEmpty()) {
            pw = env;
        } else if (System.console() != null) {
            char[] a = System.console().readPassword("New password (min 8 characters): ");
            char[] b = System.console().readPassword("Repeat password: ");
            if (a == null || b == null || !java.util.Arrays.equals(a, b)) fail("Passwords did not match.");
            pw = new String(a);
        } else {
            System.out.println("(No secure console available - the password you type will be visible.)");
            System.out.print("New password (min 8 characters): ");
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            pw = r.readLine();
        }
        if (pw == null || pw.length() < 8 || pw.length() > 128 || pw.isBlank()) fail("Password must be 8-128 characters.");
        return pw;
    }

    private static void usage() {
        System.err.println("Usage:\n  create-admin <LOGIN_ID> \"<Display Name>\"\n  create-finance <LOGIN_ID> \"<Display Name>\"\n  create-login <EMPLOYEE_ID>\n  set-password <LOGIN_ID>");
        System.exit(2);
    }

    private static void fail(String m) {
        System.err.println(m);
        System.exit(1);
    }
}
