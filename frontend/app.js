'use strict';
// Gatekeeper console: plain JS, no build step. Everything the server sends is put on the page with
// textContent (never innerHTML), so a hostile response can't inject markup.

const GW = '/gw'; // nginx forwards /gw/* to the gateway, so the browser stays on one origin (no CORS)
let doFetch = (...args) => fetch(...args); // swapped for the in-browser model (demo.js) when no real gateway answers
let demo = false;

const $ = (sel) => document.querySelector(sel);
function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === 'class') el.className = v; else if (k === 'text') el.textContent = v; else el.setAttribute(k, v);
  }
  for (const kid of kids) el.append(kid);
  return el;
}

const state = { access: null, refresh: null, prevRefresh: null, header: null, claims: null, log: [], last: null };

const PRESETS = [
  { label: 'GET /api/java/hello', method: 'GET', path: '/api/java/hello' },
  { label: 'GET /api/java/whoami  (shows the identity the backend sees)', method: 'GET', path: '/api/java/whoami' },
  { label: 'GET /api/go/whoami  (Go backend)', method: 'GET', path: '/api/go/whoami' },
  { label: 'GET /api/java/slow?ms=5000  (gateway times out after 3s)', method: 'GET', path: '/api/java/slow?ms=5000' },
  { label: 'GET /api/java/fail  (backend returns 500)', method: 'GET', path: '/api/java/fail' },
  { label: 'POST /api/java/fail  (never retried)', method: 'POST', path: '/api/java/fail' },
  { label: 'GET /actuator/gateway/routes  (admin API, must be closed)', method: 'GET', path: '/actuator/gateway/routes' },
  { label: 'GET /auth/validate?token=x  (removed endpoint)', method: 'GET', path: '/auth/validate?token=x' },
];

// ---------- helpers ----------
function b64urlDecode(part) {
  const s = part.replace(/-/g, '+').replace(/_/g, '/');
  return decodeURIComponent(Array.from(atob(s.padEnd(Math.ceil(s.length / 4) * 4, '=')), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''));
}
function decodeJwt(token) {
  const [h64, p64] = token.split('.');
  return { header: JSON.parse(b64urlDecode(h64)), claims: JSON.parse(b64urlDecode(p64)) };
}
function tamper(token) { // flip one character of the signature
  const i = token.lastIndexOf('.') + 3;
  return token.slice(0, i) + (token[i] === 'A' ? 'B' : 'A') + token.slice(i + 1);
}
function statusClass(status) {
  if (status === 0) return 's0';
  if (status === 429) return 's429';
  if (status >= 500) return 's5';
  if (status >= 400) return 's4';
  return 's2';
}
function percentile(sorted, p) { return sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))] : null; }
const pretty = (v) => JSON.stringify(v, null, 2);

// ---------- calling the gateway ----------
const SHOWN_HEADERS = ['x-request-id', 'www-authenticate', 'retry-after', 'x-ratelimit-remaining', 'x-ratelimit-replenish-rate', 'x-ratelimit-burst-capacity', 'x-ratelimit-requested-tokens', 'content-type'];

async function call(method, path, { token = state.access, headers = {}, body, what = '' } = {}) {
  const init = { method, headers: { ...headers } };
  if (token) init.headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) { init.headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
  const t0 = performance.now();
  const entry = { id: state.log.length + 1, method, path, what, status: 0, ms: 0, headers: {}, body: '' };
  try {
    const res = await doFetch(GW + path, init);
    entry.status = res.status;
    entry.body = await res.text();
    for (const name of SHOWN_HEADERS) { const v = res.headers.get(name); if (v !== null) entry.headers[name] = v; }
  } catch (e) {
    entry.body = 'Network error: ' + e.message;
  }
  entry.ms = Math.round(performance.now() - t0);
  state.log.push(entry);
  if (state.log.length > 400) state.log.shift();
  state.last = entry;
  renderTimeline();
  renderFlow(entry);
  return entry;
}

// ---------- auth ----------
function setTokens(json) {
  if (state.refresh) state.prevRefresh = state.refresh;
  state.access = json.accessToken;
  state.refresh = json.refreshToken;
  const decoded = decodeJwt(json.accessToken);
  state.header = decoded.header;
  state.claims = decoded.claims;
  renderToken();
}
function parseJson(text) { try { return JSON.parse(text); } catch { return null; } }

async function register() {
  const tenant = $('#tenant').value.trim();
  const e = await call('POST', '/auth/register', { token: null, body: tenant ? { ...creds(), tenant } : creds(), what: 'register' });
  showResult(e);
  $('#auth-hint').textContent = e.status === 201 ? 'Registered. Now log in.' : (e.status === 409 ? 'That username exists. Registering again never overwrites it (409).' : 'Register failed: see the result below.');
}
async function login() {
  const e = await call('POST', '/auth/login', { token: null, body: creds(), what: 'login' });
  showResult(e);
  const json = parseJson(e.body);
  if (e.status === 200 && json && json.accessToken) { setTokens(json); $('#auth-hint').textContent = 'Logged in.'; }
  else $('#auth-hint').textContent = 'Login failed (' + e.status + ').';
}
async function refresh() {
  const e = await call('POST', '/auth/refresh', { token: null, body: { refreshToken: state.refresh }, what: 'refresh' });
  showResult(e);
  const json = parseJson(e.body);
  if (e.status === 200 && json && json.accessToken) { setTokens(json); $('#auth-hint').textContent = 'Got a new access token AND a new refresh token. The old refresh token is now used up.'; }
}
async function replayOld() {
  const e = await call('POST', '/auth/refresh', { token: null, body: { refreshToken: state.prevRefresh }, what: 'replay used refresh token' });
  showResult(e);
  $('#auth-hint').textContent = e.status === 401 ? 'Rejected. The server assumes the token was stolen and revoked the whole family: the latest refresh token no longer works either. Try "Refresh token".' : 'Unexpected result: ' + e.status;
}
function logout() { Object.assign(state, { access: null, refresh: null, prevRefresh: null, header: null, claims: null }); renderToken(); $('#auth-hint').textContent = 'Tokens forgotten.'; }
function creds() { return { username: $('#username').value.trim(), password: $('#password').value }; }

// ---------- rendering ----------
function renderToken() {
  const on = !!state.access;
  $('#token-box').hidden = !on;
  for (const id of ['#btn-refresh', '#btn-logout']) $(id).disabled = !on;
  $('#btn-replay').disabled = !state.prevRefresh;
  if (!on) return;
  $('#jwt-header').textContent = pretty(state.header);
  $('#jwt-claims').textContent = pretty(state.claims);
  tickExpiry();
}
function tickExpiry() {
  if (!state.claims) return;
  const total = state.claims.exp - state.claims.iat;
  const left = Math.max(0, state.claims.exp - Date.now() / 1000);
  $('#expiry-text').textContent = left > 0 ? `Access token expires in ${Math.floor(left / 60)}m ${String(Math.floor(left % 60)).padStart(2, '0')}s` : 'Access token expired. The gateway now answers 401; use "Refresh token".';
  $('#expiry-bar').style.width = (100 * left / total).toFixed(1) + '%';
}

function showResult(entry) {
  $('#result').hidden = false;
  const pill = $('#res-status');
  pill.textContent = entry.status === 0 ? 'no response' : String(entry.status);
  pill.className = 'pill ' + statusClass(entry.status);
  $('#res-ms').textContent = entry.ms + ' ms';
  $('#res-what').textContent = `${entry.method} ${entry.path}`;
  const table = $('#res-headers');
  table.replaceChildren(...Object.entries(entry.headers).map(([k, v]) => h('tr', {}, h('td', { text: k }), h('td', { text: v }))));
  const parsed = parseJson(entry.body);
  $('#res-body').textContent = (parsed ? pretty(parsed) : entry.body || '(empty body)').slice(0, 2000);
}

const STAGES = [['jwt', 'JWT check'], ['ratelimit', 'Rate limit'], ['breaker', 'Circuit breaker'], ['backend', 'Backend']];
function stoppedAt(entry) {
  if (entry.status === 0) return 'network';
  // the gateway's own 401 carries WWW-Authenticate; the auth service's "wrong password" 401 does not
  if (entry.status === 401 && (entry.path.startsWith('/api') || entry.headers['www-authenticate'])) return 'jwt';
  if (entry.status === 429) return 'ratelimit';
  if (entry.status === 503 && (parseJson(entry.body) || {}).error === 'Service Unavailable') return 'breaker';
  return 'backend';
}
function renderFlow(entry) {
  const stop = stoppedAt(entry);
  const isAuth = entry.path.startsWith('/auth/') && stop !== 'jwt';
  const stopIdx = STAGES.findIndex(([id]) => id === stop);
  const items = STAGES.map(([id, label], i) => {
    let cls = 'idle', note = 'not reached';
    if (stop === 'network') note = 'no response';
    else if (isAuth && (id === 'jwt' || id === 'breaker')) { cls = 'skipped'; note = id === 'jwt' ? 'public route, no token needed' : 'not used on /auth'; }
    else if (i < stopIdx) { cls = 'pass'; note = 'passed'; }
    else if (i === stopIdx) {
      const ok = entry.status < 400;
      cls = ok ? 'pass' : 'stop' + (stop === 'ratelimit' ? ' limit' : '');
      note = ok ? 'answered ' + entry.status : { jwt: 'rejected: 401', ratelimit: 'too many requests: 429', breaker: 'fallback answered 503', backend: 'answered ' + entry.status }[stop];
    }
    return h('li', { class: cls }, h('span', { class: 'name', text: label }), h('span', { class: 'state', text: note }));
  });
  $('#pipeline').replaceChildren(...items);
  $('#flow-note').textContent = `${entry.method} ${entry.path} → ${entry.status || 'no response'} in ${entry.ms} ms`;
}

const LEGEND = [['s2', '2xx ok'], ['s4', '4xx rejected'], ['s429', '429 rate limited'], ['s5', '5xx / 503 fallback'], ['s0', 'no response']];
function renderTimeline() {
  $('#timeline').replaceChildren(...state.log.map((e) => h('span', { class: 'cell ' + statusClass(e.status), title: `#${e.id} ${e.method} ${e.path} → ${e.status} (${e.ms} ms)`, role: 'img', 'aria-label': `${e.method} ${e.path} status ${e.status}` })));
  const counts = {};
  for (const e of state.log) counts[e.status] = (counts[e.status] || 0) + 1;
  const lat = state.log.filter((e) => e.status !== 0).map((e) => e.ms).sort((a, b) => a - b);
  const parts = Object.entries(counts).sort().map(([s, n]) => `${s}: ${n}`);
  if (lat.length) parts.push(`latency p50 ${percentile(lat, 0.5)} ms`, `p95 ${percentile(lat, 0.95)} ms`);
  $('#stats').replaceChildren(...(parts.length ? parts : ['no requests yet']).map((t) => h('span', { text: t })));
}

// ---------- the request lab ----------
async function sendFromLab() {
  const preset = PRESETS[$('#preset').selectedIndex];
  const mode = $('#token-mode').value;
  let token = state.access;
  if (mode === 'none') token = null;
  if (mode === 'tampered') token = state.access ? tamper(state.access) : 'not.a.token';
  if (mode === 'valid' && !state.access) $('#flow-note').textContent = 'You are not logged in, so this goes out without a token.';
  const headers = $('#spoof').checked ? { 'X-User-Id': 'admin', 'X-User-Roles': 'SUPERUSER' } : {};
  showResult(await call(preset.method, preset.path, { token, headers, what: preset.label }));
}

// ---------- scenarios ----------
async function pool(total, concurrency, fn) {
  let next = 0;
  await Promise.all(Array.from({ length: concurrency }, async () => { while (next < total) await fn(next++); }));
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function withButton(btn, fn) { btn.disabled = true; try { await fn(); } finally { btn.disabled = false; } }

async function burst() {
  if (!state.access) { $('#flow-note').textContent = 'Log in first: the limit is per user.'; return; }
  await pool(150, 25, () => call('GET', '/api/java/hello', { what: 'burst' }));
}
async function trip() {
  if (!state.access) { $('#flow-note').textContent = 'Log in first.'; return; }
  for (let i = 0; i < 12; i++) { await call('GET', '/api/java/fail', { what: 'breaker demo' }); await sleep(150); }
}
async function brute() {
  for (let i = 0; i < 6; i++) await call('POST', '/auth/login', { token: null, body: { username: $('#username').value.trim(), password: 'wrong-password-' + i }, what: 'wrong password' });
}

// Noisy neighbour: this session's tenant floods the API while a second user in another tenant sends a few requests.
// With one shared bucket the quiet tenant is starved; with per-tenant buckets it is not.
async function neighbour() {
  if (!state.access) { $('#flow-note').textContent = 'Log in first: that user is the noisy tenant.'; return; }
  const out = $('#nb-result'); out.textContent = 'Running…';
  const probe = { username: 'quiet-' + Math.random().toString(36).slice(2, 7), password: 'pass1234' };
  const tenant = 'quiet-' + Math.random().toString(36).slice(2, 6);
  await call('POST', '/auth/register', { token: null, body: { ...probe, tenant }, what: 'register quiet tenant user' });
  const login = await call('POST', '/auth/login', { token: null, body: probe, what: 'login quiet tenant user' });
  const quietToken = (parseJson(login.body) || {}).accessToken;
  if (!quietToken) { out.textContent = `Could not log in the second user (${login.status}). Wait ~10 s (login is rate limited) and retry.`; return; }
  const noisy = { ok: 0, limited: 0 }, quiet = { ok: 0, limited: 0 };
  const tally = (t, e) => { if (e.status === 200) t.ok++; else if (e.status === 429) t.limited++; };
  await Promise.all([
    pool(150, 25, async () => tally(noisy, await call('GET', '/api/java/hello', { what: 'noisy tenant' }))),
    (async () => { for (let i = 0; i < 20; i++) { tally(quiet, await call('GET', '/api/java/hello', { token: quietToken, what: 'quiet tenant' })); await sleep(20); } })(),
  ]);
  out.textContent = `noisy tenant: ${noisy.ok} ok, ${noisy.limited} limited. quiet tenant: ${quiet.ok} ok, ${quiet.limited} limited.`;
}

// ---------- server side: health + Prometheus metrics ----------
function parseProm(text) {
  const out = [];
  for (const line of text.split('\n')) {
    if (!line || line[0] === '#') continue;
    const m = line.match(/^([a-zA-Z_:][\w:]*)(?:\{(.*)\})?\s+(\S+)/);
    if (!m) continue;
    const labels = {};
    for (const l of (m[2] || '').matchAll(/(\w+)="((?:[^"\\]|\\.)*)"/g)) labels[l[1]] = l[2];
    out.push({ name: m[1], labels, value: parseFloat(m[3]) });
  }
  return out;
}
function summarize(samples) {
  const reqs = samples.filter((s) => s.name === 'spring_cloud_gateway_requests_seconds_count');
  const sum = (f) => reqs.filter(f).reduce((a, s) => a + s.value, 0);
  const breakers = {};
  for (const s of samples) if (s.name === 'resilience4j_circuitbreaker_state' && s.value === 1) breakers[s.labels.name] = s.labels.state;
  return { total: sum(() => true), limited: sum((s) => s.labels.httpStatusCode === '429'), errors: sum((s) => /^5/.test(s.labels.httpStatusCode || '')), breakers };
}
let prev = null; const rpsHistory = [];
async function pollMetrics() {
  try {
    const res = await doFetch(GW + '/actuator/prometheus');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const now = summarize(parseProm(await res.text())); const t = Date.now();
    if (prev) {
      const dt = (t - prev.t) / 1000;
      const rate = (a, b) => Math.max(0, (a - b) / dt);
      const rps = rate(now.total, prev.s.total);
      $('#m-rps').textContent = rps.toFixed(1);
      $('#m-429').textContent = rate(now.limited, prev.s.limited).toFixed(1);
      $('#m-5xx').textContent = rate(now.errors, prev.s.errors).toFixed(1);
      rpsHistory.push(rps); if (rpsHistory.length > 60) rpsHistory.shift();
      const max = Math.max(1, ...rpsHistory);
      $('#m-spark polyline').setAttribute('points', rpsHistory.map((v, i) => `${(i / 59 * 120).toFixed(1)},${(29 - v / max * 28).toFixed(1)}`).join(' '));
    }
    prev = { t, s: now };
    const names = Object.keys(now.breakers);
    $('#m-breakers').replaceChildren(...(names.length ? names.map((n) => { const st = now.breakers[n]; return h('span', { class: 'badge ' + (st === 'closed' ? 's2' : st === 'open' ? 's5' : 's4'), text: `${n}: ${st.toUpperCase()}` }); }) : ['no calls yet']));
    $('#metrics-note').textContent = '';
  } catch (e) {
    $('#metrics-note').textContent = '(metrics unavailable: ' + e.message + ')';
  }
}
async function pollHealth() {
  const el = $('#health');
  try {
    const res = await doFetch(GW + '/actuator/health');
    const ok = res.ok && (parseJson(await res.text()) || {}).status === 'UP';
    el.textContent = ok ? 'gateway: UP' : 'gateway: DOWN'; el.className = 'health ' + (ok ? 'up' : 'down');
  } catch { el.textContent = 'gateway: unreachable'; el.className = 'health down'; }
}

// ---------- start ----------
async function pickMode() {
  const forced = new URLSearchParams(location.search).get('mode');
  let live = false;
  if (forced !== 'demo') {
    try { const res = await fetch(GW + '/actuator/health'); live = res.ok && (parseJson(await res.text()) || {}).status === 'UP'; } catch { /* no gateway here */ }
  }
  if (forced === 'live') live = true;
  if (live) return;
  demo = true; doFetch = window.demoFetch;
  $('#demo-banner').hidden = false;
  $('#scope').value = window.demoControl.getScope();
  $('#scope').onchange = (e) => window.demoControl.setScope(e.target.value);
}

async function init() {
  await pickMode();
  $('#username').value = 'demo-' + Math.random().toString(36).slice(2, 6);
  $('#preset').replaceChildren(...PRESETS.map((p) => h('option', { text: p.label })));
  $('#legend').replaceChildren(...LEGEND.map(([cls, text]) => h('li', {}, h('span', { class: 'cell ' + cls }), text)));
  $('#pipeline').replaceChildren(...STAGES.map(([, label]) => h('li', { class: 'idle' }, h('span', { class: 'name', text: label }), h('span', { class: 'state', text: 'waiting' }))));
  $('#btn-register').onclick = register; $('#btn-login').onclick = login; $('#btn-refresh').onclick = refresh;
  $('#btn-replay').onclick = replayOld; $('#btn-logout').onclick = logout; $('#btn-send').onclick = sendFromLab;
  $('#scn-burst').onclick = (e) => withButton(e.currentTarget, burst);
  $('#scn-breaker').onclick = (e) => withButton(e.currentTarget, trip);
  $('#scn-brute').onclick = (e) => withButton(e.currentTarget, brute);
  $('#scn-neighbour').onclick = (e) => withButton(e.currentTarget, neighbour);
  $('#btn-clear').onclick = () => { state.log = []; renderTimeline(); };
  renderTimeline(); renderToken();
  pollHealth(); pollMetrics();
  setInterval(pollHealth, 5000); setInterval(pollMetrics, 2000); setInterval(tickExpiry, 1000);
}
init();
