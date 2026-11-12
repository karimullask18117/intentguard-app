// Thin client for the IntentGuard Java backend.
async function request(method, path, body) {
  let res;
  try {
    res = await fetch(`/api${path}`, {
      method,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new Error('Cannot reach the IntentGuard backend. Start it with: cd backend && ./build.sh run');
  }
  let data = null;
  try {
    data = await res.json();
  } catch {
    /* non-JSON response */
  }
  if (!res.ok) throw new Error((data && data.error) || `Request failed (${res.status})`);
  return data;
}

export const api = {
  scenarios: () => request('GET', '/scenarios'),
  parseIntent: (intent) => request('POST', '/parse-intent', { intent }),
  evaluate: (intent, mode, action) => request('POST', '/evaluate', { intent, mode, action }),
  audit: () => request('GET', '/audit'),
  stats: () => request('GET', '/audit/stats'),
  resolve: (id, approved) => request('POST', `/audit/${encodeURIComponent(id)}/resolve`, { approved }),
  clearAudit: () => request('DELETE', '/audit'),
};
