import { MODES } from '../constants.js';

function Tags({ label, items }) {
  return (
    <div>
      {label}:{' '}
      {items.length ? items.map((x) => <span className="tag" key={x}>{String(x)}</span>) : <span className="tag">none</span>}
    </div>
  );
}

export default function IntentPanel({ intent, onIntent, mode, onMode, parsed, scenarios, active, onScenario }) {
  return (
    <section>
      <h2>1 · Your intent</h2>
      <label htmlFor="intent">What did you ask the agent to do?</label>
      <textarea id="intent" value={intent} maxLength={2000} onChange={(e) => onIntent(e.target.value)} />
      <div className="parsed">
        {parsed ? (
          <>
            <Tags label="Allowed action types" items={parsed.actionTypes} />
            <Tags label="Named recipients" items={parsed.emails} />
            <Tags label="Named domains" items={parsed.domains} />
            <Tags label="Named files" items={parsed.files} />
            <Tags label="Authorised amounts" items={parsed.amounts} />
          </>
        ) : (
          <span>Type an intent to see what the agent is allowed to do.</span>
        )}
      </div>

      <label htmlFor="mode">Policy strictness</label>
      <select id="mode" value={mode} onChange={(e) => onMode(e.target.value)}>
        {MODES.map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
      </select>

      <h2 style={{ marginTop: 16 }}>Scenarios</h2>
      <div className="chips row">
        {scenarios.map((s) => (
          <button key={s.name} className={active === s.name ? 'on' : ''} onClick={() => onScenario(s)}>
            {s.name}
          </button>
        ))}
      </div>
      <p className="note">
        Each scenario loads an intent plus actions an agent wants to take. Some are legitimate; some come
        from prompt injection hidden in emails, web pages or documents.
      </p>
    </section>
  );
}
