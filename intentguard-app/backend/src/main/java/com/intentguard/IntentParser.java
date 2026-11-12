package com.intentguard;

import com.intentguard.Models.ParsedIntent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts what the user authorised: action types, recipients, domains, files and amounts. */
public final class IntentParser {
    private IntentParser() {}

    static final String FILE_EXT = "py|md|txt|js|ts|json|csv|pdf|docx|xlsx|yml|yaml|sh|html|css|log|png|jpg|ini|toml|xml";
    private static final int CI = Pattern.CASE_INSENSITIVE;

    static final Map<String, Pattern> CATS = new LinkedHashMap<>();
    static {
        CATS.put("read", Pattern.compile("\\b(read|summari[sz]e|review|find|search|look|check|list|show|open|analy[sz]e|extract|get|fetch|compare|explain|translate|research)\\b", CI));
        CATS.put("write", Pattern.compile("\\b(write|draft|edit|update|create|rewrite|fix|save|schedule|add|rename|format|note)\\b", CI));
        CATS.put("send", Pattern.compile("\\b(send|email|reply|forward|notify|submit|upload|post (?:it|this|that|to)|share (?:it|this|that|with)|message (?:him|her|them|the))\\b", CI));
        CATS.put("delete", Pattern.compile("\\b(delete|remove|erase|clean up|clear (?:the|all|my)|purge|drop)\\b", CI));
        CATS.put("shell", Pattern.compile("\\b(run|execute|install|build|deploy|compile|test|tests|script|command)\\b", CI));
        CATS.put("pay", Pattern.compile("\\b(pay|buy|purchase|transfer|order (?:a|the|me|it)|refund|checkout)\\b", CI));
    }

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern DOMAIN = Pattern.compile("\\b(?:https?://)?(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b", CI);
    private static final Pattern FILE_EXT_END = Pattern.compile("\\.(" + FILE_EXT + ")$", CI);
    private static final Pattern FILE = Pattern.compile("[\\w./~-]*[\\w-]\\.(?:" + FILE_EXT + ")\\b", CI);
    private static final Pattern AMOUNT_SYMBOL = Pattern.compile("[$\u20B9\u20AC\u00A3]\\s?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern AMOUNT_WORD = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?\\s?(?:usd|dollars|inr|eur)\\b", CI);

    public static ParsedIntent parse(String text) {
        String t = text == null ? "" : text;

        List<String> cats = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : CATS.entrySet())
            if (e.getValue().matcher(t).find()) cats.add(e.getKey());

        Set<String> emails = new LinkedHashSet<>();
        Matcher m = EMAIL.matcher(t);
        while (m.find()) emails.add(m.group().toLowerCase(Locale.ROOT));

        Set<String> domains = new LinkedHashSet<>();
        for (String e : emails) domains.add(e.substring(e.indexOf('@') + 1));
        m = DOMAIN.matcher(t);
        while (m.find()) {
            String h = m.group().replaceFirst("(?i)^https?://", "").toLowerCase(Locale.ROOT);
            if (!FILE_EXT_END.matcher(h).find()) domains.add(h);
        }

        Set<String> files = new LinkedHashSet<>();
        m = FILE.matcher(t);
        while (m.find()) {
            String f = m.group().toLowerCase(Locale.ROOT);
            boolean partOfEmail = false;
            for (String e : emails) if (e.contains(f)) { partOfEmail = true; break; }
            if (!partOfEmail) files.add(f);
        }

        List<Double> amounts = new ArrayList<>();
        m = AMOUNT_SYMBOL.matcher(t);
        while (m.find()) amounts.add(num(m.group()));
        m = AMOUNT_WORD.matcher(t);
        while (m.find()) amounts.add(num(m.group()));

        return new ParsedIntent(cats, new ArrayList<>(emails), new ArrayList<>(domains),
                new ArrayList<>(files), amounts);
    }

    private static double num(String s) {
        return Double.parseDouble(s.replaceAll("[^\\d.]", ""));
    }
}
