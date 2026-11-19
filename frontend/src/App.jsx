import { useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import MapView, { SEVERITY_COLORS } from './MapView.jsx';
import { along, roadRoute } from './routes.js';

const TYPES = ['GENERAL', 'CARDIAC', 'TRAUMA', 'BURNS', 'NEURO'];
// Plain-English names for beginners; the API still uses the enum values.
const TYPE_LABELS = { GENERAL: 'General', CARDIAC: 'Heart problem', TRAUMA: 'Accident / injury', BURNS: 'Burns', NEURO: 'Brain / stroke' };
const SEVERITY_LABELS = { 1: 'Minor', 2: 'Moderate', 3: 'Serious', 4: 'Severe', 5: 'Critical' };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// The welcome guide remembers "hidden" per browser; storage can be blocked, so never let it throw.
const loadHelp = () => { try { return localStorage.getItem('hideHelp') !== '1'; } catch { return true; } };
const saveHelp = (show) => { try { localStorage.setItem('hideHelp', show ? '0' : '1'); } catch { /* ignore */ } };

// Animation speed: 1 simulated minute of driving = 1 real second (each leg at least 1.5 s so it is visible).
const MS_PER_SIM_MIN = 1000;
const MIN_LEG_MS = 1500;

/**
 * Where a mission's ambulance is right now: driving to the patient, then to the hospital.
 * Follows the road route when one has loaded, otherwise a straight line. rest1/rest2 = path still ahead per leg.
 */
function missionProgress({ a, start, road }, now) {
  const leg1 = Math.max(MIN_LEG_MS, a.etaToPatientMin * MS_PER_SIM_MIN);
  const leg2 = Math.max(MIN_LEG_MS, (a.totalMin - a.etaToPatientMin) * MS_PER_SIM_MIN);
  const t = Math.max(0, now - start);
  const amb = [a.ambulanceLat, a.ambulanceLng], pat = [a.lat, a.lng], hos = [a.hospitalLat, a.hospitalLng];
  const line1 = road?.[0] ?? [amb, pat], line2 = road?.[1] ?? [pat, hos];
  const left = Math.ceil((leg1 + leg2 - t) / 1000);
  if (t < leg1) {
    const r = along(line1, t / leg1);
    return { a, phase: 'toPatient', pos: r.pos, rest1: r.rest, rest2: line2, left };
  }
  if (t < leg1 + leg2) {
    const r = along(line2, (t - leg1) / leg2);
    return { a, phase: 'toHospital', pos: r.pos, rest1: [], rest2: r.rest, left };
  }
  return { a, phase: 'arrived', pos: hos, rest1: [], rest2: [], left: 0 };
}

/**
 * Animates every active mission from the server. When an ambulance reaches its hospital,
 * tells the backend (POST /arrive), which frees the ambulance and parks it at the hospital.
 */
function useMissions(state, refresh, onError) {
  const [starts, setStarts] = useState({}); // ambulanceId -> animation start time
  const [roads, setRoads] = useState({}); // emergencyId -> [leg1, leg2] road geometry (missing = straight line)
  const asked = useRef(new Set());
  const [now, setNow] = useState(Date.now());
  const reported = useRef(new Set());

  // Start animating new missions; forget ones the server no longer has (arrived, or reset).
  useEffect(() => {
    const live = state?.missions ?? [];
    setStarts((prev) => {
      const next = {};
      for (const m of live) next[m.ambulanceId] = prev[m.ambulanceId] ?? Date.now();
      return next;
    });
    const ids = new Set(live.map((m) => m.ambulanceId));
    for (const id of [...reported.current]) if (!ids.has(id)) reported.current.delete(id);

    // Ask once per trip for its road route; the ambulance moves in a straight line until (or unless) it arrives.
    for (const m of live) {
      if (asked.current.has(m.emergencyId)) continue;
      asked.current.add(m.emergencyId);
      roadRoute([m.ambulanceLat, m.ambulanceLng], [m.lat, m.lng], [m.hospitalLat, m.hospitalLng])
        .then((road) => road && setRoads((prev) => ({ ...prev, [m.emergencyId]: road })));
    }
  }, [state]);

  const active = (state?.missions ?? []).filter((m) => starts[m.ambulanceId]);
  useEffect(() => {
    if (active.length === 0) return;
    const timer = setInterval(() => setNow(Date.now()), 100);
    return () => clearInterval(timer);
  }, [active.length]);

  const moving = active.map((a) => missionProgress({ a, start: starts[a.ambulanceId], road: roads[a.emergencyId] }, now));

  useEffect(() => {
    for (const m of moving) {
      if (m.phase === 'arrived' && !reported.current.has(m.a.ambulanceId)) {
        reported.current.add(m.a.ambulanceId);
        api.arrive(m.a.ambulanceId)
          .catch((e) => {
            // Another open tab animating the same trip may have reported the arrival first; that's fine.
            if (!e.message.includes('not on a mission')) onError(e.message);
          })
          .then(refresh);
      }
    }
  });

  return moving;
}

export default function App() {
  const [tab, setTab] = useState('dispatch');
  const [state, setState] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [showHelp, setShowHelpRaw] = useState(loadHelp);
  const setShowHelp = (v) => { setShowHelpRaw(v); saveHelp(v); };

  const refresh = () => api.state().then(setState).catch((e) => setError(e.message));
  useEffect(() => { refresh(); }, []);

  // While patients are in hospital, re-read the state every 2 s so countdowns tick and freed beds appear.
  const admitted = state?.admissions?.length ?? 0;
  useEffect(() => {
    if (admitted === 0) return;
    const timer = setInterval(refresh, 2000);
    return () => clearInterval(timer);
  }, [admitted]);

  // Runs an API action with a loading flag and a visible error instead of failing silently.
  const run = async (fn) => {
    setBusy(true);
    setError('');
    try {
      return await fn();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const onUpload = (file) => {
    if (!file) return;
    run(async () => {
      let json;
      try { json = JSON.parse(await file.text()); } catch { throw new Error('That file is not valid JSON'); }
      setState(await api.upload(json));
    });
  };

  return (
    <div className="app">
      <header>
        <h1>🚑 Mumbai Ambulance Dispatch</h1>
        <nav>
          {['dispatch', 'forecast', 'benchmarks'].map((t) => (
            <button key={t} className={tab === t ? 'tab active' : 'tab'} onClick={() => setTab(t)}>
              {t[0].toUpperCase() + t.slice(1)}
            </button>
          ))}
        </nav>
        <div className="header-actions">
          <button className="btn ghost" onClick={() => { setTab('dispatch'); setShowHelp(true); }}>❓ How to use</button>
          <label className="btn ghost" title="Load your own city data (JSON file, same format as the sample)">
            Upload scenario
            <input type="file" accept=".json,application/json" hidden onChange={(e) => { onUpload(e.target.files[0]); e.target.value = ''; }} />
          </label>
          <button className="btn ghost" disabled={busy} onClick={() => run(async () => setState(await api.reset()))}>Reset sample</button>
        </div>
      </header>

      <StatsBar state={state} />

      {error && <div className="error" role="alert">⚠ {error} <button onClick={() => setError('')}>×</button></div>}

      {tab === 'dispatch' && (
        <DispatchTab state={state} refresh={refresh} run={run} busy={busy} onError={setError}
          showHelp={showHelp} setShowHelp={setShowHelp} />
      )}
      {tab === 'forecast' && <ForecastTab state={state} run={run} busy={busy} />}
      {tab === 'benchmarks' && <BenchmarkTab run={run} busy={busy} />}
    </div>
  );
}

// ------------------------------------------------------------------ Dispatch

function DispatchTab({ state, refresh, run, busy, onError, showHelp, setShowHelp }) {
  const moving = useMissions(state, refresh, onError);
  const [draft, setDraft] = useState(null);
  const [severity, setSeverity] = useState(3);
  const [type, setType] = useState('GENERAL');
  const [params, setParams] = useState({ ambulanceRadiusKm: 8, hospitalRadiusKm: 10, strategy: 'MAXFLOW' });
  const [result, setResult] = useState(null);
  const [comparison, setComparison] = useState(null);
  const [narration, setNarration] = useState(null);
  const [why, setWhy] = useState({}); // patientId -> reason they can't be helped right now
  const [live, setLive] = useState(false);

  // Live city: every 6 s, 1–3 new emergencies arrive and the Smart method sends ambulances automatically.
  useEffect(() => {
    if (!live) return;
    let alive = true;
    const tick = async () => {
      try {
        await api.simulate(1 + Math.floor(Math.random() * 3));
        await api.dispatch({ ...params, strategy: 'MAXFLOW', apply: true });
        if (alive) await refresh();
      } catch (e) {
        onError(e.message);
        setLive(false);
      }
    };
    tick();
    const timer = setInterval(tick, 6000);
    return () => { alive = false; clearInterval(timer); };
  }, [live, params]);

  // Whenever the city or the distance limits change, ask the server for a dry-run plan (nobody is sent)
  // so each waiting patient can show whether they can be helped right now, and if not, why.
  useEffect(() => {
    if (!state?.queue.length) { setWhy({}); return; }
    let stale = false;
    api.dispatch({ ...params, apply: false })
      .then((r) => {
        if (stale) return;
        const m = {};
        for (const u of r.unserved) m[u.emergencyId] = u.reason;
        setWhy(m);
      })
      // Bad input (e.g. a half-typed distance) just skips the hints; pressing Send shows the real error.
      .catch(() => { if (!stale) setWhy({}); });
    return () => { stale = true; };
  }, [state, params]);

  // Guided demo: plays the whole workflow with a caption for each step. Every step is a real API call.
  const playDemo = async () => {
    if (narration) return;
    try {
      setResult(null);
      setComparison(null);
      setNarration('Step 1 of 5 · The city: 🏥 are hospitals, 🚑 are ambulances waiting at their stations.');
      await api.reset(); await refresh(); await sleep(4000);

      setNarration('Step 2 of 5 · Emergency calls come in. Each coloured dot is a patient: bigger and darker = more serious.');
      await api.simulate(8); await refresh(); await sleep(4500);

      const s = await api.state();
      const last = s.queue[s.queue.length - 1];
      if (last && last.severity < 5) {
        setNarration(`Step 3 of 5 · Patient ${last.id} got worse! Their severity goes up and they jump to the top of the waiting list (Fibonacci heap).`);
        await api.escalate(last.id, 5); await refresh(); await sleep(5000);
      }

      setNarration('Step 4 of 5 · Comparing two ways to assign ambulances: Smart (max-flow) vs Simple (nearest first). The results table is in the side panel.');
      setComparison(await api.compare(params)); await sleep(6000);

      setNarration('Step 5 of 5 · Sending ambulances! Each 🚑 drives to its patient, then to a 🏥 hospital, then becomes free again.');
      setComparison(null);
      setResult(await api.dispatch({ ...params, apply: true })); await refresh(); await sleep(8000);

      setNarration('Done! Your turn: click the map to add a patient, then press "🚑 Send ambulances".');
      await sleep(5000);
    } catch (e) {
      onError(e.message);
    } finally {
      setNarration(null);
    }
  };

  const addCall = () => run(async () => {
    await api.addEmergency({ lat: draft[0], lng: draft[1], severity: Number(severity), type });
    setDraft(null);
    await refresh();
  });

  const doDispatch = (apply) => run(async () => {
    setComparison(null);
    setResult(await api.dispatch({ ...params, apply }));
    await refresh();
  });

  return (
    <main className="split">
      <div className="map-wrap">
        <MapView state={state} result={result} draft={draft} onPick={setDraft} moving={moving} />
        {narration && <div className="narration" role="status">{narration}</div>}
        {live && !narration && (
          <div className="narration live" role="status">🔴 LIVE · new emergencies every 6 s, ambulances sent automatically</div>
        )}
        {!narration && !live && !draft && <div className="map-tip">👆 Click anywhere on the map to report an emergency there</div>}
      </div>
      <aside>
        {state?.admissions?.length > 0 && (
          <section className="card">
            <h2>🛏 In hospital <span className="muted">({state.admissions.length}) · bed frees up when they go home</span></h2>
            <ul className="missions">
              {state.admissions.map((a) => (
                <li key={a.emergencyId}>
                  <b>{a.emergencyId}</b> at {a.hospitalName}
                  <span className="muted"> · goes home in {a.secondsLeft}s</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {showHelp && <Welcome onClose={() => setShowHelp(false)} onDemo={playDemo} running={!!narration || live} />}
        <div className="row">
          {!showHelp && (
            <button className="btn demo-btn" disabled={!!narration || live} onClick={playDemo}>
              {narration ? 'Demo playing…' : '▶ Play guided demo'}
            </button>
          )}
          <button className={live ? 'btn demo-btn live-btn on' : 'btn ghost demo-btn live-btn'} disabled={!!narration}
            title="The city runs by itself: random emergencies keep coming in and ambulances are sent automatically"
            onClick={() => setLive(!live)}>
            {live ? '⏹ Stop live city' : '🔴 Start live city'}
          </button>
        </div>
        {moving.length > 0 && (
          <section className="card">
            <h2>🚨 Active missions <span className="muted">({moving.length})</span></h2>
            <ul className="missions">
              {moving.map((m) => (
                <li key={m.a.ambulanceId}>
                  <b>{m.a.ambulanceId}</b>{' '}
                  {m.phase === 'toPatient' && <>→ picking up <b>{m.a.emergencyId}</b></>}
                  {m.phase === 'toHospital' && <>→ taking {m.a.emergencyId} to {m.a.hospitalName}</>}
                  {m.phase === 'arrived' && <>arrived at {m.a.hospitalName}</>}
                  <span className="muted"> · {m.left}s</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        <section className="card">
          <h2>1 · Report an emergency</h2>
          <p className="hint">
            {draft
              ? <>📍 Location picked. Now choose how serious it is and press <b>Add patient</b>.</>
              : <>Click on the map where the emergency is, or press <b>Add 10 random patients</b> to try it quickly.</>}
          </p>
          <div className="row">
            <label>How serious?
              <select value={severity} onChange={(e) => setSeverity(e.target.value)}>
                {[1, 2, 3, 4, 5].map((s) => <option key={s} value={s}>{s} · {SEVERITY_LABELS[s]}</option>)}
              </select>
            </label>
            <label>What kind of emergency?
              <select value={type} onChange={(e) => setType(e.target.value)}>
                {TYPES.map((t) => <option key={t} value={t}>{TYPE_LABELS[t]}</option>)}
              </select>
            </label>
          </div>
          <div className="row">
            <button className="btn" disabled={!draft || busy} onClick={addCall}>Add patient</button>
            <button className="btn ghost" disabled={busy} onClick={() => run(async () => { await api.simulate(10); await refresh(); })}>Add 10 random patients</button>
          </div>
        </section>

        <section className="card">
          <h2>2 · Waiting list <span className="muted">· most urgent first · {state?.queue.length ?? 0} waiting</span></h2>
          <p className="hint">If a patient's condition gets worse, press <b>Got worse ↑</b> and they move up the list.
            <span className="algo-tag" title="The list is kept in order by a Fibonacci heap; 'Got worse' is its decreaseKey operation">Fibonacci heap</span></p>
          {state?.queue.length === 0 && <p className="hint">Nobody is waiting. 🎉</p>}
          <ol className="queue">
            {state?.queue.map((e) => (
              <li key={e.id}>
                <span className="sev" style={{ background: SEVERITY_COLORS[e.severity] }}>{e.severity}</span>
                <div className="q-main">
                  <span><b>{e.id}</b> {SEVERITY_LABELS[e.severity]} · {TYPE_LABELS[e.type]}</span>
                  {why[e.id]
                    ? <small className="q-why">⚠ {why[e.id]}</small>
                    : <small className="q-ok">✓ Can be helped now: press Send ambulances</small>}
                </div>
                <button className="btn tiny" disabled={e.severity >= 5 || busy} title="The patient got worse: raise severity by 1"
                  onClick={() => run(async () => { await api.escalate(e.id, e.severity + 1); await refresh(); })}>
                  Got worse ↑
                </button>
              </li>
            ))}
          </ol>
        </section>

        <section className="card">
          <h2>3 · Send ambulances</h2>
          <p className="hint">The computer picks an ambulance and a hospital (with a free bed that treats the problem) for each patient.
            <span className="algo-tag" title="Edmonds–Karp maximum flow, run per severity level">Max-flow</span></p>
          <div className="row">
            <label>Max distance: ambulance → patient (km)
              <input type="number" min="0.5" max="50" step="0.5" value={params.ambulanceRadiusKm}
                onChange={(e) => setParams({ ...params, ambulanceRadiusKm: Number(e.target.value) })} />
            </label>
            <label>Max distance: patient → hospital (km)
              <input type="number" min="0.5" max="50" step="0.5" value={params.hospitalRadiusKm}
                onChange={(e) => setParams({ ...params, hospitalRadiusKm: Number(e.target.value) })} />
            </label>
          </div>
          <label>Method
            <select value={params.strategy} onChange={(e) => setParams({ ...params, strategy: e.target.value })}>
              <option value="MAXFLOW">Smart: help as many patients as possible (max-flow)</option>
              <option value="GREEDY">Simple: nearest ambulance first (greedy, for comparison)</option>
            </select>
          </label>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn" disabled={busy || !state?.queue.length} onClick={() => doDispatch(true)}>🚑 Send ambulances</button>
            <button className="btn ghost" disabled={busy} title="Show the plan on the map without sending anyone" onClick={() => doDispatch(false)}>Preview plan</button>
            <button className="btn ghost" disabled={busy} title="Run both methods on the same patients and compare" onClick={() => run(async () => { setResult(null); setComparison(await api.compare(params)); })}>Compare methods</button>
          </div>
        </section>

        {comparison && <Comparison rows={comparison} />}
        {result && <Result r={result} />}

        <Legend />
      </aside>
    </main>
  );
}

/** City at a glance: the numbers a control-room supervisor watches. */
function StatsBar({ state }) {
  if (!state) return null;
  const freeAmb = state.ambulances.filter((a) => !a.busy).length;
  const beds = state.hospitals.reduce((s, h) => s + h.freeBeds, 0);
  const items = [
    ['🚑', `${freeAmb}/${state.ambulances.length}`, 'ambulances free'],
    ['🏥', beds, 'free beds'],
    ['⏳', state.queue.length, 'waiting'],
    ['🛏', state.admissions.length, 'in hospital'],
    ['✅', state.stats.patientsHelped, `helped of ${state.stats.callsReceived} calls`],
    ['⏱', state.stats.patientsHelped ? `${state.stats.avgResponseMin} min` : '–', 'avg response'],
  ];
  return (
    <div className="statsbar">
      {items.map(([icon, value, label]) => (
        <div key={label} className="kpi"><span>{icon}</span><b>{value}</b><small>{label}</small></div>
      ))}
    </div>
  );
}

function Welcome({ onClose, onDemo, running }) {
  return (
    <section className="card welcome">
      <h2>👋 New here? Start with this</h2>
      <p>This app is a control room for Mumbai: it decides <b>which 🚑 ambulance</b> goes to <b>which patient</b>,
        and <b>which 🏥 hospital</b> they are taken to.</p>
      <ol className="steps">
        <li><b>Report an emergency:</b> click anywhere on the map, choose how serious it is, press <i>Add patient</i>.</li>
        <li><b>Check the waiting list:</b> the most urgent patients are on top. If someone gets worse, press <i>Got worse ↑</i>.</li>
        <li><b>Send ambulances:</b> press <i>🚑 Send ambulances</i> and watch them drive to the patient, then to a hospital.</li>
        <li><b>Plan ahead:</b> the <i>Forecast</i> tab says how many ambulances each area needs on a busy day.</li>
      </ol>
      <p className="hint">Not sure? Press the demo and it does all of this for you, explaining each step.</p>
      <div className="row">
        <button className="btn" onClick={onDemo} disabled={running}>{running ? 'Demo playing…' : '▶ Play guided demo'}</button>
        <button className="btn ghost" onClick={onClose}>Got it, hide this</button>
      </div>
    </section>
  );
}

function Comparison({ rows }) {
  const [mf, gr] = rows;
  return (
    <section className="card">
      <h2>Smart vs Simple (same patients, nobody actually sent)</h2>
      <table>
        <thead><tr><th></th><th>Smart (max-flow)</th><th>Simple (greedy)</th></tr></thead>
        <tbody>
          <tr><td>Patients helped</td><td><b>{mf.served}</b></td><td>{gr.served}</td></tr>
          <tr><td>Still waiting</td><td>{mf.unservedCount}</td><td>{gr.unservedCount}</td></tr>
          <tr><td>Avg time to reach patient</td><td>{mf.avgEtaToPatientMin} min</td><td>{gr.avgEtaToPatientMin} min</td></tr>
          <tr><td>Runtime</td><td>{mf.runtimeMicros} µs</td><td>{gr.runtimeMicros} µs</td></tr>
        </tbody>
      </table>
      <p className="hint">
        {mf.served > gr.served
          ? `The Smart method helps ${mf.served - gr.served} more patient(s): the Simple method gives away ambulances too early and leaves some patients with none in reach.`
          : 'Both help the same number here. The Smart method is never worse, and it wins when several patients compete for the same ambulances or beds (try adding more patients).'}
        {' '}Smart aims to help the most people, so its average travel time can be slightly longer.
      </p>
    </section>
  );
}

function Result({ r }) {
  return (
    <section className="card">
      <h2>{r.applied ? '🚑 Ambulances sent' : 'Plan preview (nobody sent yet)'} · {r.strategy === 'MAXFLOW' ? 'Smart' : 'Simple'}</h2>
      <p className="hint">
        {r.served} patient(s) got an ambulance and a hospital bed{r.unservedCount > 0 ? `; ${r.unservedCount} still waiting (reasons below)` : ''}.
      </p>
      <p className="hint">
        {r.roadDistances
          ? '🛣 Distances and times measured along real roads.'
          : '📏 Road router unreachable: distances are straight-line for now.'}
      </p>
      <div className="stats">
        <Stat label="Helped" value={r.served} />
        <Stat label="Still waiting" value={r.unservedCount} />
        <Stat label="Avg time to patient" value={`${r.avgEtaToPatientMin} min`} />
        <Stat label="Runtime" value={`${r.runtimeMicros} µs`} />
      </div>
      {r.strategy === 'MAXFLOW' && (
        <p className="hint">{r.augmentingPaths} augmenting path(s) found; {r.localSearchMoves} local-search swap(s) then shortened trips without changing who is served.</p>
      )}
      {r.bottlenecks.length > 0 && (
        <div className="bottleneck">
          <b>What ran out (bottleneck, from the min cut):</b>
          <ul>{r.bottlenecks.map((b) => <li key={b}>{b}</li>)}</ul>
        </div>
      )}
      {r.assignments.length > 0 && (
        <table>
          <thead><tr><th>Patient</th><th>Ambulance</th><th>Hospital</th><th>Arrives in</th></tr></thead>
          <tbody>
            {r.assignments.map((a) => (
              <tr key={a.emergencyId}>
                <td><span className="sev" style={{ background: SEVERITY_COLORS[a.severity] }}>{a.severity}</span> {a.emergencyId} {TYPE_LABELS[a.type]}</td>
                <td>{a.ambulanceId} <span className="muted">{a.pickupKm} km</span></td>
                <td>{a.hospitalName} <span className="muted">{a.transportKm} km</span></td>
                <td>{a.etaToPatientMin} min</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {r.unserved.length > 0 && (
        <>
          <h3>Still waiting, and why</h3>
          <ul className="unserved">
            {r.unserved.map((u) => <li key={u.emergencyId}><b>{u.emergencyId}</b> ({SEVERITY_LABELS[u.severity]}, {TYPE_LABELS[u.type]}): {u.reason}</li>)}
          </ul>
        </>
      )}
    </section>
  );
}

function Stat({ label, value }) {
  return <div className="stat"><div className="stat-value">{value}</div><div className="stat-label">{label}</div></div>;
}

function Legend() {
  return (
    <section className="card legend">
      <span><span className="emoji">🏥</span> Hospital (number = free beds, faded = full)</span>
      <span><span className="emoji">🚑</span> Ambulance (red ring = on a trip)</span>
      <span><i style={{ background: '#ef4444' }} /> Patient (bigger = more serious)</span>
      <span><i className="line dashed" /> Ambulance → patient</span>
      <span><i className="line" /> Patient → hospital</span>
    </section>
  );
}

// ------------------------------------------------------------------ Forecast

function ForecastTab({ state, run, busy }) {
  const [req, setReq] = useState({ days: 1000, seed: 42, percentile: 90, callsPerAmbulance: 8 });
  const [res, setRes] = useState(null);
  const field = (k, label, step = 1) => (
    <label>{label}
      <input type="number" step={step} value={req[k]} onChange={(e) => setReq({ ...req, [k]: Number(e.target.value) })} />
    </label>
  );

  return (
    <main className="split">
      <MapView state={state} zones={res?.zones} />
      <aside>
        <section className="card">
          <h2>How many ambulances does each area need?</h2>
          <p className="hint">
            The computer imagines 1,000 random days of emergencies in each area, finds what a <b>busy day</b> looks like
            (the 90th percentile: busier than 9 out of 10 days), and suggests enough ambulances for that.
            Just press <b>Run simulation</b>; the defaults are fine.
            <span className="algo-tag" title="Monte Carlo simulation with Poisson-distributed calls; percentiles via randomized Quickselect">Monte Carlo + Quickselect</span>
          </p>
          <div className="row">{field('days', 'Days to simulate')}{field('seed', 'Random seed (same seed = same result)')}</div>
          <div className="row">{field('percentile', 'Plan for a day busier than this % of days', 0.5)}{field('callsPerAmbulance', 'Calls one ambulance can handle per day')}</div>
          <button className="btn" disabled={busy} onClick={() => run(async () => setRes(await api.forecast(req)))}>Run simulation</button>
        </section>
        {res && (
          <section className="card">
            <h2>Recommendation</h2>
            <div className="stats">
              <Stat label={`Ambulances (p${res.percentile})`} value={res.totalRecommended} />
              <Stat label="Mean-based plan" value={res.totalMeanBased} />
              <Stat label="Runtime" value={`${(res.runtimeMicros / 1000).toFixed(1)} ms`} />
            </div>
            <table>
              <thead><tr><th>Area</th><th>Average calls</th><th>Normal day</th><th>Busy day</th><th>Ambulances</th><th>Days covered</th><th>If we planned for average</th></tr></thead>
              <tbody>
                {res.zones.map((z) => (
                  <tr key={z.zone}>
                    <td>{z.zone}</td><td>{z.simulatedMean}</td><td>{z.p50}</td><td>{z.pTarget}</td>
                    <td><b>{z.recommendedAmbulances}</b></td>
                    <td><Bar value={z.coverage} /></td>
                    <td><Bar value={z.meanBasedCoverage} warn /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="hint">"Days covered" = share of simulated days on which that many ambulances could handle every call. Planning for the average day leaves zones short far more often.</p>
          </section>
        )}
      </aside>
    </main>
  );
}

function Bar({ value, warn }) {
  return (
    <div className="bar" title={`${Math.round(value * 100)}%`}>
      <div style={{ width: `${value * 100}%`, background: warn ? '#f59e0b' : '#10b981' }} />
      <span>{Math.round(value * 100)}%</span>
    </div>
  );
}

// ------------------------------------------------------------------ Benchmarks

function BenchmarkTab({ run, busy }) {
  const [rows, setRows] = useState(null);
  const groups = rows ? [...new Set(rows.map((r) => r.experiment))] : [];

  return (
    <main className="page">
      <section className="card">
        <h2>Speed tests</h2>
        <p className="hint">
          Times each algorithm against a simpler method on small, medium and large inputs, to show how it scales.
          Blue bar = our algorithm, grey bar = the simpler method; shorter is faster. Takes about 10 seconds.
          (Technical: fixed random seeds, median of 3 runs after a warm-up, all run in Java on the server.)
        </p>
        <button className="btn" disabled={busy} onClick={() => run(async () => setRows(await api.benchmark()))}>
          {busy ? 'Running…' : 'Run benchmarks'}
        </button>
      </section>
      {groups.map((g) => {
        const rs = rows.filter((r) => r.experiment === g);
        const max = Math.max(...rs.flatMap((r) => [r.algorithmMs, r.baselineMs]), 0.001);
        return (
          <section className="card" key={g}>
            <h2>{g}</h2>
            <table>
              <thead><tr><th>n</th><th>{rs[0].algorithm}</th><th>{rs[0].baseline}</th><th>Baseline ÷ algorithm</th><th>Note</th></tr></thead>
              <tbody>
                {rs.map((r) => (
                  <tr key={r.n}>
                    <td>{r.n.toLocaleString()}</td>
                    <td><TimeBar ms={r.algorithmMs} max={max} color="#2563eb" /></td>
                    <td><TimeBar ms={r.baselineMs} max={max} color="#9ca3af" /></td>
                    <td>{r.algorithmMs > 0 ? `${(r.baselineMs / r.algorithmMs).toFixed(2)}×` : '–'}</td>
                    <td className="muted">{r.note}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        );
      })}
    </main>
  );
}

function TimeBar({ ms, max, color }) {
  return (
    <div className="bar">
      <div style={{ width: `${(ms / max) * 100}%`, background: color }} />
      <span>{ms} ms</span>
    </div>
  );
}
