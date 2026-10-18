// Scenario 3: rate limit enforcement. One user hammers the gateway well above 100 req/s;
// expect a mix of 200 and 429, with Retry-After on every 429.
//   docker run --rm -i -v "$PWD/bench:/bench" grafana/k6 run /bench/ratelimit.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://host.docker.internal:8080';
const ok = new Counter('status_200');
const limited = new Counter('status_429');

export const options = {
  scenarios: {
    burst: { executor: 'constant-arrival-rate', rate: 400, timeUnit: '1s', duration: '15s', preAllocatedVUs: 50, maxVUs: 200 },
  },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export function setup() {
  const json = { headers: { 'Content-Type': 'application/json' } };
  const creds = JSON.stringify({ username: `rl${Date.now()}`, password: 'pass1234' });
  http.post(`${GATEWAY}/auth/register`, creds, json);
  return { token: http.post(`${GATEWAY}/auth/login`, creds, json).json().accessToken };
}

export default function (data) {
  const res = http.get(`${GATEWAY}/api/java/hello`, { headers: { Authorization: `Bearer ${data.token}` } });
  if (res.status === 200) ok.add(1);
  if (res.status === 429) {
    limited.add(1);
    check(res, { '429 has Retry-After': (r) => !!r.headers['Retry-After'] });
  }
}
