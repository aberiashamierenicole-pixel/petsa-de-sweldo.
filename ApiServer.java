package com.petsa.http;

import com.petsa.Config;
import com.petsa.api.*;
import com.petsa.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class ApiServer {
    private final Router router = new Router();
    private final Config cfg = Config.get();

    public ApiServer() {
        router.add("POST", "/api/auth/login", Access.PUBLIC, AuthApi::login);
        router.add("POST", "/api/auth/logout", Access.PUBLIC, AuthApi::logout);
        router.add("GET", "/api/auth/me", Access.ANY, AuthApi::me);

        // System Admin manages accounts; Finance may read the list (salary information, report generator)
        router.add("GET", "/api/employees", Access.STAFF, EmployeeApi::list);
        router.add("POST", "/api/employees", Access.ADMIN, EmployeeApi::create);
        router.add("PUT", "/api/employees/{id}", Access.ADMIN, EmployeeApi::update);
        router.add("POST", "/api/employees/{id}/status", Access.ADMIN, EmployeeApi::setStatus);
        router.add("GET", "/api/audit", Access.STAFF, AuditApi::list);

        router.add("GET", "/api/attendance", Access.ADMIN, AttendanceApi::listAll);
        router.add("GET", "/api/me/attendance", Access.EMPLOYEE, AttendanceApi::listMine);
        router.add("POST", "/api/me/attendance/time-in", Access.EMPLOYEE, AttendanceApi::timeIn);
        router.add("POST", "/api/me/attendance/time-out", Access.EMPLOYEE, AttendanceApi::timeOut);

        router.add("GET", "/api/finance/payroll", Access.FINANCE, PayrollApi::list);
        router.add("POST", "/api/finance/payroll/refresh", Access.FINANCE, PayrollApi::refresh);
        router.add("POST", "/api/finance/payroll/{id}/approve", Access.FINANCE, PayrollApi::approve);
        router.add("POST", "/api/finance/payroll/{id}/release", Access.FINANCE, PayrollApi::release);
        router.add("GET", "/api/me/payslips", Access.EMPLOYEE, PayrollApi::myPayslips);
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.host, cfg.port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Petsa de Sweldo API listening on http://" + cfg.host + ":" + cfg.port);
    }

    private void dispatch(HttpExchange ex) throws IOException {
        try {
            var h = ex.getResponseHeaders();
            h.set("Cache-Control", "no-store");
            h.set("X-Server-Date", java.time.LocalDate.now(cfg.zone).toString()); // the UI uses the server's calendar day, not the browser clock
            h.set("X-Content-Type-Options", "nosniff");
            h.set("X-Frame-Options", "DENY");
            h.set("Referrer-Policy", "no-referrer");
            h.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");

            Ctx c = new Ctx(ex);
            if (!c.path.startsWith("/api/")) throw ApiException.notFound("Not found.");
            if (c.method.equals("OPTIONS")) { send(ex, 204, null); return; } // same-origin only: no CORS headers

            boolean unsafe = !(c.method.equals("GET") || c.method.equals("HEAD"));
            if (unsafe) checkCsrf(c);

            Router.Match m = router.find(c.method, c.path);
            c.params = m.params();
            Access access = m.route().access();
            if (access != Access.PUBLIC) {
                c.session = Sessions.find(c.cookie(Sessions.COOKIE));
                if (c.session == null) throw ApiException.unauthorized("Your session has expired. Please log in again.");
                if (!access.allows(c.session.role())) throw ApiException.forbidden("You do not have access to this resource.");
            }
            Object result = m.route().handler().handle(c);
            if (result == null) send(ex, 204, null);
            else if (result instanceof Res r) send(ex, r.status(), r.body());
            else send(ex, 200, result);
        } catch (ApiException e) {
            send(ex, e.status, Json.obj("error", e.getMessage()));
        } catch (Throwable t) {
            System.err.println("[error] " + ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " -> " + t);
            t.printStackTrace(System.err);
            send(ex, 500, Json.obj("error", "Something went wrong on the server. Please try again."));
        } finally {
            ex.close();
        }
    }

    /** CSRF defence in depth: SameSite=Strict cookie + required custom header + Origin allow-list. */
    private void checkCsrf(Ctx c) {
        String xrw = c.ex.getRequestHeaders().getFirst("X-Requested-With");
        if (!"PetsaDeSweldo".equals(xrw)) throw ApiException.forbidden("Missing required request header.");
        String origin = c.ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !cfg.allowedOrigins.contains(origin)) throw ApiException.forbidden("Origin not allowed.");
        String ct = c.ex.getRequestHeaders().getFirst("Content-Type");
        boolean hasBody = c.ex.getRequestHeaders().getFirst("Content-Length") != null
                && !"0".equals(c.ex.getRequestHeaders().getFirst("Content-Length"));
        if (hasBody && (ct == null || !ct.toLowerCase().startsWith("application/json")))
            throw new ApiException(415, "Content-Type must be application/json.");
    }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
