import { withAuth } from './auth.js';

// Thin wrapper over the Java REST API. Every algorithm runs on the server; the UI only displays results.
async function call(method, path, body) {
  const res = await withAuth((auth) => fetch(`/api${path}`, {
    method,
    headers: { ...auth, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }));
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* non-JSON error page */ }
  if (!res.ok) throw new Error(data?.error || `Request failed (${res.status}). Is the Java backend running on :8081?`);
  return data;
}

export const api = {
  state: () => call('GET', '/state'),
  reset: () => call('POST', '/reset'),
  upload: (scenario) => call('POST', '/scenario', scenario),
  addEmergency: (e) => call('POST', '/emergencies', e),
  escalate: (id, severity) => call('POST', `/emergencies/${id}/escalate`, { severity }),
  arrive: (ambulanceId) => call('POST', `/ambulances/${ambulanceId}/arrive`),
  simulate: (count) => call('POST', '/simulate', { count }),
  dispatch: (req) => call('POST', '/dispatch', req),
  compare: (req) => call('POST', '/compare', req),
  forecast: (req) => call('POST', '/forecast', req),
  benchmark: () => call('GET', '/benchmark'),
};
