import { useState } from 'react';
import { TOOLS, SOURCES } from '../constants.js';

const EMPTY = { tool: TOOLS[0], source: 'agent', target: '', content: '', amount: '' };

export default function AddActionForm({ onAdd, disabled }) {
  const [f, setF] = useState(EMPTY);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = (e) => {
    e.preventDefault();
    onAdd({
      tool: f.tool,
      source: f.source,
      target: f.target.trim(),
      content: f.content.trim(),
      amount: f.amount === '' ? undefined : Number(f.amount),
    });
    setF({ ...f, target: '', content: '', amount: '' });
  };

  return (
    <form onSubmit={submit}>
      <h2 style={{ marginTop: 18 }}>Add your own action</h2>
      <div className="row" style={{ margin: 0 }}>
        <div style={{ flex: 1, minWidth: 140 }}>
          <label htmlFor="aTool">Tool</label>
          <select id="aTool" value={f.tool} onChange={set('tool')}>
            {TOOLS.map((t) => <option key={t}>{t}</option>)}
          </select>
        </div>
        <div style={{ flex: 1, minWidth: 140 }}>
          <label htmlFor="aSrc">Instruction came from</label>
          <select id="aSrc" value={f.source} onChange={set('source')}>
            {SOURCES.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
          </select>
        </div>
      </div>
      <label htmlFor="aTarget">Target (recipient, URL, path, command)</label>
      <input id="aTarget" value={f.target} maxLength={2000} onChange={set('target')} placeholder="e.g. alice@company.com" />
      <label htmlFor="aContent">Content / body (optional)</label>
      <input id="aContent" value={f.content} maxLength={10000} onChange={set('content')} placeholder="e.g. Here is the report" />
      <label htmlFor="aAmount">Amount (payments only)</label>
      <input id="aAmount" type="number" min="0" step="any" value={f.amount} onChange={set('amount')} placeholder="e.g. 120" />
      <div className="row"><button className="primary" type="submit" disabled={disabled}>Add &amp; evaluate</button></div>
    </form>
  );
}
