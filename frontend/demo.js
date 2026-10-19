'use strict';
// In-browser stand-in for the gateway, used when no real gateway answers (e.g. on static hosting).
// It mirrors what the gateway integration tests pin down: 401 without a valid token, 429 + Retry-After,
// breaker fallback, refresh rotation with reuse detection, tenant from the token. It is a model of the design,
// not the real system: tokens are not cryptographically signed and the latencies it shows mean nothing.
(() => {
  const users = new Map();      // username -> { id, password, tenant }
  const refreshTokens = new Map(); // token -> { username, family, used }
  const buckets = new Map();
  const counts = new Map();     // "routeId|status" -> n
  const breakers = {};          // name -> { state, window, openedAt }
  let scope = 'user';           // what the /api rate-limit bucket is keyed by: user | tenant | global
  const SIG = 'demo-signature';

  const b64 = (o) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const uuid = () => crypto.randomUUID();
  const json = (status, body, headers = {}) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
  const empty = (status, headers = {}) => new Response(null, { status, headers });

  function take(key, rate, burst, cost = 1) {
    const now = Date.now() / 1000;
    const b = buckets.get(key) || { tokens: burst, t: now };
    b.tokens = Math.min(burst, b.tokens + (now - b.t) * rate);
    b.t = now;
    const ok = b.tokens >= cost;
    if (ok) b.tokens -= cost;
    buckets.set(key, b);
    const headers = { 'X-RateLimit-Remaining': String(Math.floor(b.tokens)), 'X-RateLimit-Replenish-Rate': String(rate), 'X-RateLimit-Burst-Capacity': String(burst), 'X-RateLimit-Requested-Tokens': String(cost) };
    if (!ok) headers['Retry-After'] = String(Math.max(1, Math.ceil(cost / rate)));
    return { ok, headers };
  }

  function mintTokens(username, user, family) {
    const now = Math.floor(Date.now() / 1000);
    const claims = { iss: 'https://gatekeeper.local', sub: user.id, iat: now, exp: now + 900, jti: uuid(), username, roles: ['USER'], tenant: user.tenant };
    const accessToken = `${b64({ alg: 'DEMO', kid: 'demo-key' })}.${b64(claims)}.${SIG}`;
    const refreshToken = uuid() + uuid();
    refreshTokens.set(refreshToken, { username, family, used: false });
    return { accessToken, refreshToken, tokenType: 'Bearer', expiresIn: 900 };
  }

  function claimsOf(req) {
    const m = /^Bearer (\S+)$/.exec(req.headers.Authorization || '');
    if (!m) return null;
    const parts = m[1].split('.');
    if (parts.length !== 3 || parts[2] !== SIG) return null;
    try {
      const claims = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
      return claims.exp > Date.now() / 1000 ? claims : null;
    } catch { return null; }
  }

  const count = (route, status) => counts.set(`${route}|${status}`, (counts.get(`${route}|${status}`) || 0) + 1);
  function done(route, res) { count(route, res.status); return res; }

  // ---- circuit breaker: 10-call window, opens at >=50% failures after 5 calls, 10 s open, then half-open ----
  function breakerFor(name) { return (breakers[name] ||= { state: 'closed', window: [], openedAt: 0 }); }
  function breakerAllows(b) {
    if (b.state === 'open' && Date.now() - b.openedAt >= 10000) b.state = 'half_open';
    return b.state !== 'open';
  }
  function record(b, failed) {
    if (b.state === 'half_open') { b.state = failed ? 'open' : 'closed'; b.openedAt = Date.now(); b.window = []; return; }
    b.window.push(failed); if (b.window.length > 10) b.window.shift();
    if (b.window.length >= 5 && b.window.filter(Boolean).length / b.window.length >= 0.5) { b.state = 'open'; b.openedAt = Date.now(); }
  }
  const fallback = (route, name) => done(route, json(503, { error: 'Service Unavailable', message: `${name} is unavailable, try again later` }));

  function api(req, path, query) {
    const claims = claimsOf(req);
    if (!claims) return empty(401, { 'WWW-Authenticate': 'Bearer' });
    const java = path.startsWith('/api/java/');
    const route = java ? 'svc-java' : 'svc-go';
    const key = scope === 'global' ? 'all' : scope === 'tenant' ? 'tenant:' + claims.tenant : claims.sub;
    const rl = take(`${route}|${key}`, 100, 100);
    if (!rl.ok) return done(route, empty(429, rl.headers));
    const requestId = uuid();
    const headers = { ...rl.headers, 'X-Request-Id': requestId };
    const b = breakerFor(java ? 'javaService' : 'goService');
    if (!breakerAllows(b)) return fallback(route, java ? 'java service' : 'go service');

    const who = (service) => json(200, { service, userId: claims.sub, roles: claims.roles.join(','), tenantId: claims.tenant, requestId }, headers);
    let res;
    if (java && path === '/api/java/hello') res = new Response('Hello from svc-java', { status: 200, headers });
    else if (java && path === '/api/java/whoami') res = who('svc-java');
    else if (!java && path === '/api/go/whoami') res = who('svc-go');
    else if (java && path === '/api/java/fail') { record(b, true); return fallback(route, 'java service'); }
    else if (java && path === '/api/java/slow') {
      if (Number(query.get('ms') || 5000) > 3000) { record(b, true); return fallback(route, 'java service'); } // gateway response timeout is 3 s
      res = new Response('finally', { status: 200, headers });
    } else res = json(404, { error: 'Not Found' }, headers);
    record(b, false);
    return done(route, res);
  }

  function auth(req, path) {
    const body = (() => { try { return JSON.parse(req.body || '{}'); } catch { return null; } })();
    const post = req.method === 'POST';
    if (path === '/auth/login' && post) {
      const rl = take('login', 1, 30, 10);
      if (!rl.ok) return done('auth-login', empty(429, rl.headers));
      const u = body && users.get(body.username);
      if (!u || u.password !== body.password) return done('auth-login', json(401, { error: 'Invalid credentials' }, rl.headers));
      return done('auth-login', json(200, mintTokens(body.username, u, uuid()), rl.headers));
    }
    if ((path === '/auth/register' || path === '/auth/refresh') && post) {
      const rl = take('auth', 1, 5);
      if (!rl.ok) return done('auth', empty(429, rl.headers));
      if (path === '/auth/register') {
        const ok = body && /^[A-Za-z0-9_.-]{3,32}$/.test(body.username || '') && (body.password || '').length >= 8 && body.password.length <= 72
          && (body.tenant == null || /^[a-z0-9-]{1,64}$/.test(body.tenant));
        if (!ok) return done('auth', json(400, { error: 'Validation failed' }, rl.headers));
        if (users.has(body.username)) return done('auth', json(409, { error: 'Username already taken' }, rl.headers));
        const user = { id: uuid(), password: body.password, tenant: body.tenant || 'default' };
        users.set(body.username, user);
        return done('auth', json(201, { id: user.id, username: body.username }, rl.headers));
      }
      const t = body && refreshTokens.get(body.refreshToken);
      if (!t) return done('auth', json(401, { error: 'Invalid refresh token' }, rl.headers));
      if (t.used) {
        for (const [k, v] of refreshTokens) if (v.family === t.family) refreshTokens.delete(k);
        return done('auth', json(401, { error: 'Refresh token reuse detected; log in again' }, rl.headers));
      }
      t.used = true;
      return done('auth', json(200, mintTokens(t.username, users.get(t.username), t.family), rl.headers));
    }
    return claimsOf(req) ? json(404, { error: 'Not Found' }) : empty(401, { 'WWW-Authenticate': 'Bearer' });
  }

  function prometheus() {
    const lines = [];
    for (const [k, n] of counts) { const [routeId, code] = k.split('|'); lines.push(`spring_cloud_gateway_requests_seconds_count{routeId="${routeId}",httpStatusCode="${code}"} ${n}`); }
    for (const [name, b] of Object.entries(breakers)) for (const s of ['closed', 'open', 'half_open']) lines.push(`resilience4j_circuitbreaker_state{name="${name}",state="${s}"} ${b.state === s ? 1 : 0}`);
    return lines.join('\n');
  }

  window.demoFetch = async (url, init = {}) => {
    const u = new URL(url.replace(/^\/gw/, ''), 'http://demo');
    const req = { method: (init.method || 'GET').toUpperCase(), headers: init.headers || {}, body: init.body };
    const path = u.pathname;
    if (path === '/actuator/health') return json(200, { status: 'UP' });
    if (path === '/actuator/prometheus') return new Response(prometheus(), { status: 200 });
    if (path.startsWith('/api/')) return api(req, path, u.searchParams);
    if (path.startsWith('/auth/')) return auth(req, path);
    return claimsOf(req) ? json(404, { error: 'Not Found' }) : empty(401, { 'WWW-Authenticate': 'Bearer' }); // e.g. /actuator/gateway/routes
  };
  window.demoControl = { getScope: () => scope, setScope: (s) => { scope = s; buckets.clear(); } };
})();
