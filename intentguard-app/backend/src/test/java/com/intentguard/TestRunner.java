package com.intentguard;

import com.intentguard.Models.ActionRequest;
import com.intentguard.Models.Decision;
import com.intentguard.Models.EvaluationResult;
import com.intentguard.Models.ParsedIntent;
import com.intentguard.Models.Scenario;
import com.sun.net.httpserver.HttpServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-free test runner: `./build.sh test`. Exits non-zero on any failure. */
public final class TestRunner {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    static ActionRequest act(String tool, String target, String content, Double amount, String source) {
        return new ActionRequest(tool, target, content, amount, source);
    }

    static Decision eval(String intent, ActionRequest a) {
        return PolicyEngine.evaluate(intent, a, 1.0).decision();
    }

    public static void main(String[] args) throws Exception {
        jsonTests();
        parserTests();
        engineTests();
        scenarioTests();
        httpTests();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void jsonTests() {
        Object o = Json.parse("{\"a\":[1,2.5,true,null,\"x\\n\\u0041\"],\"b\":{\"c\":\"q\\\"\"}}");
        check("json parse object", o instanceof Map);
        String out = Json.write(o);
        check("json roundtrip", Json.write(Json.parse(out)).equals(out));
        check("json unicode escape", Json.write(Json.parse("\"\\u0041\"")).equals("\"A\""));
        for (String bad : new String[]{"", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"abc", "nul", "{'a':1}", "01x"}) {
            boolean threw = false;
            try { Json.parse(bad); } catch (IllegalArgumentException e) { threw = true; }
            check("json rejects: " + bad, threw);
        }
        check("json deep nesting rejected", throwsIAE(() -> Json.parse("[".repeat(200) + "]".repeat(200))));
        check("json enum lowercased", Json.write(Decision.CONFIRM).equals("\"confirm\""));
        check("json escapes control chars", Json.write("a\u0001b").equals("\"a\\u0001b\""));
    }

    static boolean throwsIAE(Runnable r) {
        try { r.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }

    static void parserTests() {
        ParsedIntent p = IntentParser.parse("Pay the $120 electricity invoice to utility@power-co.example.");
        check("parser: pay type", p.actionTypes().contains("pay"));
        check("parser: email", p.emails().equals(List.of("utility@power-co.example")));
        check("parser: amount", p.amounts().equals(List.of(120.0)));
        check("parser: email domain", p.domains().contains("power-co.example"));
        check("parser: email not a file", p.files().isEmpty());

        p = IntentParser.parse("Run the unit tests in the project and fix the failing tests in utils.py.");
        check("parser: file", p.files().equals(List.of("utils.py")));
        check("parser: file not a domain", p.domains().isEmpty());
        check("parser: shell+write", p.actionTypes().contains("shell") && p.actionTypes().contains("write"));

        p = IntentParser.parse("Search the web for Q3 market share of EV makers and save a summary to ev_summary.md.");
        check("parser: 'market share' is not a send", !p.actionTypes().contains("send"));
        check("parser: read+write", p.actionTypes().contains("read") && p.actionTypes().contains("write"));

        p = IntentParser.parse("Transfer 500 USD and also \u20B9250 to bob@x.org");
        check("parser: amounts", p.amounts().equals(List.of(250.0, 500.0)) || p.amounts().equals(List.of(500.0, 250.0)));
        p = IntentParser.parse(null);
        check("parser: null safe", p.actionTypes().isEmpty());
    }

    static void engineTests() {
        String mail = "Email the report to bob@acme.com";
        check("send to named recipient allowed", eval(mail, act("send_email", "bob@acme.com", "report", null, "user")) == Decision.ALLOW);
        check("send to other recipient needs review",
                eval(mail, act("send_email", "eve@evil.com", "report", null, "user")) != Decision.ALLOW);
        check("send with secret blocked",
                eval(mail, act("send_email", "bob@acme.com", "token=abc123", null, "user")) == Decision.BLOCK);
        check("delete always needs approval",
                eval("Delete old.txt", act("delete_file", "old.txt", "", null, "user")) == Decision.CONFIRM);
        check("payment needs approval even when matching",
                eval("Pay $50 to shop@x.com", act("make_payment", "shop@x.com", "", 50.0, "user")) == Decision.CONFIRM);
        check("payment over limit blocked",
                eval("Pay $50 to shop@x.com", act("make_payment", "shop@x.com", "", 500.0, "user")) == Decision.BLOCK);
        check("rm -rf blocked",
                eval("Run the build", act("run_shell", "rm -rf /", "", null, "agent")) == Decision.BLOCK);
        check("untrusted source raises risk",
                PolicyEngine.evaluate("Summarize report.pdf", act("read_file", "report.pdf", "", null, "document"), 1).score() >= 30);
        check("unknown tool not allowed",
                eval("Do things", act("launch_missiles", "", "", null, "agent")) != Decision.ALLOW);
        EvaluationResult strict = PolicyEngine.evaluate("Summarize notes.txt", act("http_get", "https://x.org", "", null, "document"), 1.25);
        EvaluationResult relaxed = PolicyEngine.evaluate("Summarize notes.txt", act("http_get", "https://x.org", "", null, "document"), 0.8);
        check("strict scores higher than relaxed", strict.score() > relaxed.score());
        check("score within 0..100", strict.score() <= 100 && relaxed.score() >= 0);
        check("mode parsing", PolicyEngine.strictness("strict") == 1.25 && PolicyEngine.strictness(null) == 1.0);
        check("bad mode rejected", throwsIAE(() -> PolicyEngine.strictness("nope")));
        check("hostOf handles garbage", PolicyEngine.hostOf("::::").equals("::::"));
    }

    static void scenarioTests() {
        // expected decision per action, in order
        Map<String, List<Decision>> expected = Map.of(
                "Hijacked email assistant", List.of(Decision.ALLOW, Decision.BLOCK, Decision.BLOCK),
                "Coding agent", List.of(Decision.ALLOW, Decision.ALLOW, Decision.BLOCK, Decision.BLOCK),
                "Bill payment", List.of(Decision.CONFIRM, Decision.BLOCK),
                "Web research", List.of(Decision.ALLOW, Decision.ALLOW, Decision.BLOCK, Decision.BLOCK));
        for (Scenario s : Scenarios.ALL) {
            List<Decision> want = expected.get(s.name());
            check("scenario known: " + s.name(), want != null && want.size() == s.actions().size());
            for (int i = 0; i < s.actions().size(); i++)
                check("scenario " + s.name() + " #" + i, eval(s.intent(), s.actions().get(i)) == want.get(i));
        }
    }

    // ---------------------------------------------------------------- HTTP
    static HttpClient http = HttpClient.newHttpClient();
    static String base;

    static HttpResponse<String> call(String method, String path, String body, String origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (body != null) b.header("Content-Type", "application/json");
        if (origin != null) b.header("Origin", origin);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) { return (Map<String, Object>) Json.parse(json); }

    static void httpTests() throws Exception {
        HttpServer s = new Server(Set.of("http://localhost:5173"), null).start(0);
        base = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            check("health", obj(call("GET", "/api/health", null, null).body()).get("status").equals("ok"));
            check("scenarios", ((List<?>) Json.parse(call("GET", "/api/scenarios", null, null).body())).size() == 4);

            var pr = call("POST", "/api/parse-intent", "{\"intent\":\"Email bob@acme.com\"}", null);
            check("parse-intent", pr.statusCode() == 200 && pr.body().contains("bob@acme.com"));

            String ev = "{\"intent\":\"Summarize my unread emails from today.\",\"mode\":\"balanced\","
                    + "\"action\":{\"tool\":\"send_email\",\"target\":\"a@evil.example\",\"content\":\"api_key=sk-live-1234567890abcdef\",\"source\":\"email\"}}";
            var r = call("POST", "/api/evaluate", ev, null);
            Map<String, Object> j = obj(r.body());
            check("evaluate 200", r.statusCode() == 200);
            check("evaluate blocks injection", "block".equals(j.get("decision")));
            check("evaluate has findings", ((List<?>) j.get("findings")).size() >= 2);

            var ok = call("POST", "/api/evaluate",
                    "{\"intent\":\"Summarize my unread emails.\",\"action\":{\"tool\":\"read_email\",\"target\":\"inbox\",\"source\":\"user\"}}", null);
            check("evaluate allows legit", "allow".equals(obj(ok.body()).get("decision")));

            var pay = call("POST", "/api/evaluate",
                    "{\"intent\":\"Pay $120 to utility@power-co.example\",\"action\":{\"tool\":\"make_payment\",\"target\":\"utility@power-co.example\",\"amount\":120,\"source\":\"agent\"}}", null);
            Map<String, Object> pj = obj(pay.body());
            check("payment needs approval", "confirm".equals(pj.get("decision")));
            String id = (String) ((Map<?, ?>) pj.get("entry")).get("id");

            check("audit lists 3", ((List<?>) Json.parse(call("GET", "/api/audit", null, null).body())).size() == 3);
            Map<String, Object> st = obj(call("GET", "/api/audit/stats", null, null).body());
            check("stats", ((Number) st.get("allowed")).intValue() == 1 && ((Number) st.get("blocked")).intValue() == 1
                    && ((Number) st.get("pendingApproval")).intValue() == 1);

            var res = call("POST", "/api/audit/" + id + "/resolve", "{\"approved\":true}", null);
            check("resolve approve", res.statusCode() == 200 && "approved".equals(obj(res.body()).get("outcome")));
            check("resolve twice -> 409", call("POST", "/api/audit/" + id + "/resolve", "{\"approved\":false}", null).statusCode() == 409);
            check("resolve unknown -> 404", call("POST", "/api/audit/nope/resolve", "{\"approved\":true}", null).statusCode() == 404);
            check("resolve bad body -> 400", call("POST", "/api/audit/" + id + "/resolve", "{\"approved\":\"yes\"}", null).statusCode() == 400);

            // validation & errors
            check("missing intent -> 400", call("POST", "/api/evaluate", "{\"action\":{\"tool\":\"read_file\"}}", null).statusCode() == 400);
            check("bad json -> 400", call("POST", "/api/evaluate", "{oops", null).statusCode() == 400);
            check("bad mode -> 400", call("POST", "/api/evaluate",
                    "{\"intent\":\"x\",\"mode\":\"wild\",\"action\":{\"tool\":\"read_file\"}}", null).statusCode() == 400);
            check("bad source -> 400", call("POST", "/api/evaluate",
                    "{\"intent\":\"x\",\"action\":{\"tool\":\"read_file\",\"source\":\"alien\"}}", null).statusCode() == 400);
            check("negative amount -> 400", call("POST", "/api/evaluate",
                    "{\"intent\":\"x\",\"action\":{\"tool\":\"make_payment\",\"amount\":-5}}", null).statusCode() == 400);
            check("huge body -> 413", call("POST", "/api/evaluate", "{\"intent\":\"" + "a".repeat(70000) + "\"}", null).statusCode() == 413);
            check("wrong method -> 405", call("GET", "/api/evaluate", null, null).statusCode() == 405);
            check("unknown route -> 404", call("GET", "/api/nothing", null, null).statusCode() == 404);
            check("non-api without frontend -> 404", call("GET", "/", null, null).statusCode() == 404);

            // CORS
            var allowed = call("OPTIONS", "/api/evaluate", null, "http://localhost:5173");
            check("cors preflight 204", allowed.statusCode() == 204);
            check("cors allowed origin echoed",
                    "http://localhost:5173".equals(allowed.headers().firstValue("Access-Control-Allow-Origin").orElse("")));
            var denied = call("GET", "/api/health", null, "http://evil.example");
            check("cors disallowed origin not echoed", denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

            check("clear audit", call("DELETE", "/api/audit", null, null).statusCode() == 200);
            check("audit empty", ((List<?>) Json.parse(call("GET", "/api/audit", null, null).body())).isEmpty());
        } finally {
            s.stop(0);
        }
    }
}
