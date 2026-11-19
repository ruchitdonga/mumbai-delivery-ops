import { MapContainer, TileLayer, CircleMarker, Marker, Polyline, Tooltip, useMapEvents } from 'react-leaflet';
import L from 'leaflet';

const MUMBAI = [19.08, 72.87];

// Emoji icons: no image assets to ship. Grey-out = full hospital / busy ambulance.
const icon = (emoji, cls) => L.divIcon({
  html: emoji,
  className: `map-icon ${cls}`,
  iconSize: [28, 28],
  iconAnchor: [14, 14],
});
// Hospital icon carries its free-bed count as a badge; cached per count so markers aren't rebuilt each render.
const hospitalIcons = new Map();
const hospitalIcon = (beds) => {
  if (!hospitalIcons.has(beds)) {
    hospitalIcons.set(beds, L.divIcon({
      html: `🏥<span class="beds">${beds}</span>`,
      className: `map-icon hospital${beds > 0 ? '' : ' off'}`,
      iconSize: [28, 28],
      iconAnchor: [14, 14],
    }));
  }
  return hospitalIcons.get(beds);
};

const ICONS = {
  ambulance: icon('🚑', 'ambulance'),
  ambulanceBusy: icon('🚑', 'ambulance off'),
  ambulanceMoving: icon('🚑', 'ambulance moving'),
};
export const SEVERITY_COLORS = { 1: '#9ca3af', 2: '#f59e0b', 3: '#f97316', 4: '#ef4444', 5: '#991b1b' };

function ClickToPick({ onPick }) {
  useMapEvents({ click: (e) => onPick?.([e.latlng.lat, e.latlng.lng]) });
  return null;
}

export default function MapView({ state, result, draft, onPick, zones, moving = [] }) {
  const onMission = new Set(moving.map((m) => m.a.ambulanceId));
  return (
    // zoomAnimation off: trips redraw lines/circles 10x a second, and a redraw mid zoom-animation lands at the old zoom.
    <MapContainer center={MUMBAI} zoom={11} className="map" zoomAnimation={false}>
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors · routes by <a href="https://project-osrm.org">OSRM</a>'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <ClickToPick onPick={onPick} />

      {zones?.map((z) => (
        <CircleMarker key={z.zone} center={[z.lat, z.lng]} radius={6 + z.recommendedAmbulances * 3}
          pathOptions={{ color: '#7c3aed', fillOpacity: 0.15, weight: 1 }}>
          <Tooltip>{z.zone}: keep {z.recommendedAmbulances} ambulance(s) (p{z.pTarget})</Tooltip>
        </CircleMarker>
      ))}

      {state?.hospitals.map((h) => (
        <Marker key={h.id} position={[h.lat, h.lng]} icon={hospitalIcon(h.freeBeds)}>
          <Tooltip>
            <b>{h.name}</b><br />Free beds: {h.freeBeds}<br />{h.specialities.join(', ')}
            {state.admissions?.filter((a) => a.hospitalId === h.id).map((a) => (
              <div key={a.emergencyId}>🛏 {a.emergencyId} goes home in {a.secondsLeft}s</div>
            ))}
          </Tooltip>
        </Marker>
      ))}

      {state?.ambulances.filter((a) => !onMission.has(a.id)).map((a) => (
        <Marker key={a.id} position={[a.lat, a.lng]} icon={a.busy ? ICONS.ambulanceBusy : ICONS.ambulance}>
          <Tooltip>{a.id} {a.busy ? '(busy)' : '(free)'}</Tooltip>
        </Marker>
      ))}

      {moving.map(({ a, phase, pos, rest1, rest2 }) => (
        <span key={a.ambulanceId}>
          {phase === 'toPatient' && (
            <>
              <Polyline positions={rest1} pathOptions={{ color: '#f97316', dashArray: '6 6', weight: 3 }} />
              <CircleMarker center={[a.lat, a.lng]} radius={4 + a.severity * 1.5}
                pathOptions={{ color: SEVERITY_COLORS[a.severity], fillOpacity: 0.9 }}>
                <Tooltip>{a.emergencyId} waiting for {a.ambulanceId}</Tooltip>
              </CircleMarker>
            </>
          )}
          {phase !== 'arrived' && (
            <Polyline positions={rest2}
              pathOptions={{ color: '#2563eb', weight: 3, opacity: phase === 'toPatient' ? 0.4 : 1 }} />
          )}
          <Marker position={pos} icon={ICONS.ambulanceMoving} zIndexOffset={1000}>
            <Tooltip>{a.ambulanceId}: {phase === 'toPatient' ? `going to ${a.emergencyId}` : `to ${a.hospitalName}`}</Tooltip>
          </Marker>
        </span>
      ))}

      {state?.queue.map((e) => (
        <CircleMarker key={e.id} center={[e.lat, e.lng]} radius={4 + e.severity * 1.5}
          pathOptions={{ color: SEVERITY_COLORS[e.severity], fillOpacity: 0.7 }}>
          <Tooltip>#{e.queuePosition} {e.id} · severity {e.severity} · {e.type}</Tooltip>
        </CircleMarker>
      ))}

      {/* Applied dispatches are drawn as live missions above; this draws preview routes only. */}
      {!result?.applied && result?.assignments.map((a) => (
        <span key={a.emergencyId}>
          <Polyline positions={[[a.ambulanceLat, a.ambulanceLng], [a.lat, a.lng]]}
            pathOptions={{ color: '#f97316', dashArray: '6 6', weight: 3 }} />
          <Polyline positions={[[a.lat, a.lng], [a.hospitalLat, a.hospitalLng]]}
            pathOptions={{ color: '#2563eb', weight: 3 }} />
          <CircleMarker center={[a.lat, a.lng]} radius={4 + a.severity * 1.5}
            pathOptions={{ color: SEVERITY_COLORS[a.severity], fillOpacity: 0.9 }}>
            <Tooltip>{a.emergencyId}: {a.ambulanceId} → {a.hospitalName}</Tooltip>
          </CircleMarker>
        </span>
      ))}

      {draft && (
        <CircleMarker center={draft} radius={9} pathOptions={{ color: '#111827', dashArray: '3 3', fillOpacity: 0 }}>
          <Tooltip permanent direction="top">New call here</Tooltip>
        </CircleMarker>
      )}
    </MapContainer>
  );
}
