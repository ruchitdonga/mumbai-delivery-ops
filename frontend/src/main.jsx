import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import 'leaflet/dist/leaflet.css';
import './styles.css';
import App from './App.jsx';
import OpsApp from './ops/OpsApp.jsx';

// Two apps share one backend: the delivery command center (default) and the original ambulance dispatcher (CIA 3).
function Root() {
  const [mode, setMode] = useState(() => { try { return localStorage.getItem('mode') || 'ops'; } catch { return 'ops'; } });
  const go = (m) => { setMode(m); try { localStorage.setItem('mode', m); } catch { /* storage blocked */ } };
  document.title = mode === 'ops' ? 'Mumbai Delivery Ops' : 'Mumbai Ambulance Dispatch';
  if (mode === 'ops') return <OpsApp onSwitch={() => go('ambulance')} />;
  return (
    <>
      <App />
      <button className="mode-back" onClick={() => go('ops')}>🛵 Delivery Ops</button>
    </>
  );
}

createRoot(document.getElementById('root')).render(<Root />);
