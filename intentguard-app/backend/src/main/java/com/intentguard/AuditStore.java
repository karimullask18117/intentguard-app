package com.intentguard;

import com.intentguard.Models.AuditEntry;
import com.intentguard.Models.Decision;
import com.intentguard.Models.EvaluationResult;
import com.intentguard.Models.ActionRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Thread-safe in-memory audit trail (newest last), capped to keep memory bounded. */
public final class AuditStore {
    private static final int MAX = 500;
    private final List<AuditEntry> entries = new ArrayList<>();

    public synchronized AuditEntry record(ActionRequest a, EvaluationResult r) {
        String outcome = switch (r.decision()) {
            case ALLOW -> "allowed";
            case BLOCK -> "blocked";
            case CONFIRM -> "awaiting approval";
        };
        List<String> reasons = r.findings().stream().map(Models.Finding::text).toList();
        AuditEntry e = new AuditEntry(UUID.randomUUID().toString(), Instant.now().toString(), a.tool(),
                a.target() == null ? "" : a.target(), a.source(), r.score(), r.decision(), outcome, reasons);
        entries.add(e);
        if (entries.size() > MAX) entries.remove(0);
        return e;
    }

    public synchronized List<AuditEntry> list() {
        return List.copyOf(entries);
    }

    /** Resolves an entry that is awaiting approval. */
    public synchronized AuditEntry resolve(String id, boolean approved) {
        for (int i = 0; i < entries.size(); i++) {
            AuditEntry e = entries.get(i);
            if (!e.id().equals(id)) continue;
            if (e.decision() != Decision.CONFIRM || !e.outcome().equals("awaiting approval"))
                throw new IllegalStateException("This action is not awaiting approval.");
            AuditEntry n = new AuditEntry(e.id(), e.time(), e.tool(), e.target(), e.source(), e.score(),
                    e.decision(), approved ? "approved" : "denied", e.reasons());
            entries.set(i, n);
            return n;
        }
        throw new NoSuchElementException("No audit entry with id " + id);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized Map<String, Object> stats() {
        int allowed = 0, blocked = 0, pending = 0;
        for (AuditEntry e : entries) {
            switch (e.outcome()) {
                case "allowed", "approved" -> allowed++;
                case "blocked", "denied" -> blocked++;
                default -> pending++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("allowed", allowed);
        m.put("pendingApproval", pending);
        m.put("blocked", blocked);
        m.put("total", entries.size());
        return m;
    }
}
