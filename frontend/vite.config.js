import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The Java backend runs on :8081; the dev server forwards /api (REST) and /ws (live WebSocket) there.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 127.0.0.1, not localhost: Node may resolve localhost to IPv6 ::1, and the backend binds to IPv4 127.0.0.1.
      '/api': 'http://127.0.0.1:8081',
      '/ws': { target: 'ws://127.0.0.1:8081', ws: true },
    },
  },
});
