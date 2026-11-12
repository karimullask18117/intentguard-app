import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import IntentPanel from './components/IntentPanel.jsx';
import ActionQueue from './components/ActionQueue.jsx';
import AddActionForm from './components/AddActionForm.jsx';
import AuditPanel from './components/AuditPanel.jsx';

const EMPTY_STATS = { allowed: 0, pendingApproval: 0, blocked: 0, total: 0 };

export default function App() {
  const [scenarios, setScenarios] = useState([]);
  const [active, setActive] = useState('');
  const [intent, setIntent] = useState('');
  const [mode, setMode] = useState('balanced');
  const [parsed, setParsed] = useState(null);
  const [queue, setQueue] = useState([]);
  const [audit, setAudit] = useState([]);
  const [stats, setStats] = useState(EMPTY_STATS);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const nextId = useRef(1);
  const epoch = useRef(0); // bumped whenever intent/mode/queue is reset, so stale responses are ignored

  const fail = (e) => setError(e.message || String(e));

  const refreshAudit = useCallback(async () => {
    try {
      const [a, s] = await Promise.all([api.audit(), api.stats()]);
      setAudit(a);
      setStats(s);
    } catch (e) {
      fail(e);
    }
  }, []);

  // initial load
  useEffect(() => {
    api.scenarios()
      .then((list) => {
        setScenarios(list);
        if (list.length) loadScenario(list[0]);
      })
      .catch(fail);
    refreshAudit();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // live preview of what the backend understood from the intent (debounced)
  useEffect(() => {
    if (!intent.trim()) { setParsed(null); return undefined; }
    const t = setTimeout(() => {
      api.parseIntent(intent).then(setParsed).catch(fail);
    }, 250);
    return () => clearTimeout(t);
  }, [intent]);

  const resetResults = () => {
    epoch.current += 1;
    setQueue((q) => q.map(({ result, status, evaluating, entryId, ...rest }) => rest));
  };

  const loadScenario = (s) => {
    epoch.current += 1;
    setError('');
    setActive(s.name);
    setIntent(s.intent);
    setQueue(s.actions.map((a) => ({ id: nextId.current++, ...a, amount: a.amount ?? undefined })));
  };

  const onIntent = (v) => { setActive(''); setIntent(v); resetResults(); };
  const onMode = (v) => { setMode(v); resetResults(); };

  const evaluateOne = async (a) => {
    const my = epoch.current;
    setQueue((q) => q.map((x) => (x.id === a.id ? { ...x, evaluating: true } : x)));
    try {
      const { id, result, status, evaluating, entryId, ...action } = a;
      const res = await api.evaluate(intent, mode, action);
      if (my !== epoch.current) return; // intent/mode/queue changed meanwhile
      const status2 = res.decision === 'allow' ? 'executed' : res.decision === 'block' ? 'stopped' : '';
      setQueue((q) => q.map((x) => (x.id === a.id
        ? { ...x, evaluating: false, entryId: res.entry.id, status: status2,
            result: { score: res.score, decision: res.decision, findings: res.findings } }
        : x)));
      await refreshAudit();
    } catch (e) {
      setQueue((q) => q.map((x) => (x.id === a.id ? { ...x, evaluating: false } : x)));
      fail(e);
    }
  };

  const runAll = async () => {
    setError('');
    setBusy(true);
    const my = epoch.current;
    for (const a of queue.filter((x) => !x.result)) {
      if (my !== epoch.current) break; // intent/mode/queue changed; stop evaluating stale items
      // eslint-disable-next-line no-await-in-loop
      await evaluateOne(a);
    }
    setBusy(false);
  };

  const addAction = (action) => {
    setError('');
    const a = { id: nextId.current++, ...action };
    setQueue((q) => [...q, a]);
    evaluateOne(a);
  };

  const resolve = async (a, approved) => {
    try {
      await api.resolve(a.entryId, approved);
      setQueue((q) => q.map((x) => (x.id === a.id ? { ...x, status: approved ? 'executed after approval' : 'denied by you' } : x)));
      await refreshAudit();
    } catch (e) {
      fail(e);
    }
  };

  const clearAudit = async () => {
    try {
      await api.clearAudit();
      await refreshAudit();
    } catch (e) {
      fail(e);
    }
  };

  return (
    <>
      <header>
        <svg className="logo" viewBox="0 0 32 32" aria-hidden="true">
          <path d="M16 2 4 7v8c0 7.5 5 13 12 15 7-2 12-7.5 12-15V7L16 2Z" fill="none" stroke="#2dd4bf" strokeWidth="2" />
          <path d="m10.5 16 4 4 7-8" fill="none" stroke="#2dd4bf" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <div>
          <h1>IntentGuard</h1>
          <p>Checks every AI-agent action against what you actually asked for, before it runs.</p>
        </div>
      </header>
      {error && (
        <div className="error" role="alert">
          {error} <button onClick={() => setError('')}>Dismiss</button>
        </div>
      )}
      <main>
        <IntentPanel
          intent={intent} onIntent={onIntent} mode={mode} onMode={onMode}
          parsed={parsed} scenarios={scenarios} active={active} onScenario={loadScenario}
        />
        <section>
          <h2>2 · Agent action queue</h2>
          <ActionQueue
            queue={queue} busy={busy} onRunAll={runAll}
            onClear={() => { epoch.current += 1; setQueue([]); }}
            onApprove={(a) => resolve(a, true)} onDeny={(a) => resolve(a, false)}
          />
          <AddActionForm onAdd={addAction} disabled={!intent.trim()} />
        </section>
        <AuditPanel audit={audit} stats={stats} onClear={clearAudit} />
      </main>
    </>
  );
}
