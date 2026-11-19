// Admin token for state-changing requests (only needed when the backend sets ADMIN_TOKEN).
// Kept for this browser tab only; asked for once when the backend first answers 401.
const KEY = 'adminToken';

const read = () => { try { return sessionStorage.getItem(KEY) || ''; } catch { return ''; } };

export function authHeaders() {
  const t = read();
  return t ? { 'X-Admin-Token': t } : {};
}

/** Runs a fetch; on 401 asks for the token once and retries. */
export async function withAuth(doFetch) {
  let res = await doFetch(authHeaders());
  if (res.status === 401) {
    const t = window.prompt('This action needs the admin token (ADMIN_TOKEN on the server):');
    if (t) {
      try { sessionStorage.setItem(KEY, t); } catch { /* storage blocked: token used for this request only */ }
      res = await doFetch({ 'X-Admin-Token': t });
    }
  }
  return res;
}
