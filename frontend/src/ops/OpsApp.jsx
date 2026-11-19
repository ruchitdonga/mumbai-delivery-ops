import { useEffect, useMemo, useState } from 'react';
import { ops, connectLive } from './opsApi.js';
import OpsMap, { RIDER_COLORS } from './OpsMap.jsx';
import { BarChart, LineChart, inr } from './charts.jsx';
import './ops.css';

const WINDOWS = { '24 h': 24, '7 days': 168, '21 days': 504 };
const HEAT_TYPES = { ALL_UNMET: 'All unmet demand', UNSERVICEABLE: 'Unserviceable address', OUT_OF_STOCK: 'Out of stock', ABANDONED_CART: 'Abandoned cart', SEARCH: 'Searches' };

export default function OpsApp({ onSwitch }) {
  const [snap, setSnap] = useState(null);
  const [connected, setConnected] = useState(false);
  const [alerts, setAlerts] = useState([]);
  const [error, setError] = useState('');
  const [tab, setTab] = useState('start');
  const [layers, setLayers] = useState({ areas: true, stores: true, riders: true, orders: true, riskOnly: false, coverage: true, heat: false, traffic: false });
  const [coverage, setCoverage] = useState([]);
  const [boundaries, setBoundaries] = useState([]);
  const [heatType, setHeatType] = useState('ALL_UNMET');
  const [heatHours, setHeatHours] = useState(24);
  const [heat, setHeat] = useState([]);
  const [sites, setSites] = useState(null);
  const [draft, setDraft] = useState(null);
  const [focus, setFocus] = useState(null);
  const [selectedLocality, setSelectedLocality] = useState(null);
  const [showLayers, setShowLayers] = useState(() => window.innerWidth > 900);
  const [nextHour, setNextHour] = useState(null);
  const [pendingStore, setPendingStore] = useState(null); // { preview, resolve }

  // fly=true only for the first look; moving the pin should not re-zoom the map under the admin's hand.
  const loadPreview = (where, fly = false) => ops.previewStore(where).then((preview) => {
    setPendingStore((cur) => cur && { ...cur, preview });
    if (fly) setFocus([preview.lat, preview.lng]);
  }).catch((e) => setError(e.message));
  askOpenStore = (where) => new Promise((resolve) => {
    setPendingStore((cur) => { cur?.resolve(false); return { preview: null, resolve }; }); // a newer preview replaces an open one
    loadPreview(where, true);
  });
  const closePreview = (result) => {
    pendingStore?.resolve(result);
    setPendingStore(null);
    if (result) setTimeout(() => ops.coverage().then(setCoverage), 1500);
  };

  // Point 2: 1-hour revenue estimate, refreshed every minute.
  useEffect(() => {
    const load = () => ops.forecast().then(setNextHour).catch(() => {});
    load();
    const t = setInterval(load, 60000);
    return () => clearInterval(t);
  }, []);

  const run = async (fn) => {
    setError('');
    try { return await fn(); } catch (e) { setError(e.message); }
  };

  useEffect(() => {
    run(async () => {
      setBoundaries(await ops.boundaries());
      setAlerts(await ops.alerts());
      setCoverage(await ops.coverage());
    });
    return connectLive((s) => {
      setSnap(s);
      if (s.newAlerts?.length) setAlerts((a) => [...[...s.newAlerts].reverse(), ...a].slice(0, 150));
    }, setConnected);
  }, []);

  // Coverage polygons change with traffic, scenario, closures and store toggles: refresh every 15 s.
  useEffect(() => {
    const t = setInterval(() => ops.coverage().then(setCoverage).catch(() => {}), 15000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    if (layers.heat) run(async () => setHeat(await ops.heatmap(heatType, heatHours)));
  }, [layers.heat, heatType, heatHours]);

  const k = snap?.kpis;
  return (
    <div className="ops">
      <header className="ops-head">
        <div className="ops-brand">🛵 Mumbai Delivery Ops <span className="ops-muted">command center</span></div>
        <div className={`ops-pill ${snap?.running ? 'live' : ''}`}>{snap?.running ? '● LIVE' : '❚❚ PAUSED'}</div>
        <div className="ops-pill">🕒 {snap ? snap.simTime.replace('T', ' ') : '…'} <span className="ops-muted">sim time</span></div>
        <div className={`ops-pill scen-${snap?.scenario}`}>{snap?.scenario ?? '…'}</div>
        {!connected && <div className="ops-pill warn">reconnecting…</div>}
        <LocalitySearch onPick={(l) => { setFocus([l.lat, l.lng]); setSelectedLocality(l.id); }} />
        <button className="ops-btn ghost" onClick={onSwitch}>🚑 Ambulance app (CIA 3)</button>
      </header>
      <div className="ops-sim-note">All orders, customers, riders, traffic and disruptions are <b>simulated</b>. Map tiles, road network (OSRM) and locality names are real; locality boundaries are approximate.
        {snap && ` Road distances: ${snap.roadDistancesPending > 0 ? `${snap.roadDistancesPending} still being fetched from OSRM (straight-line estimate meanwhile)` : 'all from OSRM'}.`}</div>

      <section className="ops-kpis">
        <Kpi label="Revenue today" value={inr(k?.revenueToday)} />
        <Kpi label="Next 1 h revenue (est.)" value={nextHour ? inr(nextHour.forecastNextHour) : '–'}
          sub={nextHour ? `from ${nextHour.nextHourStart.slice(11, 16)} · ±${nextHour.backtestWapePct ?? '–'}% WAPE` : ''} />
        <Kpi label="Orders in progress" value={k?.activeOrders} sub={`${k?.queueSize ?? 0} waiting for a rider`} help="Orders placed but not delivered yet." />
        <Kpi label="Riders on a delivery" value={k?.activeRiders} sub={`of ${k?.ridersOnShift ?? 0} on shift`} help="Riders going to a store, carrying orders or riding back." />
        <Kpi label="Riders free now" value={k?.availableRiders} sub="waiting at their store" help="Free riders wait at their dark store, because every delivery starts with a pickup there." />
        <Kpi label="Average order value" value={inr(k?.aov)} sub="AOV today" help="Revenue today ÷ orders today: how much a typical order is worth." />
        <Kpi label="On time (SLA met)" value={k?.slaPct == null ? '–' : `${k.slaPct}%`}
          sub={`today (${k?.deliveredToday ?? 0}) · last hour ${k?.slaLastHourPct ?? '–'}%`}
          help="SLA = the delivery time we promise the customer (20 min for small baskets, 30 for big ones, +10 in monsoon). This is the share of today's deliveries that arrived before that promise."
          tone={k?.slaPct != null && k.slaPct < 90 ? 'bad' : 'good'} />
        <Kpi label="Avg delivery time" value={k?.avgDeliveryMin == null ? '–' : `${k.avgDeliveryMin} min`}
          sub={`today · last hour ${k?.avgDeliveryLastHourMin ?? '–'} min`} help="Order placed → at the door. The big number is the whole day; a bad rush hour keeps it high for hours after service recovers, so the last hour is shown too." />
        <Kpi label="How busy stores are" value={k ? `${k.warehouseUtilPct}%` : '–'} sub="store utilisation"
          help="Store utilisation = orders the dark stores received in the last hour ÷ how many they can pack per hour (their hourly capacity). 10% = mostly idle, 100% = at the limit." />
      </section>
      <p className="ops-kpi-help">Hover or tap a number to see what it means · new here? open the <button className="linklike" onClick={() => setTab('start')}>Start here</button> tab.</p>

      {error && <div className="ops-error" role="alert">⚠ {error} <button onClick={() => setError('')}>×</button></div>}

      <main className="ops-main">
        <div className="ops-mapwrap">
          <OpsMap snap={snap} layers={layers} coverage={coverage} boundaries={boundaries} heat={heat} sites={sites}
            draft={draft} onPick={setDraft} focus={focus} selectedLocality={selectedLocality}
            preview={pendingStore?.preview} onPreviewMove={(lat, lng) => loadPreview({ lat, lng })} />
          {pendingStore && <StorePreview pending={pendingStore} onClose={closePreview} run={run} />}
          {selectedLocality && <LocalityLive id={selectedLocality} simTime={snap?.simTime} onClose={() => setSelectedLocality(null)} />}
          <div className={`ops-layers ${showLayers ? '' : 'closed'}`}>
            <button className="small" aria-expanded={showLayers} onClick={() => setShowLayers(!showLayers)}>{showLayers ? 'Layers ▴' : 'Layers ▾'}</button>
            {showLayers && [['areas', 'Area names'], ['stores', 'Dark stores'], ['riders', 'Riders'], ['orders', 'Orders'], ['riskOnly', 'SLA-risk only'], ['coverage', 'Coverage'], ['traffic', 'Traffic'], ['heat', 'Demand heatmap']].map(([key, label]) => (
              <label key={key}><input type="checkbox" checked={layers[key]} onChange={(e) => setLayers({ ...layers, [key]: e.target.checked })} /> {label}</label>
            ))}
            {showLayers && layers.heat && (
              <>
                <select value={heatType} onChange={(e) => setHeatType(e.target.value)}>
                  {Object.entries(HEAT_TYPES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
                <select value={heatHours} onChange={(e) => setHeatHours(Number(e.target.value))}>
                  {Object.entries(WINDOWS).map(([l, h]) => <option key={h} value={h}>{l}</option>)}
                </select>
              </>
            )}
            {showLayers && <div className="ops-legend">
              {Object.entries(RIDER_COLORS).slice(0, 4).map(([s, c]) => <span key={s}><i style={{ background: c }} />{s.replace('_', ' ').toLowerCase()}</span>)}
              <span><i style={{ background: '#22c55e' }} />order ok</span><span><i style={{ background: '#f59e0b' }} />at risk</span><span><i style={{ background: '#ef4444' }} />late</span>
            </div>}
          </div>
        </div>

        <aside className="ops-side">
          <nav className="ops-tabs">
            {[['start', 'Start here'], ['live', 'Live'], ['analytics', 'Analytics'], ['planning', 'Planning'], ['simulation', 'Simulation']].map(([t, l]) => (
              <button key={t} className={tab === t ? 'on' : ''} onClick={() => setTab(t)}>{l}</button>
            ))}
          </nav>
          <div className="ops-panel">
            {tab === 'start' && <StartTab snap={snap} run={run} onFocus={setFocus} />}
            {tab === 'live' &&<LiveTab snap={snap} alerts={alerts} setAlerts={setAlerts} run={run} />}
            {tab === 'analytics' && <AnalyticsTab run={run} boundaries={boundaries} />}
            {tab === 'planning' && <PlanningTab snap={snap} run={run} setSites={setSites} setCoverage={setCoverage} onFocus={setFocus} />}
            {tab === 'simulation' && <SimulationTab snap={snap} run={run} draft={draft} setDraft={setDraft} />}
          </div>
        </aside>
      </main>
    </div>
  );
}

const ACTION_LABEL = {
  MOVE_RIDERS: 'Move riders', MOVE_ONE: 'Move', HIRE_RIDERS: 'Hire', SET_CAPACITY: 'Raise capacity', RESTOCK: 'Restock', OPEN_STORE: 'Open this store',
  ACTIVATE_STORE: 'Re-open', PRIORITY_SLA: 'Switch', APPLY_STAFFING: 'Move them', ADD_RIDERS: 'Add 5', REMOVE_RIDERS: 'Take off shift', FILL_GAPS: 'Bring them in', TRIM_RIDERS: 'Take them off', SHOW_AREA: 'Show on map',
};

// Set by OpsApp: opens the store preview on the map and resolves once the admin opens the store or cancels.
let askOpenStore = null;

/** Preview of a new dark store: where it goes, what it would take over, and the decision. Drag the map pin to move it. */
function StorePreview({ pending, onClose, run }) {
  const p = pending.preview;
  const [busy, setBusy] = useState(false);
  if (!p) return <div className="ops-preview"><b>Finding the best spot…</b></div>;
  return (
    <div className="ops-preview" role="dialog" aria-label="New dark store preview">
      <div className="ops-row"><b>🏬 New store: {p.name}</b><span className="ops-muted">{p.code}</span></div>
      <div className="ops-muted">📍 {p.locality} · {p.lat.toFixed(4)}, {p.lng.toFixed(4)} · <b>drag the yellow pin, or click the map, to move it</b></div>
      <div className={`ops-verdict ${p.verdict.startsWith('Good') ? 'good' : 'warn'}`}>{p.verdict}</div>
      <div className="ops-live-grid">
        <span><b>~{p.ordersPerDay}</b> orders/day it would take over</span><span><b>{inr(p.revenuePerDay)}</b> revenue/day</span>
        <span><b>{p.kmNow ?? '–'} → {p.kmNew ?? '–'} km</b> ride for them</span><span><b>{p.lostOrdersPerDay}</b> lost orders/day rescued</span>
        <span>now <b>{p.avgDeliveryMinNow ?? '–'} min</b> avg, <b>{p.latePctNow ?? '–'}%</b> late</span><span>starts with <b>{p.startingRiders}</b> riders, <b>{p.capacityPerHour}</b>/h</span>
      </div>
      {p.takenFrom.length > 0 && <div className="ops-muted">Takes orders from: {p.takenFrom.map((x) => `${x.code} (${Math.round(x.orders / 7)}/day)`).join(', ')}</div>}
      <div className="ops-muted">Nearest stores: {p.nearestStores.map((x) => `${x.code} ${x.km} km${x.active ? '' : ' (off)'}`).join(' · ')}</div>
      <div className="ops-row">
        <button disabled={busy} onClick={() => run(async () => {
          setBusy(true);
          try { const r = await ops.openStore({ localityId: p.localityId, lat: p.lat, lng: p.lng, riders: p.startingRiders }); onClose(r); } finally { setBusy(false); }
        })}>{busy ? 'Opening…' : '✅ Open store here'}</button>
        <button className="ghost" onClick={() => onClose(false)}>Cancel</button>
      </div>
    </div>
  );
}

/**
 * Runs one fix (only the one clicked). Returns the API result, or false if the admin cancelled the confirmation.
 * Opening a store and hiring cost money in real life, so they ask first.
 */
async function doAction(action, target, place) {
  const t = target && typeof target === 'object' ? target : { id: target };
  switch (action) {
    case 'MOVE_RIDERS': return ops.moveBetween(t.fromStoreId, t.toStoreId, t.count);
    case 'MOVE_ONE': return ops.moveRider(t.riderId, t.toStoreId);
    case 'HIRE_RIDERS':
      if (!window.confirm(`Hire ${t.count} new rider${t.count > 1 ? 's' : ''} for this store?`)) return false;
      return ops.hire(t.storeId, t.count);
    case 'SET_CAPACITY': return ops.setCapacity(t.storeId, t.capacity);
    case 'RESTOCK': return ops.restock(t.storeId ?? t.id);
    case 'OPEN_STORE': // show where it would go first; resolves with the new store, or false if cancelled
      return askOpenStore ? askOpenStore({ localityId: t.localityId ?? t.id ?? null, lat: t.lat ?? null, lng: t.lng ?? null }, place) : false;
    case 'ACTIVATE_STORE': return ops.setStoreActive(t.id, true);
    case 'PRIORITY_SLA': return ops.priority('SLA');
    case 'APPLY_STAFFING': return ops.applyStaffing();
    case 'ADD_RIDERS': return ops.riders(5);
    case 'FILL_GAPS': return ops.fillGaps();
    case 'TRIM_RIDERS': return ops.trimRiders();
    case 'REMOVE_RIDERS': return ops.riders(-(t.count ?? t.id));
    default: return null; // SHOW_AREA: just fly there
  }
}

/** A fix button: runs only its own action, then shows ✓ until the list refreshes. */
function FixButton({ action, target, place, lat, lng, run, onFocus, onDone }) {
  const [done, setDone] = useState(false);
  if (!action) return null;
  return (
    <button className="small" disabled={done} onClick={(e) => {
      e.stopPropagation();
      run(async () => {
        const r = await doAction(action, target, place);
        if (r === false) return;
        if (r?.lat != null) onFocus?.([r.lat, r.lng]); else if (lat != null) onFocus?.([lat, lng]);
        if (action !== 'SHOW_AREA') setDone(true);
        onDone?.(r);
      });
    }}>{done ? '✓ Done' : ACTION_LABEL[action]}</button>
  );
}

/** Beginner view: what's on screen, what the numbers mean, and what to do next. */
function StartTab({ snap, run, onFocus }) {
  const [recs, setRecs] = useState(null);
  const load = () => ops.recommendations().then(setRecs).catch(() => {});
  useEffect(() => { load(); const t = setInterval(load, 20000); return () => clearInterval(t); }, []);
  const k = snap?.kpis;
  return (
    <>
      <h3>💡 Recommendations <span className="ops-muted">updated every 20 s</span></h3>
      {!recs && <p className="ops-muted">Loading…</p>}
      <ul className="ops-recs">
        {recs?.map((r, i) => (
          <li key={i} className={`p${r.priority}`}>
            <div><b>{r.title}</b><div className="ops-muted">{r.why}</div></div>
            <FixButton action={r.action} target={r.target} place={r.title.replace(/^Open a dark store in /, '')} lat={r.lat} lng={r.lng}
              run={run} onFocus={onFocus} onDone={() => setTimeout(load, 1500)} />
          </li>
        ))}
      </ul>
      <p className="ops-hint">Red = do it now · amber = plan for it this week · grey = keep an eye on it.</p>

      <h3>🗺️ What you are looking at</h3>
      <p>A simulated quick-commerce company (like a 10-minute grocery app) running across <b>all of Mumbai</b>, from Colaba to Dahisar and Mulund.
        Customers order, a nearby dark store packs the order, and a rider delivers it, riding on real Mumbai roads.</p>
      <ul className="ops-glossary">
        <li><span className="ico">🏬</span><span><b>Dark store</b>: a small warehouse that only serves delivery orders. The number on it = orders waiting for a rider.</span></li>
        <li><span className="ico">🛵</span><span><b>Rider</b>: ring colour shows what they are doing: <i className="dot g" />free, <i className="dot o" />going to the store, <i className="dot b" />delivering, <i className="dot l" />riding back.</span></li>
        <li><span className="ico">📦</span><span><b>Order</b>: border shows if it is on time: <i className="dot g" />fine, <i className="dot o" />at risk, <i className="dot r" />late.</span></li>
        <li><span className="ico">▭</span><span><b>Areas</b>: Mumbai localities (Borivali East, Bandra West…), outlined on the map. Zoom in to read the names, or search one at the top for a live view of it.</span></li>
        <li><span className="ico">◌</span><span><b>Dashed circles</b>: the area each store can reach in about 12 minutes.</span></li>
      </ul>

      <h3>📖 What the numbers mean</h3>
      <dl className="ops-terms">
        <dt>On time (SLA met) {k?.slaPct != null && <span className="ops-muted">now {k.slaPct}%</span>}</dt>
        <dd>SLA (service-level agreement) is the delivery time we <b>promise</b> each customer: 20 minutes for small baskets, 30 for big ones (+10 in monsoon).
          "SLA met" is the share of today's deliveries that arrived <b>before</b> that promise. Above 90% is good.</dd>
        <dt>How busy stores are (store utilisation) {k && <span className="ops-muted">now {k.warehouseUtilPct}%</span>}</dt>
        <dd>Orders the dark stores got in the last hour ÷ the orders they can pack in an hour (their <b>hourly capacity</b>, e.g. 70).
          Low (10–20%) means stores are mostly waiting for orders; near 100% means they are full and orders will start to be late.</dd>
        <dt>Average order value (AOV)</dt><dd>Revenue ÷ number of orders: what a typical basket is worth.</dd>
        <dt>Riders free now</dt><dd>Riders with no order. They wait at their own store, because every delivery starts with picking up there.
          When there are many more riders than orders, most of them are free. That is why the Start here tab may suggest taking some off shift.</dd>
      </dl>

      <h3>▶ Try this</h3>
      <ol className="ops-steps">
        <li>Watch a 🛵 leave a 🏬, ride along the roads to a 📦, and come back.</li>
        <li>Open <b>Simulation</b> and pick <b>Monsoon</b>: roads slow down, and orders turn amber or red.</li>
        <li>Come back here and press the buttons on the recommendations to fix it.</li>
        <li>Search an area (e.g. "Borivali East") at the top to see what is happening there right now.</li>
      </ol>
    </>
  );
}

function Kpi({ label, value, sub, tone, help }) {
  return (
    <div className={`ops-kpi ${tone ?? ''}`} title={help} tabIndex={help ? 0 : undefined}>
      <div className="v">{value ?? '–'}</div>
      <div className="l">{label}</div>
      {sub && <div className="s">{sub}</div>}
    </div>
  );
}

function LocalitySearch({ onPick }) {
  const [text, setText] = useState('');
  const [hits, setHits] = useState([]);
  useEffect(() => {
    if (!text.trim()) { setHits([]); return; }
    const t = setTimeout(() => ops.localities(text).then(setHits).catch(() => setHits([])), 200);
    return () => clearTimeout(t);
  }, [text]);
  return (
    <div className="ops-search">
      <input placeholder="Search locality or pincode…" value={text} onChange={(e) => setText(e.target.value)} aria-label="Search locality or pincode" />
      {hits.length > 0 && (
        <ul>
          {hits.map((h) => (
            <li key={h.id}><button onClick={() => { onPick(h); setText(h.name); setHits([]); }}>{h.name} <span className="ops-muted">{h.pincode}</span></button></li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Point 7: real-time panel for the searched locality (refreshes with the simulation clock). */
function LocalityLive({ id, simTime, onClose }) {
  const [d, setD] = useState(null);
  const minute = simTime?.slice(0, 16);
  useEffect(() => { ops.localityLive(id).then(setD).catch(() => setD(null)); }, [id, minute]);
  if (!d) return null;
  const ev = d.demandEventsLastHour ?? {};
  return (
    <div className="ops-live" role="region" aria-label={`Live: ${d.name}`}>
      <div className="ops-row"><b>📍 {d.name}</b><span className="ops-muted">{d.pincode} · pop. ~{(d.population / 1000).toFixed(0)}k</span>
        <button className="small" onClick={onClose} aria-label="Close">×</button></div>
      <div className="ops-live-grid">
        <span><b>{d.openOrders}</b> open orders</span><span><b>{d.waitingForRider}</b> waiting for rider</span>
        <span className={d.atRisk ? 'bad' : ''}><b>{d.atRisk}</b> at risk</span><span><b>{d.ridersInside}</b> riders inside</span>
        <span><b>{d.freeRidersWithin3km}</b> free riders ≤3 km</span><span><b>{d.congestionPct}%</b> congestion</span>
        <span><b>{d.lastHour.orders}</b> orders last hour</span><span><b>{inr(d.lastHour.revenue)}</b> revenue last hour</span>
      </div>
      <div className="ops-muted">Served by {d.servedBy.length ? d.servedBy.join(', ') : <b className="bad">no dark store (unserviceable)</b>}
        {d.lastHour.avg_delivery_min != null && ` · avg delivery ${d.lastHour.avg_delivery_min} min`}</div>
      <div className="ops-muted">Last hour: {ev.SEARCH ?? 0} searches · {ev.UNSERVICEABLE ?? 0} unserviceable · {ev.OUT_OF_STOCK ?? 0} out of stock · {ev.ABANDONED_CART ?? 0} abandoned
        {d.topSearches.length > 0 && ` · top: ${d.topSearches.map((s) => s.query).join(', ')}`}</div>
      {d.disruptions.length > 0 && <div className="bad">⚠ {d.disruptions.join(' · ')}</div>}
    </div>
  );
}

// ------------------------------------------------------------------ Live: alerts + priority queue

function LiveTab({ snap, alerts, setAlerts, run }) {
  const [assignTo, setAssignTo] = useState({});
  const [sev, setSev] = useState('ALL');
  const [hideAcked, setHideAcked] = useState(true);
  const shown = alerts.filter((a) => (sev === 'ALL' || a.severity === sev) && !(hideAcked && a.acknowledged));
  const queue = useMemo(() => (snap?.orders ?? []).filter((o) => o.queueKey != null).sort((a, b) => a.queueKey - b.queueKey), [snap]);
  const freeRiders = (snap?.riders ?? []).filter((r) => r.load === 0 && (r.status === 'IDLE' || r.status === 'RETURNING'));
  const ack = (id) => run(async () => {
    const a = await ops.ack(id);
    setAlerts((all) => all.map((x) => (x.id === id ? a : x)));
  });
  return (
    <>
      <h3>Dispatch queue <span className="ops-muted">Fibonacci heap · {queue.length}</span></h3>
      <div className="ops-row" role="group" aria-label="Delivery priority">
        <span className="ops-muted">Priority</span>
        {[['SLA', 'Promised deadline first'], ['FIFO', 'Order received first']].map(([v, l]) => (
          <button key={v} className={(snap?.priority ?? 'SLA') === v ? 'on' : ''} onClick={() => run(() => ops.priority(v))}>{l}</button>
        ))}
      </div>
      <p className="ops-hint">{snap?.priority === 'FIFO'
        ? 'Key = time the order was received (then arrival sequence) − escalation: first come, first served.'
        : 'Key = SLA deadline − aging − escalation (FIFO on ties).'} Escalate = decrease-key; cancel = heap delete.
        Orders wait here while being packed and are handed to a rider just in time (packing nearly done or deadline close).</p>
      {queue.length === 0 && <p className="ops-muted">No orders waiting for a rider right now.</p>}
      <table className="ops-table">
        <tbody>
          {queue.slice(0, 25).map((o) => (
            <tr key={o.id}>
              <td><b>#{o.id}</b><div className="ops-muted">{o.status.toLowerCase()} · {o.items} items</div></td>
              <td className={o.minutesLeft < 5 ? 'bad' : ''}>{o.minutesLeft} min</td>
              <td className="ops-actions">
                <button onClick={() => run(() => ops.escalate(o.id, 10))} title="Move 10 minutes up the queue">↑ Escalate</button>
                <select value={assignTo[o.id] ?? ''} onChange={(e) => setAssignTo({ ...assignTo, [o.id]: e.target.value })} aria-label="Rider">
                  <option value="">rider…</option>
                  {freeRiders.map((r) => <option key={r.id} value={r.id}>{r.code}</option>)}
                </select>
                <button disabled={!assignTo[o.id]} onClick={() => run(() => ops.assign(o.id, Number(assignTo[o.id])))}>Assign</button>
                <button className="danger" onClick={() => run(() => ops.cancelOrder(o.id))}>Cancel</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h3>Live alerts <span className="ops-muted">{alerts.filter((a) => !a.acknowledged).length} open</span></h3>
      <div className="ops-row wrap">
        {['ALL', 'CRITICAL', 'WARNING', 'INFO'].map((s) => <button key={s} className={sev === s ? 'on' : ''} onClick={() => setSev(s)}>{s.toLowerCase()}</button>)}
        <label className="ops-inline"><input type="checkbox" checked={hideAcked} onChange={(e) => setHideAcked(e.target.checked)} /> hide acknowledged</label>
        <button onClick={() => run(async () => { await ops.ackAll(); setAlerts((all) => all.map((x) => ({ ...x, acknowledged: true }))); })}>Ack all</button>
      </div>
      <ul className="ops-alerts">
        {shown.slice(0, 60).map((a) => (
          <li key={a.id} className={`${a.severity.toLowerCase()} ${a.acknowledged ? 'acked' : ''}`}>
            <span className="t">{a.at?.slice(11, 16)}</span>
            <span className="m"><b>{a.type.replace('_', ' ')}</b> {a.message}</span>
            {!a.acknowledged && <button onClick={() => ack(a.id)}>Ack</button>}
          </li>
        ))}
        {shown.length === 0 && <li className="ops-muted">No alerts to show.</li>}
      </ul>
    </>
  );
}

// ------------------------------------------------------------------ Analytics

function AnalyticsTab({ run, boundaries }) {
  const [hours, setHours] = useState(168);
  const [aov, setAov] = useState(null);
  const [revenue, setRevenue] = useState([]);
  const [fc, setFc] = useState(null);
  const [ww, setWw] = useState(null);
  const [cmpIds, setCmpIds] = useState([]);
  const [cmp, setCmp] = useState(null);
  const [customer, setCustomer] = useState(null);

  useEffect(() => {
    run(async () => {
      setAov(await ops.aov(hours));
      setRevenue((await ops.revenue(Math.min(hours, 72))).map((r) => ({ ...r, label: r.hour.slice(11, 13) + 'h' })));
    });
  }, [hours]);
  useEffect(() => {
    run(async () => { setFc(await ops.forecast()); setWw(await ops.weekdayWeekend(21)); });
  }, []);

  // Areas split into East and West (Borivali, Kurla, ...): one-click comparison.
  const pairs = [...new Set(boundaries.filter((b) => / (East|West)$/.test(b.name)).map((b) => b.name.replace(/ (East|West)$/, '')))]
    .filter((a) => boundaries.filter((b) => b.name.startsWith(a + ' ')).length === 2).map((a) => [a]);
  return (
    <>
      <div className="ops-row">
        <span className="ops-muted">Window</span>
        {Object.entries(WINDOWS).map(([l, h]) => <button key={h} className={hours === h ? 'on' : ''} onClick={() => setHours(h)}>{l}</button>)}
      </div>

      <h3>Revenue forecast <span className="ops-muted">next hour</span></h3>
      {fc && (
        <div className="ops-card">
          <div className="ops-big">{inr(fc.forecastNextHour)} <span className="ops-muted">from {fc.nextHourStart.slice(11, 16)}</span></div>
          <p className="ops-hint">0.7 × same-hour baseline ({inr(fc.components.baselineNextHour)}) × 3-hour trend {fc.components.trend} + 0.3 × 30-min velocity ({inr(fc.components.revenueLast30Min * 2)}/h).
            Back-test over {fc.backtestHours} h: <b>{fc.backtestWapePct ?? '–'}% WAPE</b> (total error ÷ total revenue).</p>
          <LineChart data={fc.series.map((s) => ({ ...s, label: s.hour.slice(11, 13) + 'h' }))} x="label"
            lines={[{ key: 'actual', color: '#38bdf8' }, { key: 'predicted', color: '#f59e0b', dashed: true }]} />
          <div className="ops-legend"><span><i style={{ background: '#38bdf8' }} />actual</span><span><i style={{ background: '#f59e0b' }} />model (back-test)</span></div>
        </div>
      )}

      <h3>Revenue by hour</h3>
      <BarChart data={revenue} x="label" y="revenue" format={inr} />

      <h3>Average order value</h3>
      {aov && (
        <>
          <div className="ops-card ops-big">{inr(aov.overall.aov)} <span className="ops-muted">over {aov.overall.orders} orders · {inr(aov.overall.revenue)}</span></div>
          <table className="ops-table">
            <thead><tr><th>Locality</th><th>Pincode</th><th>Orders</th><th>AOV</th></tr></thead>
            <tbody>{aov.byLocality.map((r) => <tr key={r.locality}><td>{r.locality}</td><td>{r.pincode}</td><td>{r.orders}</td><td>{inr(r.aov)}</td></tr>)}</tbody>
          </table>
          <h4>AOV by how much customers buy <span className="ops-muted">spend in this window</span></h4>
          <table className="ops-table">
            <thead><tr><th>Customer tier</th><th>Customers</th><th>Orders each</th><th>AOV</th><th>Revenue</th></tr></thead>
            <tbody>{aov.spendTiers.map((t) => <tr key={t.tier}><td>{t.tier.slice(2)}</td><td>{t.customers}</td><td>{t.orders_per_customer}</td><td>{inr(t.aov)}</td><td>{inr(t.revenue)}</td></tr>)}</tbody>
          </table>
          <h4>Top customers <span className="ops-muted">click for spending history</span></h4>
          <table className="ops-table">
            <tbody>{aov.topCustomers.slice(0, 8).map((c) => (
              <tr key={c.id} className="click" onClick={() => run(async () => setCustomer(await ops.customer(c.id)))}>
                <td>{c.name}<div className="ops-muted">{c.locality}</div></td><td>{c.orders} orders</td><td>{inr(c.spend)}</td><td>AOV {inr(c.aov)}</td>
              </tr>))}</tbody>
          </table>
          {customer && (
            <div className="ops-card">
              <div className="ops-row"><b>{customer.name}</b><span className="ops-muted">{customer.locality} · {customer.pincode}</span><button onClick={() => setCustomer(null)}>×</button></div>
              <p>Lifetime spend {inr(customer.summary.lifetime_spend)} · {customer.summary.orders} orders · AOV {inr(customer.summary.aov)} · {customer.summary.cancelled} cancelled</p>
              <BarChart data={[...customer.orders].reverse().map((o) => ({ label: o.placed_at.slice(5, 10), total: o.total }))} x="label" y="total" format={inr} height={80} color="#a78bfa" />
            </div>
          )}
        </>
      )}

      <h3>Weekday vs weekend <span className="ops-muted">last 21 days · estimate = 60% last 3 such days + 40% average</span></h3>
      {ww && (
        <table className="ops-table">
          <thead><tr><th /><th>Weekday</th><th>Weekend</th></tr></thead>
          <tbody>
            <tr className="hl"><td><b>Revenue estimate</b></td>{['WEEKDAY', 'WEEKEND'].map((t) => {
              const row = ww.find((r) => r.day_type === t);
              return <td key={t}>{row ? <><b>{inr(row.next_day_revenue_estimate)}</b><div className="ops-muted">{row.next_date}</div></> : '–'}</td>;
            })}</tr>
            {[['Orders / day', 'orders_per_day'], ['Revenue / day', 'revenue_per_day', inr], ['AOV', 'aov', inr], ['Peak hour', 'peak_hour', (v) => `${v}:00`],
              ['Peak orders / hour', 'peak_orders_per_hour'], ['Riders needed at peak', 'riders_needed_at_peak'], ['Avg delivery (min)', 'avg_delivery_min'],
              ['SLA met %', 'sla_pct'], ['Cancel %', 'cancel_pct']].map(([label, key, f]) => (
              <tr key={key}><td>{label}</td>{['WEEKDAY', 'WEEKEND'].map((t) => {
                const row = ww.find((r) => r.day_type === t);
                return <td key={t}>{row ? (f ? f(row[key]) : row[key]) : '–'}</td>;
              })}</tr>
            ))}
            <tr><td>Top items / day</td>{['WEEKDAY', 'WEEKEND'].map((t) => (
              <td key={t}>{ww.find((r) => r.day_type === t)?.top_products_per_day.slice(0, 3).map((p) => `${p.name} ${p.units_per_day}`).join(', ')}</td>))}</tr>
            <tr><td>Unfulfilled / day</td>{['WEEKDAY', 'WEEKEND'].map((t) => (
              <td key={t}>{Object.entries(ww.find((r) => r.day_type === t)?.unfulfilled_per_day ?? {}).map(([kk, v]) => `${kk.toLowerCase().replace('_', ' ')} ${v}`).join(', ')}</td>))}</tr>
          </tbody>
        </table>
      )}

      <h3>Locality comparison <span className="ops-muted">search a locality above the map for its live view</span></h3>
      <div className="ops-row wrap">
        {pairs.map(([area]) => (
          <button key={area} onClick={() => {
            const ids = boundaries.filter((b) => b.name.startsWith(area)).map((b) => b.id);
            setCmpIds(ids);
            run(async () => setCmp(await ops.compareLocalities(ids, hours)));
          }}>{area} E/W</button>
        ))}
      </div>
      <div className="ops-row wrap" role="group" aria-label="Pick localities to compare">
        {boundaries.map((b) => (
          <label key={b.id} className={`chip pick ${cmpIds.includes(b.id) ? 'on' : ''}`}>
            <input type="checkbox" checked={cmpIds.includes(b.id)}
              onChange={(e) => setCmpIds(e.target.checked ? [...cmpIds, b.id] : cmpIds.filter((x) => x !== b.id))} />
            {b.name}
          </label>
        ))}
        <button disabled={!cmpIds.length} onClick={() => run(async () => setCmp(await ops.compareLocalities(cmpIds, hours)))}>Compare {cmpIds.length || ''}</button>
      </div>
      {cmp && (
        <table className="ops-table">
          <thead><tr><th>Locality</th><th>Population</th><th>Orders</th><th>per 1k people</th><th>AOV</th><th>Avg min</th><th>SLA %</th><th>Cancel %</th><th>Unmet</th></tr></thead>
          <tbody>{cmp.map((r) => (
            <tr key={r.id}><td>{r.name}<div className="ops-muted">{r.pincode}</div></td><td>{r.population ? `~${Math.round(r.population / 1000)}k` : '–'}</td>
              <td>{r.orders}</td><td>{r.orders_per_1k_residents ?? '–'}<div className="ops-muted">{r.buyer_penetration_pct != null ? `${r.buyer_penetration_pct}% buy` : ''}</div></td>
              <td>{inr(r.aov)}</td><td>{r.avg_delivery_min ?? '–'}</td>
              <td>{r.sla_pct ?? '–'}</td><td>{r.cancel_pct ?? '–'}</td>
              <td>{Object.entries(r.demand_events).filter(([kk]) => kk !== 'SEARCH').map(([kk, v]) => `${kk.toLowerCase().replace('_', ' ')} ${v}`).join(', ') || '0'}</td></tr>))}</tbody>
        </table>
      )}
    </>
  );
}

// ------------------------------------------------------------------ Planning: stores, set cover, utilisation, allocation

/** Every dark store: is it overloaded, why, and what to do about it. */
function StoreHealth({ run, onFocus, onChanged }) {
  const [rows, setRows] = useState(null);
  const [showAll, setShowAll] = useState(false);
  const load = () => ops.storeHealth(7).then(setRows).catch(() => {});
  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t); }, []);
  const count = (s) => rows?.filter((r) => r.status === s).length ?? 0;
  const shown = rows?.filter((r) => showAll || r.status !== 'OK') ?? [];
  return (
    <>
      <h3>Store health <span className="ops-muted">last 7 days + right now · refreshes every 30 s</span></h3>
      {rows && (
        <div className="ops-row wrap">
          <span className="chip bad">{count('OVERLOADED')} overloaded</span><span className="chip warn">{count('BUSY')} busy</span>
          <span className="chip good">{count('OK')} healthy</span><span className="chip">{count('UNDERUSED')} underused</span>
          <button className="small" onClick={() => setShowAll(!showAll)}>{showAll ? 'Only problems' : 'Show all stores'}</button>
        </div>
      )}
      <p className="ops-hint">Overloaded = packing at 90%+ of capacity at the busiest hour, 15%+ late, or orders piling up without riders.
        Busy = 70%+ capacity, 10%+ late, short of riders, or many items nearly sold out.</p>
      {rows && shown.length === 0 && <p className="ops-muted">✅ No store is overloaded or busy. Try Simulation → Peak hour to stress them.</p>}
      <div className="ops-health">
        {shown.map((s) => (
          <div key={s.id} className={`ops-hcard ${s.status.toLowerCase()}`}>
            <div className="ops-row">
              <button className="linklike" onClick={() => onFocus([s.lat, s.lng])}><b>🏬 {s.code}</b></button>
              <span className="ops-muted">{s.area}{s.active ? '' : ' · switched off'}</span>
              <span className={`chip ${s.status === 'OVERLOADED' ? 'bad' : s.status === 'BUSY' ? 'warn' : s.status === 'OK' ? 'good' : ''}`}>{s.status.toLowerCase()}</span>
            </div>
            <div className="ops-meter" title={`Busiest hour: ${s.peakOrdersPerHour} of ${s.capacityPerHour} orders/h`}>
              <i style={{ width: `${Math.min(100, s.peakUtilPct)}%` }} className={s.peakUtilPct >= 90 ? 'bad' : s.peakUtilPct >= 70 ? 'warn' : ''} />
            </div>
            <div className="ops-hstats">
              <span><b>{s.peakUtilPct}%</b> capacity at peak</span><span><b>{s.nowUtilPct}%</b> last hour</span>
              <span className={s.latePct >= 10 ? 'bad' : ''}><b>{s.latePct}%</b> late</span>
              <span className={s.riders < s.ridersNeededAtPeak ? 'bad' : ''}><b>{s.riders}</b>/{s.ridersNeededAtPeak} riders</span>
              <span><b>{s.waitingNow}</b> waiting</span><span><b>{s.avgDeliveryMin ?? '–'}</b> min avg</span>
            </div>
            <div className="ops-why"><b>Why:</b><ul>{s.reasons.map((x, i) => <li key={i}>{x}</li>)}</ul></div>
            {s.fixes.length > 0 && (
              <div className="ops-fix"><b>How to fix:</b>
                <ul>{s.fixes.map((f, i) => (
                  <li key={i}><span>{f.text}</span>
                    <FixButton action={f.action} target={f.target} place={f.text.match(/second store in ([^:]+)/)?.[1]} lat={f.lat} lng={f.lng}
                      run={run} onFocus={onFocus} onDone={() => { onChanged(); setTimeout(load, 1500); }} /></li>
                ))}</ul>
              </div>
            )}
          </div>
        ))}
      </div>
    </>
  );
}

function PlanningTab({ snap, run, setSites, setCoverage, onFocus }) {
  const [areaFilter, setAreaFilter] = useState('action');
  const [plan, setPlan] = useState(null);
  const [params, setParams] = useState({ budget: 12, target: 95, capacity: 80 });
  const [util, setUtil] = useState(null);
  const [cmp, setCmp] = useState(null);
  const [bench, setBench] = useState(null);
  const [busy, setBusy] = useState(false);

  const [needed, setNeeded] = useState(null);
  const [staff, setStaff] = useState(null);
  const refreshUtil = () => run(async () => setUtil(await ops.utilization()));
  const refreshStaff = () => run(async () => setStaff(await ops.staffing()));
  useEffect(() => { refreshUtil(); refreshStaff(); run(async () => setNeeded(await ops.warehousesNeeded(7))); }, []);

  return (
    <>
      <h3>Dark stores <span className="ops-muted">activate / deactivate</span></h3>
      <table className="ops-table">
        <tbody>{snap?.stores.map((s) => (
          <tr key={s.id}>
            <td><b>{s.code}</b><div className="ops-muted">{s.riders} riders · {s.lowStock} low-stock SKUs</div></td>
            <td>{s.utilPct}%<div className="ops-muted">of capacity</div></td>
            <td>{s.waiting} waiting</td>
            <td><button className={s.active ? 'danger' : ''} onClick={() => run(async () => { await ops.setStoreActive(s.id, !s.active); setTimeout(() => ops.coverage().then(setCoverage), 1500); })}>
              {s.active ? 'Deactivate' : 'Activate'}</button></td>
          </tr>))}</tbody>
      </table>

      <StoreHealth run={run} onFocus={onFocus} onChanged={() => { refreshStaff(); refreshUtil(); setTimeout(() => ops.coverage().then(setCoverage), 1500); }} />

      <h3>Dark stores needed per area <span className="ops-muted">from the last 7 days of shopping</span></h3>
      <p className="ops-hint">Needed = busiest hour of an average day (orders + orders lost because no store delivers there) × 1.15 headroom ÷ 70 orders/h one store can pack.
        Any area with real demand (3+ orders at its busiest hour) gets one of its own.</p>
      <div className="ops-row">
        {[['action', 'Needs action'], ['all', 'All areas']].map(([v, l]) => <button key={v} className={areaFilter === v ? 'on' : ''} onClick={() => setAreaFilter(v)}>{l}</button>)}
        {needed && <span className="ops-muted">{needed.filter((r) => r.change > 0).length} areas need a store · {needed.filter((r) => r.advice === 'Over-served').length} over-served</span>}
      </div>
      {needed && (
        <table className="ops-table">
          <thead><tr><th>Area and why</th><th>Busiest h</th><th>Stores</th><th>Need</th><th /></tr></thead>
          <tbody>{needed.filter((r) => areaFilter === 'all' || r.advice !== 'OK').map((r) => (
            <tr key={r.id} className="click" title="Show on map" onClick={() => r.lat != null && onFocus([r.lat, r.lng])}>
              <td><b>{r.name}</b> <span className="ops-muted">{r.population ? `pop. ~${Math.round(r.population / 1000)}k` : ''} · {r.buyers} buyers · AOV {inr(r.aov)}</span>
                <div className="ops-why">{r.reason}</div></td>
              <td>{r.peak_orders_per_hour}/h{r.lost_per_hour_at_peak > 0 && <div className="bad">{r.lost_per_hour_at_peak} lost</div>}</td>
              <td>{r.stores}</td><td><b>{r.stores_needed}</b></td>
              <td><span className={`chip ${r.change > 0 ? 'bad' : r.advice === 'OK' ? 'good' : ''}`}>{r.advice}</span>
                {r.change > 0 && <div><FixButton action="OPEN_STORE" target={{ localityId: r.id }} place={r.name} run={run} onFocus={onFocus}
                  onDone={() => { setTimeout(() => ops.coverage().then(setCoverage), 1500); setTimeout(() => ops.warehousesNeeded(7).then(setNeeded), 1500); }} /></div>}</td></tr>))}
            {needed.filter((r) => areaFilter === 'all' || r.advice !== 'OK').length === 0 && <tr><td colSpan={5} className="ops-muted">Every area has the stores it needs.</td></tr>}
          </tbody>
        </table>
      )}

      <h3>Riders by demand <button className="small" onClick={refreshStaff}>↻</button>
        {staff?.moves.length > 0 && <button className="small" onClick={() => run(async () => { await ops.applyStaffing(); refreshStaff(); refreshUtil(); })}>Apply all {staff.moves.length}</button>}</h3>
      <p className="ops-hint">Riders needed = orders in the last hour ÷ 2.2 deliveries per rider-hour + 1 spare. Free riders move from quiet stores to the busiest ones first.</p>
      {staff && (
        <>
          {staff.moves.map((m) => <div key={m.riderId} className="ops-rec">Move <b>{m.rider}</b> {m.from} → {m.to} <span className="ops-muted">{m.reason}</span>
            <FixButton action="MOVE_ONE" target={{ riderId: m.riderId, toStoreId: m.toStoreId }} run={run} onDone={() => setTimeout(refreshStaff, 800)} /></div>)}
          <table className="ops-table">
            <thead><tr><th>Store</th><th>Orders last h</th><th>Riders</th><th>Needed</th></tr></thead>
            <tbody>{staff.stores.slice(0, 12).map((s) => (
              <tr key={s.storeId}><td>{s.code}<div className="ops-muted">{s.locality}</div></td><td>{s.ordersLastHour}</td><td>{s.riders}</td>
                <td className={s.gap > 0 ? 'bad' : ''}>{s.ridersNeeded}</td></tr>))}</tbody>
          </table>
        </>
      )}

      <h3>Warehouse optimisation <span className="ops-muted">greedy set cover</span></h3>
      <div className="ops-row wrap">
        <label>Ride budget (min)<input type="number" min="5" max="30" value={params.budget} onChange={(e) => setParams({ ...params, budget: Number(e.target.value) })} /></label>
        <label>Coverage target %<input type="number" min="50" max="100" value={params.target} onChange={(e) => setParams({ ...params, target: Number(e.target.value) })} /></label>
        <label>Store capacity /h<input type="number" min="20" max="500" value={params.capacity} onChange={(e) => setParams({ ...params, capacity: Number(e.target.value) })} /></label>
        <button disabled={busy} onClick={() => run(async () => { setBusy(true); try { const p = await ops.plan(params.budget, params.target, params.capacity); setPlan(p); setSites(p.sites); } finally { setBusy(false); } })}>
          {busy ? 'Solving…' : 'Recommend stores'}</button>
        {plan && <button onClick={() => { setPlan(null); setSites(null); }}>Clear</button>}
      </div>
      {plan && (
        <div className="ops-card">
          <p><b>{plan.recommendedStores} stores</b> reach {plan.sites.at(-1)?.cumulativeCoveragePct ?? 0}% of {plan.peakOrdersPerHour} peak orders/h
            (today: {plan.currentStores} stores cover {plan.currentCoveragePct}%). ★ markers on the map.</p>
          <p className="ops-hint">{plan.method}. Pick the sites you want: each button opens only that one store, at the ★ spot.</p>
          <table className="ops-table"><tbody>{plan.sites.map((s) => (
            <tr key={s.rank}><td>#{s.rank}</td><td>{s.locality}</td><td>+{s.coveredPeakPerHour}/h</td><td>{s.cumulativeCoveragePct}%</td>
              <td><FixButton action="OPEN_STORE" target={{ lat: s.lat, lng: s.lng }} place={`${s.locality} (site #${s.rank})`} run={run} onFocus={onFocus}
                onDone={() => setTimeout(() => ops.coverage().then(setCoverage), 1500)} /></td></tr>))}</tbody></table>
        </div>
      )}

      <h3>Rider utilisation <button className="small" onClick={refreshUtil}>↻</button></h3>
      {util && (
        <>
          <div className="ops-row"><span className="chip good">{util.summary.active} active</span><span className="chip">{util.summary.idle} idle</span><span className="chip bad">{util.summary.overloaded} overloaded</span></div>
          {util.recommendations.length === 0 && <p className="ops-muted">No rebalancing needed right now.</p>}
          {util.recommendations.map((r) => (
            <div key={r.riderId} className="ops-rec">Move <b>{r.rider}</b> {r.from} → {r.to} <span className="ops-muted">{r.reason}</span>
              <button onClick={() => run(async () => { await ops.moveRider(r.riderId, r.toStoreId); refreshUtil(); })}>Apply</button></div>
          ))}
          <table className="ops-table">
            <thead><tr><th>Rider</th><th>Store</th><th>Busy %</th><th>Done</th><th>Class</th></tr></thead>
            <tbody>{util.riders.slice(0, 40).map((r) => (
              <tr key={r.id}><td>{r.code}<div className="ops-muted">{r.status.toLowerCase()}</div></td><td>{r.homeStore}</td><td>{r.utilPct}</td><td>{r.deliveriesToday}</td>
                <td><span className={`chip ${r.class === 'OVERLOADED' ? 'bad' : r.class === 'ACTIVE' ? 'good' : ''}`}>{r.class.toLowerCase()}</span></td></tr>))}</tbody>
          </table>
        </>
      )}

      <h3>Allocation algorithms <span className="ops-muted">re-plan of every order not yet picked up</span></h3>
      <button onClick={() => run(async () => setCmp(await ops.compareAllocation()))}>Compare now</button>
      {cmp && (
        <table className="ops-table">
          <thead><tr><th>Algorithm</th><th>Assigned</th><th>Total ETA</th><th>Late min</th><th>Late orders</th><th>µs</th></tr></thead>
          <tbody>{cmp.map((c) => <tr key={c.algorithm}><td>{c.algorithm.replace('_', ' ')}</td><td>{c.assigned}</td><td>{c.totalEtaMin}</td><td>{c.totalLateMin}</td><td>{c.predictedLateOrders}</td><td>{c.micros}</td></tr>)}</tbody>
        </table>
      )}
      <p className="ops-hint">Edmonds–Karp and Dinic maximise how many orders get a rider but ignore ETA, SLA and workload; min-cost max-flow assigns the same number at the lowest total cost.</p>

      <h3>Benchmarks</h3>
      <button disabled={busy} onClick={() => run(async () => { setBusy(true); try { setBench(await ops.benchmark()); } finally { setBusy(false); } })}>{busy ? 'Running…' : 'Run benchmarks'}</button>
      {bench && (
        <>
          <h4>Allocation (synthetic, fixed seed)</h4>
          <table className="ops-table">
            <thead><tr><th>n</th><th>Algorithm</th><th>ms</th><th>Total ETA</th><th>Late orders</th></tr></thead>
            <tbody>{bench.allocation.map((r, i) => <tr key={i}><td>{r.n}</td><td>{r.algorithm.replace('_', ' ')}</td><td>{r.millis}</td><td>{r.totalEtaMin}</td><td>{r.lateOrders}</td></tr>)}</tbody>
          </table>
          <h4>Priority queue: escalation storm</h4>
          <table className="ops-table">
            <thead><tr><th>n</th><th>Fibonacci heap ms</th><th>Binary heap ms</th></tr></thead>
            <tbody>{bench.priorityQueue.map((r) => <tr key={r.n}><td>{r.n.toLocaleString()}</td><td>{r.algorithmMs}</td><td>{r.baselineMs}</td></tr>)}</tbody>
          </table>
        </>
      )}
    </>
  );
}

// ------------------------------------------------------------------ Simulation / what-if

function SimulationTab({ snap, run, draft, setDraft }) {
  const [d, setD] = useState({ type: 'ROAD_CLOSURE', radiusM: 500, minutes: 60, details: '' });
  const [mult, setMult] = useState(snap?.demandMultiplier ?? 1);
  return (
    <>
      <h3>Simulation clock</h3>
      <div className="ops-row wrap">
        <button className={snap?.running ? 'danger' : ''} onClick={() => run(() => ops.control({ running: !snap?.running }))}>{snap?.running ? '❚❚ Pause' : '▶ Run'}</button>
        <label>Speed
          <select value={snap?.tickMs ?? 1000} onChange={(e) => run(() => ops.control({ tickMs: Number(e.target.value) }))}>
            <option value={1000}>1 sim-min / s</option><option value={500}>2 / s</option><option value={200}>5 / s</option><option value={100}>10 / s</option>
          </select>
        </label>
      </div>

      <h3>Scenario</h3>
      <div className="ops-row wrap">
        {[['NORMAL', 'Normal day'], ['PEAK', 'Peak hour / festival (×1.7 demand, slower roads)'], ['MONSOON', 'Monsoon (waterlogging, ×0.55 speed, high-rise delays)']].map(([v, l]) => (
          <button key={v} className={snap?.scenario === v ? 'on' : ''} title={l} onClick={() => run(() => ops.control({ scenario: v }))}>{l.split(' (')[0]}</button>
        ))}
      </div>

      <h3>What-if</h3>
      <label className="ops-slider">Demand multiplier <b>{mult.toFixed(1)}×</b>
        <input type="range" min="0" max="3" step="0.1" value={mult} onChange={(e) => setMult(Number(e.target.value))}
          onPointerUp={() => run(() => ops.control({ demandMultiplier: mult }))} onKeyUp={() => run(() => ops.control({ demandMultiplier: mult }))} />
      </label>
      <div className="ops-row">
        <span className="ops-muted">Riders on shift: {snap?.kpis.ridersOnShift}</span>
        <button onClick={() => run(() => ops.riders(5))}>+5 riders</button>
        <button onClick={() => run(() => ops.riders(-5))}>−5 idle riders</button>
      </div>

      <h3>Report a disruption</h3>
      <p className="ops-hint">{draft ? `📍 ${draft[0].toFixed(4)}, ${draft[1].toFixed(4)}` : 'Click the map where it is.'}</p>
      <div className="ops-row wrap">
        <select value={d.type} onChange={(e) => setD({ ...d, type: e.target.value })}>
          <option value="ROAD_CLOSURE">Road closure</option><option value="WATERLOGGING">Waterlogging</option>
        </select>
        <label>Radius m<input type="number" min="50" max="3000" value={d.radiusM} onChange={(e) => setD({ ...d, radiusM: Number(e.target.value) })} /></label>
        <label>Minutes<input type="number" min="5" max="1440" value={d.minutes} onChange={(e) => setD({ ...d, minutes: Number(e.target.value) })} /></label>
        <input placeholder="Note (optional)" value={d.details} onChange={(e) => setD({ ...d, details: e.target.value })} />
        <button disabled={!draft} onClick={() => run(async () => { await ops.disruption({ ...d, lat: draft[0], lng: draft[1] }); setDraft(null); })}>Add</button>
      </div>
      <ul className="ops-alerts">
        {(snap?.disruptions ?? []).map((x) => (
          <li key={x.id}><span className="m">{x.type === 'ROAD_CLOSURE' ? '⛔' : '🌊'} {x.details}</span><span className="t">{x.endsAt ? `until ${x.endsAt.slice(11, 16)}` : 'while scenario lasts'}</span></li>
        ))}
        {!snap?.disruptions?.length && <li className="ops-muted">No active disruptions.</li>}
      </ul>
    </>
  );
}
