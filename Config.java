package com.petsa;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

/** Loads settings from config/application.properties, with environment variables taking priority. */
public final class Config {
    public final String dbUrl, dbUser, dbPassword, host;
    public final int port, sessionHours;
    public final boolean cookieSecure;
    public final Set<String> allowedOrigins;
    public final ZoneId zone;

    private static Config instance;

    public static synchronized Config get() {
        if (instance == null) instance = new Config();
        return instance;
    }

    private Config() {
        Properties p = new Properties();
        String path = System.getenv("PDS_CONFIG");
        Path file = Path.of(path != null && !path.isBlank() ? path : "config/application.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + file + ": " + e.getMessage());
            }
        }
        dbUrl = need(p, "db.url", "PDS_DB_URL", file);
        dbUser = need(p, "db.user", "PDS_DB_USER", file);
        dbPassword = pick(p, "db.password", "PDS_DB_PASSWORD", "");
        host = pick(p, "server.host", "PDS_HOST", "127.0.0.1");
        port = Integer.parseInt(pick(p, "server.port", "PDS_PORT", "8080"));
        sessionHours = Integer.parseInt(pick(p, "session.hours", "PDS_SESSION_HOURS", "8"));
        cookieSecure = Boolean.parseBoolean(pick(p, "server.cookieSecure", "PDS_COOKIE_SECURE", "false"));
        zone = ZoneId.of(pick(p, "app.timezone", "PDS_TIMEZONE", "Asia/Manila"));
        Set<String> origins = new LinkedHashSet<>();
        for (String o : pick(p, "server.allowedOrigins", "PDS_ALLOWED_ORIGINS",
                "http://localhost:8443,http://127.0.0.1:8443").split(",")) {
            if (!o.isBlank()) origins.add(o.trim());
        }
        allowedOrigins = Collections.unmodifiableSet(origins);
    }

    private static String pick(Properties p, String key, String env, String fallback) {
        String e = System.getenv(env);
        if (e != null && !e.isEmpty()) return e;
        String v = p.getProperty(key);
        return v != null && !v.isBlank() ? v.trim() : fallback;
    }

    private static String need(Properties p, String key, String env, Path file) {
        String v = pick(p, key, env, "");
        if (v.isEmpty() || v.startsWith("YOUR_") || v.contains("YOUR_")) {
            throw new IllegalStateException("Missing setting '" + key + "'. Copy config/application.properties.example to "
                    + file + " and fill in your own values (or set the " + env + " environment variable).");
        }
        return v;
    }
}
