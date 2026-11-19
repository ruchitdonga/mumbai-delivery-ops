// Road geometry for drawing and animating trips. Display only: dispatch decisions and ETAs stay on the Java server.
// shortcut: uses the public OSRM demo server (fair-use limits, needs internet); falls back to a straight line on failure.
const cache = new Map();

/** Road route ambulance → patient → hospital, as [leg1, leg2] lists of [lat, lng]; resolves null if unavailable. */
export function roadRoute(ambulance, patient, hospital) {
  const key = [ambulance, patient, hospital].map((p) => p.join(',')).join(';');
  if (!cache.has(key)) {
    const coords = [ambulance, patient, hospital].map(([lat, lng]) => `${lng},${lat}`).join(';');
    cache.set(key, fetch(`https://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`OSRM ${r.status}`))))
      .then((d) => splitAt(d.routes[0].geometry.coordinates.map(([lng, lat]) => [lat, lng]), patient))
      .catch(() => { cache.delete(key); return null; }));
  }
  return cache.get(key);
}

// ---- single legs for the delivery map: cached per ~220 m cell pair, fetched one at a time, 300 ms apart.
const legs = new Map(); // key -> [[lat,lng]...] | 'pending' | 'failed'
const waiting = [];
let pumping = false;

/** Road geometry through the given points, or null while it is being fetched / if OSRM failed (caller draws straight). */
export function roadPath(points, onReady) {
  const snap = (v) => (Math.round(v / 0.002) * 0.002).toFixed(3);
  const key = points.map((p) => `${snap(p[0])},${snap(p[1])}`).join('>');
  const hit = legs.get(key);
  if (Array.isArray(hit)) return hit;
  if (!hit) {
    legs.set(key, 'pending');
    waiting.push([key, points]);
    if (waiting.length > 200) legs.delete(waiting.shift()[0]); // shortcut: drop stale requests when far behind
    pump(onReady);
  }
  return null;
}

async function pump(onReady) {
  if (pumping) return;
  pumping = true;
  while (waiting.length) {
    const [key, points] = waiting.shift();
    try {
      const coords = points.map(([lat, lng]) => `${lng},${lat}`).join(';');
      const r = await fetch(`https://router.project-osrm.org/route/v1/driving/${coords}?overview=full&geometries=geojson`);
      if (!r.ok) throw new Error(`OSRM ${r.status}`);
      legs.set(key, (await r.json()).routes[0].geometry.coordinates.map(([lng, lat]) => [lat, lng]));
      onReady?.();
    } catch {
      legs.set(key, 'failed');
    }
    await new Promise((res) => setTimeout(res, 300));
  }
  pumping = false;
}

/** Splits a route at its point closest to `stop`, giving the two legs (both include that point). */
export function splitAt(line, stop) {
  let k = 0, best = Infinity;
  line.forEach((p, i) => {
    const d = (p[0] - stop[0]) ** 2 + (p[1] - stop[1]) ** 2;
    if (d < best) { best = d; k = i; }
  });
  return [line.slice(0, k + 1), line.slice(k)];
}

/** Position a fraction t (0..1) of the way along a polyline, plus the part still ahead (starting at that position). */
export function along(line, t) {
  const seg = [];
  let total = 0;
  for (let i = 1; i < line.length; i++) {
    // Flat-earth length is fine at city scale; only proportions matter here.
    const d = Math.hypot(line[i][0] - line[i - 1][0], (line[i][1] - line[i - 1][1]) * Math.cos((line[i][0] * Math.PI) / 180));
    seg.push(d);
    total += d;
  }
  let target = Math.min(Math.max(t, 0), 1) * total;
  for (let i = 0; i < seg.length; i++) {
    if (target <= seg[i] || i === seg.length - 1) {
      const f = seg[i] ? Math.min(target / seg[i], 1) : 1;
      const pos = [line[i][0] + (line[i + 1][0] - line[i][0]) * f, line[i][1] + (line[i + 1][1] - line[i][1]) * f];
      return { pos, rest: [pos, ...line.slice(i + 1)] };
    }
    target -= seg[i];
  }
  const end = line[line.length - 1];
  return { pos: end, rest: [end] };
}
