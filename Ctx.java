package com.petsa.http;

import com.petsa.Config;
import com.petsa.util.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** One HTTP request: query, JSON body, cookies, authenticated session. */
public final class Ctx {
    private static final int MAX_BODY = 64 * 1024;

    public final HttpExchange ex;
    public final String method, path;
    public final Map<String, String> query = new LinkedHashMap<>();
    public Map<String, String> params = Map.of();
    public Session session;
    private Map<String, Object> body;

    Ctx(HttpExchange ex) {
        this.ex = ex;
        this.method = ex.getRequestMethod();
        this.path = ex.getRequestURI().getPath();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                try {
                    query.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    throw ApiException.bad("Malformed query string.");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> body() throws IOException {
        if (body != null) return body;
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new ApiException(413, "Request body is too large.");
        if (raw.length == 0) throw ApiException.bad("A JSON request body is required.");
        Object parsed;
        try {
            parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            throw ApiException.bad("Invalid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) throw ApiException.bad("The request body must be a JSON object.");
        body = (Map<String, Object>) parsed;
        return body;
    }

    public String cookie(String name) {
        var headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String h : headers) {
            for (String part : h.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) return p.substring(eq + 1);
            }
        }
        return null;
    }

    public void setSessionCookie(String token, int maxAgeSeconds) {
        StringBuilder sb = new StringBuilder(Sessions.COOKIE).append('=').append(token)
                .append("; Path=/; HttpOnly; SameSite=Strict; Max-Age=").append(maxAgeSeconds);
        if (Config.get().cookieSecure) sb.append("; Secure");
        ex.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    public String clientIp() {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    public String param(String name) {
        return params.get(name);
    }
}
