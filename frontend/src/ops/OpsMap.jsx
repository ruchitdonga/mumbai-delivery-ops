import { memo, useEffect, useMemo, useRef, useState } from 'react';
import { along } from '../routes.js';
import { MapContainer, TileLayer, CircleMarker, Marker, Polyline, Polygon, Circle, GeoJSON, Tooltip, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';

const CENTER = [19.07, 72.87]; // whole of Mumbai: Colaba to Dahisar / Mulund
const RIDER_COLORS = { IDLE: '#22c55e', TO_STORE: '#f59e0b', DELIVERING: '#38bdf8', RETURNING: '#a3e635', OFF_SHIFT: '#64748b' };
const RISK_COLORS = { OK: '#22c55e', AT_RISK: '#f59e0b', BREACHED: '#ef4444' };
const HEAT_COLORS = ['#fde68a', '#fbbf24', '#f97316', '#ef4444', '#b91c1c'];

const storeIcons = new Map();
function storeIcon(s, small) {
  const key = `${s.active}-${s.waiting}-${small}`;
  if (!storeIcons.has(key)) {
    const size = small ? 18 : 30; // zoomed out over all of Mumbai: small icons so stores don't pile up
    storeIcons.set(key, L.divIcon({
      html: s.waiting > 0 && !small ? `🏬<span class="ops-badge">${s.waiting}</span>` : '🏬', // badge only when orders wait
      className: `ops-store${s.active ? '' : ' off'}${small ? ' sm' : ''}`,
      iconSize: [size, size],
      iconAnchor: [size / 2, size / 2],
    }));
  }
  return storeIcons.get(key);
}
const siteIcon = (rank) => L.divIcon({ html: `★<span class="ops-badge">${rank}</span>`, className: 'ops-site', iconSize: [26, 26], iconAnchor: [13, 13] });

function Interactions({ onPick, focus, onZoom }) {
  const map = useMap();
  useMapEvents({ click: (e) => onPick?.([e.latlng.lat, e.latlng.lng]), zoomend: () => onZoom(map.getZoom()) });
  useEffect(() => { if (focus) map.flyTo(focus, 14, { duration: 0.6 }); }, [focus, map]);
  return null;
}

const riderIcons = new Map();
function riderIcon(status, far) {
  const key = `${status}-${far}`;
  if (!riderIcons.has(key)) {
    riderIcons.set(key, far // whole city in view: a small dot per rider instead of a 🛵 badge
      ? L.divIcon({ html: '', className: `ops-rider-dot st-${status}`, iconSize: [8, 8], iconAnchor: [4, 4] })
      : L.divIcon({ html: '🛵', className: `ops-rider st-${status}`, iconSize: [24, 24], iconAnchor: [12, 12] }));
  }
  return riderIcons.get(key);
}
const orderIcons = new Map();
function orderIcon(risk, far) {
  const key = `${risk}-${far}`;
  if (!orderIcons.has(key)) orderIcons.set(key, far
    ? L.divIcon({ html: '', className: `ops-order-dot risk-${risk}`, iconSize: [6, 6], iconAnchor: [3, 3] })
    : L.divIcon({ html: '📦', className: `ops-order risk-${risk}`, iconSize: [18, 18], iconAnchor: [9, 9] }));
  return orderIcons.get(key);
}
const hazardIcon = (type) => L.divIcon({ html: type === 'ROAD_CLOSURE' ? '⛔' : '🌊', className: 'ops-hazard', iconSize: [24, 24], iconAnchor: [12, 12] });

/** 0 → 1 over one tick after every new snapshot, so riders glide instead of jumping. */
function useTween(snap) {
  const [t, setT] = useState(1);
  useEffect(() => {
    if (!snap) return undefined;
    const start = performance.now();
    const dur = Math.min(snap.tickMs ?? 1000, 1500) * 0.95;
    let id;
    const step = (now) => {
      const v = Math.min(1, (now - start) / dur);
      setT(v);
      if (v < 1) id = requestAnimationFrame(step);
    };
    id = requestAnimationFrame(step);
    return () => cancelAnimationFrame(id);
  }, [snap]);
  return t;
}

const previewIcon = L.divIcon({ html: '🏬', className: 'ops-store ops-store-ghost', iconSize: [34, 34], iconAnchor: [17, 17] });

/**
 * The proposed new store. Memoised on its coordinates: the map re-renders ~60x/s to animate riders, and a fresh
 * position array on every render made Leaflet snap the pin back mid-drag. The reach circle follows the drag live.
 */
const PreviewPin = memo(function PreviewPin({ lat, lng, radiusM, moveRef }) {
  const pos = useMemo(() => [lat, lng], [lat, lng]);
  const circle = useRef(null);
  const handlers = useMemo(() => ({
    drag: (e) => circle.current?.setLatLng(e.target.getLatLng()),
    dragend: (e) => { const p = e.target.getLatLng(); moveRef.current?.(p.lat, p.lng); },
  }), [moveRef]);
  return (
    <>
      <Circle ref={circle} center={pos} radius={radiusM} pathOptions={{ color: '#fde047', weight: 2, dashArray: '6 6', fillOpacity: 0.08 }} />
      <Marker position={pos} icon={previewIcon} draggable autoPan zIndexOffset={900} eventHandlers={handlers}>
        <Tooltip permanent direction="top" offset={[0, -18]}>New store here? Drag me or click the map</Tooltip>
      </Marker>
    </>
  );
}, (a, b) => a.lat === b.lat && a.lng === b.lng && a.radiusM === b.radiusM);

export default function OpsMap({ snap, layers, coverage, boundaries, heat, sites, draft, onPick, focus, selectedLocality, preview, onPreviewMove }) {
  const t = useTween(snap);
  const moveRef = useRef(onPreviewMove);
  moveRef.current = onPreviewMove; // latest callback without re-rendering the pin
  const [zoom, setZoom] = useState(12);
  const far = zoom < 13;
  // The backend moves riders along real road polylines and sends the points driven this tick (trail);
  // replaying that trail keeps riders on the road between updates.
  const placed = (snap?.riders ?? []).map((r) => {
    const pos = r.trail?.length > 1 ? along(r.trail, t).pos : [r.lat, r.lng];
    return { r, pos, rest: r.path.length > 1 ? [pos, ...r.path.slice(1)] : [] };
  });
  const congestion = Object.fromEntries((snap?.traffic ?? []).map((t) => [t.id, t.congestion]));
  const heatMax = Math.max(1, ...(heat ?? []).map((h) => Number(h.n)));
  return (
    // zoomAnimation off: riders and routes update every tick; a redraw mid-animation lands at the old zoom.
    <MapContainer center={CENTER} zoom={12} className="ops-map" zoomAnimation={false}>
      {/* Standard OSM tiles (no API key), darkened with a CSS filter in ops.css. */}
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors · routes by <a href="https://project-osrm.org">OSRM</a>'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      {/* While a new store is being previewed, a map click puts it there instead of dropping a disruption pin. */}
      <Interactions onPick={preview ? (p) => moveRef.current?.(p[0], p[1]) : onPick} focus={focus} onZoom={setZoom} />

      {(layers.areas || layers.traffic || selectedLocality) && boundaries?.map((b) => {
        const c = congestion[b.id] ?? 0;
        const sel = selectedLocality === b.id;
        return (
          <GeoJSON key={`${b.id}-${layers.traffic}-${sel}-${c}-${far}`} data={JSON.parse(b.geojson)}
            style={{ color: sel ? '#e2e8f0' : '#7c8da6', weight: sel ? 2.5 : 1.2, fillOpacity: layers.traffic ? 0.08 + c * 0.45 : sel ? 0.12 : 0.04,
              fillColor: layers.traffic ? (c > 0.6 ? '#ef4444' : c > 0.35 ? '#f59e0b' : '#22c55e') : '#94a3b8' }}>
            {/* Area names always on when zoomed in; on hover when the whole city is in view (41 labels would overlap). */}
            <Tooltip permanent={!far || sel} direction="center" className="ops-area-label">{b.name}</Tooltip>
          </GeoJSON>
        );
      })}

      {layers.coverage && coverage?.filter((c) => c.active && c.polygon.length).map((c) => {
        const tip = <Tooltip sticky>{c.code} delivery area (12-min ride{c.roadDetoursFromOsrm ? ', road detours from OSRM' : ', estimated detours'})</Tooltip>;
        const style = { color: '#38bdf8', weight: 1, fillOpacity: 0.06, dashArray: '4 4' };
        // Cut to land by the backend once areas follow the coastline; the raw ride-time polygon otherwise.
        return c.landGeojson
          ? <GeoJSON key={`${c.storeId}-${c.landGeojson.length}`} data={JSON.parse(c.landGeojson)} style={style}>{tip}</GeoJSON>
          : <Polygon key={c.storeId} positions={c.polygon} pathOptions={style}>{tip}</Polygon>;
      })}

      {layers.heat && heat?.map((h, i) => {
        const t = Number(h.n) / heatMax;
        return (
          <CircleMarker key={i} center={[h.lat, h.lng]} radius={5 + t * 14}
            pathOptions={{ stroke: false, fillColor: HEAT_COLORS[Math.min(4, Math.floor(t * 5))], fillOpacity: 0.25 + t * 0.45 }}>
            <Tooltip>{h.n} unmet-demand events here</Tooltip>
          </CircleMarker>
        );
      })}

      {snap?.disruptions?.map((d) => (
        <Circle key={d.id} center={[d.lat, d.lng]} radius={d.radiusM}
          pathOptions={{ color: d.type === 'ROAD_CLOSURE' ? '#ef4444' : '#3b82f6', fillOpacity: 0.25 }}>
          <Tooltip>{d.type === 'ROAD_CLOSURE' ? '⛔' : '🌊'} {d.details}</Tooltip>
        </Circle>
      ))}
      {snap?.disruptions?.map((d) => (
        <Marker key={`h${d.id}`} position={[d.lat, d.lng]} icon={hazardIcon(d.type)} interactive={false} />
      ))}

      {layers.riders && !far && placed.filter((p) => p.rest.length > 1).map((p) => (
        <Polyline key={`p${p.r.id}`} positions={p.rest} pathOptions={{ color: RIDER_COLORS[p.r.status], weight: 2.5, opacity: 0.75 }} />
      ))}

      {layers.orders && snap?.orders.filter((o) => !layers.riskOnly || o.risk !== 'OK').map((o) => (
        <Marker key={`o${o.id}`} position={[o.lat, o.lng]} icon={orderIcon(o.risk, far)}>
          <Tooltip>#{o.id} · {o.status} · ₹{Math.round(o.total)} · {o.minutesLeft} min to promise{o.etaMin != null ? ` · ETA ${o.etaMin} min` : ' · no rider yet'}</Tooltip>
        </Marker>
      ))}

      {/* Zoomed out, idle riders (parked at their store) are hidden: the store already stands for them. */}
      {layers.riders && placed.filter(({ r }) => !far || r.status !== 'IDLE').map(({ r, pos }) => (
        <Marker key={`r${r.id}`} position={pos} icon={riderIcon(r.status, far)} zIndexOffset={400}>
          <Tooltip>{r.code} · {r.status.replace('_', ' ').toLowerCase()} · {r.load}/{r.capacity} orders · {r.deliveriesToday} delivered today</Tooltip>
        </Marker>
      ))}

      {layers.stores && snap?.stores.map((s) => (
        <Marker key={`s${s.id}`} position={[s.lat, s.lng]} icon={storeIcon(s, far)} zIndexOffset={500}>
          <Tooltip>{s.name}{s.active ? '' : ' (inactive)'} · {s.waiting} waiting · {s.riders} riders · {s.utilPct}% of hourly capacity · {s.lowStock} low-stock SKUs</Tooltip>
        </Marker>
      ))}

      {sites?.map((s) => (
        <Marker key={`site${s.rank}`} position={[s.lat, s.lng]} icon={siteIcon(s.rank)} zIndexOffset={600}>
          <Tooltip>Recommended store #{s.rank} · {s.locality} · covers {s.coveredPeakPerHour} peak orders/h · cumulative {s.cumulativeCoveragePct}%</Tooltip>
        </Marker>
      ))}

      {preview && <PreviewPin lat={preview.lat} lng={preview.lng} radiusM={preview.radiusM} moveRef={moveRef} />}
      {draft && <CircleMarker center={draft} radius={9} pathOptions={{ color: '#f8fafc', dashArray: '3 3', fillOpacity: 0 }} />}
    </MapContainer>
  );
}

export { RIDER_COLORS, RISK_COLORS };
