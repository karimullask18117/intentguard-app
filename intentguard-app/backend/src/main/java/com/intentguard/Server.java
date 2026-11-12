package com.intentguard;

import com.intentguard.Models.ActionRequest;
import com.intentguard.Models.AuditEntry;
import com.intentguard.Models.EvaluationResult;
import com.intentguard.Models.ParsedIntent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executors;

/** IntentGuard REST API on the JDK's built-in HTTP server (no external dependencies). */
public final class Server {
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_INTENT = 2000;

    private final AuditStore audit = new AuditStore();
    private final Set<String> allowedOrigins;
    private final Path staticRoot;

    public Server(Set<String> allowedOrigins, Path staticRoot) {
        this.allowedOrigins = allowedOrigins;
        this.staticRoot = staticRoot;
    }

    record Resp(int status, Object body) {}

    static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Set<String> origins = Set.of(System.getenv()
                .getOrDefault("CORS_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173").split("\\s*,\\s*"));
        String dir = System.getenv("FRONTEND_DIR");
        Path root = dir != null && !dir.isBlank() && Files.isDirectory(Path.of(dir))
                ? Path.of(dir).toAbsolutePath().normalize() : null;
        HttpServer s = new Server(origins, root).start(port);
        System.out.println("IntentGuard API listening on http://localhost:" + s.getAddress().getPort()
                + (root != null ? "  (serving frontend from " + root + ")" : ""));
    }

    /** Starts the server; pass port 0 for any free port. */
    public HttpServer start(int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/", this::handle);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        return s;
    }

    // ---------------------------------------------------------------- dispatch
    private void handle(HttpExchange ex) throws IOException {
        try {
            cors(ex);
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            if (path.equals("/api") || path.startsWith("/api/")) {
                Resp r = route(method, path, ex);
                sendJson(ex, r.status(), r.body());
            } else if (method.equals("GET") || method.equals("HEAD")) {
                serveStatic(ex, path);
            } else {
                throw new ApiException(405, "Method not allowed");
            }
        } catch (ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            sendJson(ex, 409, Map.of("error", e.getMessage()));
        } catch (NoSuchElementException e) {
            sendJson(ex, 404, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Internal server error"));
        } finally {
            ex.close();
        }
    }

    private Resp route(String method, String path, HttpExchange ex) throws IOException {
        String[] seg = Arrays.stream(path.split("/")).filter(x -> !x.isEmpty()).toArray(String[]::new); // api, ...
        if (seg.length < 2) throw new ApiException(404, "Not found");
        String res = seg[1];

        switch (res) {
            case "health" -> {
                requireMethod(method, "GET");
                return new Resp(200, Map.of("status", "ok"));
            }
            case "scenarios" -> {
                requireMethod(method, "GET");
                return new Resp(200, Scenarios.ALL);
            }
            case "parse-intent" -> {
                requireMethod(method, "POST");
                Map<String, Object> body = readObject(ex);
                ParsedIntent p = IntentParser.parse(intent(body));
                return new Resp(200, p);
            }
            case "evaluate" -> {
                requireMethod(method, "POST");
                Map<String, Object> body = readObject(ex);
                String intent = intent(body);
                if (!(body.get("action") instanceof Map<?, ?> am)) throw new IllegalArgumentException("action must be an object");
                @SuppressWarnings("unchecked")
                ActionRequest action = ActionRequest.fromMap((Map<String, Object>) am);
                Object mode = body.get("mode");
                if (mode != null && !(mode instanceof String)) throw new IllegalArgumentException("mode must be a string");
                EvaluationResult r = PolicyEngine.evaluate(intent, action, PolicyEngine.strictness((String) mode));
                AuditEntry entry = audit.record(action, r);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("entry", entry);
                out.put("score", r.score());
                out.put("decision", r.decision());
                out.put("findings", r.findings());
                return new Resp(200, out);
            }
            case "audit" -> {
                if (seg.length == 2) {
                    if (method.equals("GET")) return new Resp(200, audit.list());
                    if (method.equals("DELETE")) { audit.clear(); return new Resp(200, Map.of("cleared", true)); }
                    throw new ApiException(405, "Method not allowed");
                }
                if (seg.length == 3 && seg[2].equals("stats")) {
                    requireMethod(method, "GET");
                    return new Resp(200, audit.stats());
                }
                if (seg.length == 4 && seg[3].equals("resolve")) {
                    requireMethod(method, "POST");
                    Map<String, Object> body = readObject(ex);
                    if (!(body.get("approved") instanceof Boolean approved))
                        throw new IllegalArgumentException("approved must be true or false");
                    return new Resp(200, audit.resolve(seg[2], approved));
                }
                throw new ApiException(404, "Not found");
            }
            default -> throw new ApiException(404, "Not found");
        }
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new ApiException(405, "Method not allowed");
    }

    private static String intent(Map<String, Object> body) {
        Object v = body.get("intent");
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException("intent is required");
        if (s.length() > MAX_INTENT) throw new IllegalArgumentException("intent is too long (max " + MAX_INTENT + ")");
        return s;
    }

    // ---------------------------------------------------------------- io helpers
    @SuppressWarnings("unchecked")
    private static Map<String, Object> readObject(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new ApiException(413, "Request body too large");
        Object o = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(o instanceof Map)) throw new IllegalArgumentException("JSON body must be an object");
        return (Map<String, Object>) o;
    }

    private void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && allowedOrigins.contains(origin)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Vary", "Origin");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        }
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (staticRoot == null) throw new ApiException(404, "Not found");
        Path file = staticRoot.resolve(path.replaceFirst("^/+", "")).normalize();
        if (!file.startsWith(staticRoot)) throw new ApiException(403, "Forbidden");
        if (!Files.isRegularFile(file)) file = staticRoot.resolve("index.html"); // SPA fallback
        if (!Files.isRegularFile(file)) throw new ApiException(404, "Not found");
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", contentType(file.getFileName().toString()));
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(200, head ? -1 : bytes.length);
        if (!head) try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
