import { UNTRUSTED } from '../constants.js';

function summarize(a) {
  const bits = [];
  if (a.target) bits.push(`target: ${a.target}`);
  if (a.amount !== undefined && a.amount !== '' && a.amount !== null) bits.push(`amount: ${a.amount}`);
  if (a.content) bits.push(`content: ${a.content.length > 90 ? a.content.slice(0, 90) + '…' : a.content}`);
  return bits.join('  |  ');
}

const COLORS = { allow: 'var(--ok)', confirm: 'var(--warn)', block: 'var(--bad)' };

function Card({ a, onApprove, onDeny }) {
  const r = a.result;
  return (
    <div className={`card ${r ? r.decision : ''}`}>
      <div className="top">
        <span className="tool">{a.tool}</span>
        <span className={`badge ${r ? r.decision : 'pending'}`}>
          {a.evaluating ? 'evaluating…' : r ? `${r.decision === 'confirm' ? 'needs approval' : r.decision} · ${r.score}` : 'not evaluated'}
        </span>
      </div>
      <div className={`src${UNTRUSTED.includes(a.source) ? ' untrusted' : ''}`}>
        from: {a.source.replace('_', ' ')}{a.status ? ` · ${a.status}` : ''}
      </div>
      <div className="args">{summarize(a)}</div>
      {r && (
        <>
          <div className="bar"><i style={{ width: `${r.score}%`, background: COLORS[r.decision] }} /></div>
          <ul className="f">
            {r.findings.map((x, i) => <li key={i} className={x.sev}>{x.text}</li>)}
          </ul>
        </>
      )}
      {r && r.decision === 'confirm' && !a.status && (
        <div className="row">
          <button className="primary" onClick={() => onApprove(a)}>Approve</button>
          <button onClick={() => onDeny(a)}>Deny</button>
        </div>
      )}
    </div>
  );
}

export default function ActionQueue({ queue, busy, onRunAll, onClear, onApprove, onDeny }) {
  return (
    <div>
      <div className="row" style={{ margin: '0 0 12px' }}>
        <button className="primary" onClick={onRunAll} disabled={busy || !queue.length}>Evaluate all</button>
        <button onClick={onClear} disabled={!queue.length}>Clear queue</button>
      </div>
      {queue.length === 0 && <div className="empty">Queue is empty. Pick a scenario or add an action.</div>}
      {queue.map((a) => <Card key={a.id} a={a} onApprove={onApprove} onDeny={onDeny} />)}
    </div>
  );
}
