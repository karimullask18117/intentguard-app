import { useState } from 'react';

export default function AuditPanel({ audit, stats, onClear }) {
  const [msg, setMsg] = useState('');

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(JSON.stringify(audit, null, 2));
      setMsg('Audit JSON copied.');
    } catch {
      setMsg('Copy was blocked by the browser.');
    }
  };

  return (
    <section>
      <h2>3 · Audit trail</h2>
      <div className="stats">
        <div className="stat"><b style={{ color: 'var(--ok)' }}>{stats.allowed}</b>allowed</div>
        <div className="stat"><b style={{ color: 'var(--warn)' }}>{stats.pendingApproval}</b>need approval</div>
        <div className="stat"><b style={{ color: 'var(--bad)' }}>{stats.blocked}</b>blocked</div>
      </div>
      <div className="log">
        {audit.length === 0 && <div className="empty">No decisions yet.</div>}
        {[...audit].reverse().map((e) => (
          <div key={e.id}>
            <time>{new Date(e.time).toLocaleTimeString()}</time> <b>{e.tool}</b> → {e.outcome} (risk {e.score})
            <br />
            <span style={{ color: 'var(--mute)' }}>{e.target}</span>
          </div>
        ))}
      </div>
      <div className="row">
        <button onClick={copy} disabled={!audit.length}>Copy audit JSON</button>
        <button onClick={() => { setMsg(''); onClear(); }} disabled={!audit.length}>Clear log</button>
      </div>
      <p className="note">{msg}</p>
    </section>
  );
}
