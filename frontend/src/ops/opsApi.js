import { withAuth } from '../auth.js';

// REST client for the delivery command center. Every number on screen comes from the Java backend.
async function call(method, path, body) {
  const res = await withAuth((auth) => fetch(`/api/ops${path}`, {
    method,
    headers: { ...auth, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }));
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* non-JSON error page */ }
  if (!res.ok) throw new Error(data?.error || `Request failed (${res.status}). Is the backend running on :8081?`);
  return data;
}

const q = (o) => new URLSearchParams(o).toString();

export const ops = {
  snapshot: () => call('GET', '/snapshot'),
  alerts: (limit = 60) => call('GET', `/alerts?limit=${limit}`),
  ack: (id) => call('POST', `/alerts/${id}/ack`),
  ackAll: () => call('POST', '/alerts/ack-all'),
  previewStore: (p) => call('GET', `/warehouses/preview?${q(Object.fromEntries(Object.entries(p).filter(([, v]) => v != null)))}`),
  openStore: (body) => call('POST', '/warehouses', body),
  setCapacity: (id, capacity) => call('POST', `/warehouses/${id}/capacity`, { capacity }),
  restock: (id) => call('POST', `/warehouses/${id}/restock`),
  moveBetween: (fromStoreId, toStoreId, count) => call('POST', '/riders/move-between', { fromStoreId, toStoreId, count }),
  fillGaps: () => call('POST', '/riders/fill-gaps'),
  trimRiders: () => call('POST', '/riders/trim'),
  hire: (storeId, count) => call('POST', '/riders/hire', { storeId, count }),
  storeHealth: (days) => call('GET', `/warehouses/health?days=${days}`),
  recommendations: () => call('GET', '/recommendations'),
  localityLive: (id) => call('GET', `/localities/${id}/live`),
  warehousesNeeded: (days) => call('GET', `/warehouses/needed?days=${days}`),
  staffing: () => call('GET', '/riders/staffing'),
  applyStaffing: () => call('POST', '/riders/staffing/apply'),
  priority: (policy) => call('POST', '/sim/priority', { policy }),
  localities: (text) => call('GET', `/localities?${q({ q: text })}`),
  boundaries: () => call('GET', '/boundaries'),
  coverage: () => call('GET', '/coverage'),
  aov: (hours) => call('GET', `/analytics/aov?hours=${hours}`),
  customer: (id) => call('GET', `/analytics/customers/${id}`),
  weekdayWeekend: (days) => call('GET', `/analytics/weekday-weekend?days=${days}`),
  compareLocalities: (ids, hours) => call('GET', `/analytics/localities?${q({ ids: ids.join(','), hours })}`),
  heatmap: (type, hours) => call('GET', `/analytics/heatmap?${q({ type, hours })}`),
  revenue: (hours) => call('GET', `/analytics/revenue?hours=${hours}`),
  utilization: () => call('GET', '/analytics/utilization'),
  forecast: () => call('GET', '/forecast/revenue'),
  plan: (budget, target, capacity) => call('GET', `/warehouses/plan?${q({ budget, target, capacity })}`),
  setStoreActive: (id, active) => call('POST', `/warehouses/${id}/active`, { active }),
  cancelOrder: (id) => call('POST', `/orders/${id}/cancel`),
  escalate: (id, minutes) => call('POST', `/orders/${id}/escalate`, { minutes }),
  assign: (id, riderId) => call('POST', `/orders/${id}/assign`, { riderId }),
  moveRider: (id, storeId) => call('POST', `/riders/${id}/move`, { storeId }),
  compareAllocation: () => call('GET', '/allocation/compare'),
  benchmark: () => call('GET', '/benchmark'),
  control: (body) => call('POST', '/sim/control', body),
  riders: (delta) => call('POST', '/sim/riders', { delta }),
  disruption: (body) => call('POST', '/sim/disruptions', body),
};

/** Live snapshot over WebSocket; reconnects every 2 s if the backend restarts. */
export function connectLive(onSnapshot, onStatus) {
  let ws, closed = false, timer;
  const open = () => {
    ws = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/live`);
    ws.onopen = () => onStatus(true);
    ws.onmessage = (e) => onSnapshot(JSON.parse(e.data));
    ws.onclose = () => { onStatus(false); if (!closed) timer = setTimeout(open, 2000); };
  };
  open();
  return () => { closed = true; clearTimeout(timer); ws?.close(); };
}
