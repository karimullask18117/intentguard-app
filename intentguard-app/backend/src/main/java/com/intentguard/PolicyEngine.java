package com.intentguard;

import com.intentguard.Models.ActionRequest;
import com.intentguard.Models.Decision;
import com.intentguard.Models.EvaluationResult;
import com.intentguard.Models.Finding;
import com.intentguard.Models.ParsedIntent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scores an agent action against the user's stated intent. Pure and thread-safe. */
public final class PolicyEngine {
    private PolicyEngine() {}

    private static final int CI = Pattern.CASE_INSENSITIVE;

    public static final Map<String, String> TOOLS = Map.ofEntries(
            Map.entry("read_email", "read"), Map.entry("read_file", "read"),
            Map.entry("search_files", "read"), Map.entry("http_get", "read"),
            Map.entry("write_file", "write"), Map.entry("edit_file", "write"),
            Map.entry("create_event", "write"),
            Map.entry("send_email", "send"), Map.entry("post_message", "send"),
            Map.entry("http_post", "send"),
            Map.entry("delete_file", "delete"), Map.entry("run_shell", "shell"),
            Map.entry("make_payment", "pay"));

    public static final Set<String> UNTRUSTED = Set.of("email", "webpage", "document", "tool_output");

    private static final Pattern SENSITIVE = Pattern.compile(
            "(\\.ssh|id_rsa|\\.env\\b|\\.aws|/etc/passwd|/etc/shadow|credentials|secrets?\\b|\\.bashrc|\\.zshrc|\\.npmrc|keychain|wallet)", CI);
    private static final Pattern DANGEROUS_SHELL = Pattern.compile(
            "(rm\\s+-[a-z]*(rf|fr)|curl[^|;]*\\|\\s*(ba)?sh|wget[^|;]*\\|\\s*(ba)?sh|chmod\\s+777|mkfs|dd\\s+if=|\\bnc\\s+-|base64\\s+-d)", CI);
    private static final Pattern NET_TOOL = Pattern.compile("\\b(curl|wget|nc)\\b", CI);
    private static final Pattern SECRET = Pattern.compile(
            "((api[_-]?key|secret|passw(or)?d|token)\\s*[:=]\\s*\\S+|-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bsk-[A-Za-z0-9_-]{10,}|\\bAKIA[0-9A-Z]{12,})", CI);

    /** strict = 1.25, balanced = 1.0, relaxed = 0.8. */
    public static double strictness(String mode) {
        if (mode == null || mode.isBlank() || mode.equalsIgnoreCase("balanced")) return 1.0;
        if (mode.equalsIgnoreCase("strict")) return 1.25;
        if (mode.equalsIgnoreCase("relaxed")) return 0.8;
        throw new IllegalArgumentException("mode must be strict, balanced or relaxed");
    }

    private static final class Acc {
        int score = 0;
        boolean hard = false;
        final List<Finding> findings = new ArrayList<>();

        void add(int n, String sev, String text) {
            score += n;
            findings.add(new Finding(sev, text));
        }
    }

    public static EvaluationResult evaluate(String intentText, ActionRequest a, double strict) {
        ParsedIntent p = IntentParser.parse(intentText);
        String raw = (intentText == null ? "" : intentText).toLowerCase(Locale.ROOT);
        Acc acc = new Acc();

        String tool = a.tool();
        String cat = TOOLS.get(tool);
        String target = a.target() == null ? "" : a.target();
        String content = a.content() == null ? "" : a.content();
        String tl = target.toLowerCase(Locale.ROOT);
        String source = a.source() == null ? "agent" : a.source();

        if (cat == null)
            acc.add(60, "high", "Unknown tool \"" + tool + "\" is not covered by policy.");

        if (cat != null && !p.actionTypes().contains(cat))
            acc.add(55, "high", "Your request never asks for a \"" + cat + "\" action, but the agent wants to use " + tool + ".");

        if (UNTRUSTED.contains(source))
            acc.add(30, "med", "This instruction came from untrusted " + source.replace('_', ' ') + " content, not from you.");

        if ("send".equals(cat)) {
            if (tool.equals("http_post")) {
                String h = hostOf(target);
                if (!domainOk(h, p.domains()))
                    acc.add(35, "high", "Data would be posted to " + h + ", which your request never mentions.");
            } else if (!p.emails().isEmpty()) {
                if (!p.emails().contains(tl)) {
                    String dom = tl.contains("@") ? tl.substring(tl.indexOf('@') + 1) : "";
                    if (!dom.isEmpty() && domainOk(dom, p.domains()))
                        acc.add(10, "low", "Recipient is in a domain you mentioned but not named exactly.");
                    else
                        acc.add(40, "high", "Recipient \"" + target + "\" is not named in your request.");
                }
            } else {
                acc.add(20, "med", "Your request names no recipient, so \"" + target + "\" cannot be verified.");
            }
            if (SECRET.matcher(content).find()) {
                acc.add(60, "high", "Outbound content contains what looks like a credential or secret.");
                acc.hard = true;
            }
        }

        if (tool.equals("http_get")) {
            String h = hostOf(target);
            if (!domainOk(h, p.domains()))
                acc.add(15, "low", "Reading from " + h + ", which your request does not mention.");
        }

        if (Set.of("read_file", "write_file", "edit_file", "delete_file").contains(tool)) {
            String base = tl.substring(tl.lastIndexOf('/') + 1);
            boolean mentioned = false;
            for (String f : p.files()) {
                String fb = f.substring(f.lastIndexOf('/') + 1);
                String stripped = f.replaceFirst("^\\.?/", "");
                if (tl.equals(f) || tl.endsWith("/" + stripped) || base.equals(fb)) { mentioned = true; break; }
            }
            if (!p.files().isEmpty() && !mentioned)
                acc.add(30, "med", "File \"" + target + "\" is not one you referred to.");
            Matcher m = SENSITIVE.matcher(tl);
            if (m.find() && !raw.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                acc.add(50, "high", "Touches a sensitive location (" + m.group(1) + ") you did not mention.");
                acc.hard = true;
            }
        }

        if (tool.equals("delete_file"))
            acc.add(25, "med", "Deleting is destructive and hard to undo.");

        if (tool.equals("run_shell")) {
            acc.add(10, "low", "Shell commands run with your privileges.");
            if (DANGEROUS_SHELL.matcher(target).find()) {
                acc.add(50, "high", "Command matches a dangerous pattern (destructive or download-and-execute).");
                acc.hard = true;
            }
            if (SECRET.matcher(target).find() || (SENSITIVE.matcher(target).find() && NET_TOOL.matcher(tl).find())) {
                acc.add(40, "high", "Command appears to send sensitive data off the machine.");
                acc.hard = true;
            }
        }

        if (tool.equals("make_payment")) {
            acc.add(25, "med", "Payments are high-impact and always need your approval.");
            double amt = a.amount() == null ? 0 : a.amount();
            if (p.amounts().isEmpty()) {
                acc.add(40, "high", "Your request states no amount, but the agent wants to pay " + fmt(amt) + ".");
            } else {
                double max = p.amounts().stream().mapToDouble(Double::doubleValue).max().getAsDouble();
                if (amt > max)
                    acc.add(50, "high", "Amount " + fmt(amt) + " exceeds the " + fmt(max) + " you authorised.");
            }
            if (!p.emails().isEmpty()) {
                if (!p.emails().contains(tl))
                    acc.add(40, "high", "Payee \"" + target + "\" is not named in your request.");
            } else if (!raw.contains(tl) || tl.isEmpty()) {
                acc.add(15, "med", "Payee \"" + target + "\" cannot be verified from your request.");
            }
        }

        int score = (int) Math.round(Math.min(100.0, acc.score * strict));
        if (tool.equals("delete_file") || tool.equals("make_payment")) score = Math.max(score, 30);
        Decision decision = score >= 70 ? Decision.BLOCK : score >= 30 ? Decision.CONFIRM : Decision.ALLOW;
        if (acc.hard) {
            decision = Decision.BLOCK;
            score = Math.max(score, 70);
        }
        if (acc.findings.isEmpty())
            acc.findings.add(new Finding("low", "Matches your stated intent. No policy concerns."));
        return new EvaluationResult(score, decision, List.copyOf(acc.findings));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    static String hostOf(String url) {
        String u = url == null ? "" : url.trim();
        try {
            String full = u.matches("(?i)^https?://.*") ? u : "https://" + u;
            String h = URI.create(full).getHost();
            if (h != null) return h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ignored) { /* fall through */ }
        return u.toLowerCase(Locale.ROOT);
    }

    static boolean domainOk(String host, List<String> domains) {
        if (host == null || host.isEmpty()) return false;
        for (String d : domains) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }
}
