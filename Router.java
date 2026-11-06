package com.petsa.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Router {
    public record Route(String method, String[] segments, Access access, Handler handler) {}

    public record Match(Route route, Map<String, String> params) {}

    private static final Pattern SAFE_PARAM = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private final List<Route> routes = new ArrayList<>();

    public void add(String method, String pattern, Access access, Handler h) {
        routes.add(new Route(method, pattern.substring(1).split("/"), access, h));
    }

    /** Returns the matching route; throws 404 (no path match) or 405 (path matches, wrong method). */
    public Match find(String method, String path) {
        String[] parts = path.length() > 1 ? path.substring(1).split("/") : new String[0];
        boolean pathMatched = false;
        for (Route r : routes) {
            Map<String, String> params = match(r.segments, parts);
            if (params == null) continue;
            pathMatched = true;
            if (r.method.equals(method)) return new Match(r, params);
        }
        throw pathMatched ? new ApiException(405, "Method not allowed.") : ApiException.notFound("Not found.");
    }

    private static Map<String, String> match(String[] pattern, String[] parts) {
        if (pattern.length != parts.length) return null;
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i].startsWith("{")) {
                if (!SAFE_PARAM.matcher(parts[i]).matches()) return null;
                params.put(pattern[i].substring(1, pattern[i].length() - 1), parts[i]);
            } else if (!pattern[i].equals(parts[i])) return null;
        }
        return params;
    }
}
