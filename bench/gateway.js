// Latency comparison at a fixed request rate (kept under the gateway rate limit of 100/s).
//   MODE=direct   -> svc-java straight on :8083 (needs the gateway token header)
//   MODE=gateway  -> through the gateway with a valid JWT
// Run (no local k6 needed):
//   docker run --rm -i -e MODE=gateway -v "$PWD/bench:/bench" grafana/k6 run /bench/gateway.js
import http from 'k6/http';
import { check } from 'k6';

const MODE = __ENV.MODE || 'gateway';
const GATEWAY = __ENV.GATEWAY || 'http://host.docker.internal:8080';
const DIRECT = __ENV.DIRECT || 'http://host.docker.internal:8083';
const GATEWAY_TOKEN = __ENV.GATEWAY_TOKEN || 'GATEWAY_SECRET_123';
const RATE = parseInt(__ENV.RATE || '50');

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 20, maxVUs: 100,
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const json = { headers: { 'Content-Type': 'application/json' } };
  const user = `bench${Date.now()}`;
  const creds = JSON.stringify({ username: user, password: 'pass1234' });
  http.post(`${GATEWAY}/auth/register`, creds, json);
  const res = http.post(`${GATEWAY}/auth/login`, creds, json);
  const body = res.json();
  return { token: body.accessToken || body.token };
}

export default function (data) {
  let res;
  if (MODE === 'direct') {
    res = http.get(`${DIRECT}/api/java/hello`, { headers: { 'X-Gateway-Token': GATEWAY_TOKEN } });
  } else {
    res = http.get(`${GATEWAY}/api/java/hello`, { headers: { Authorization: `Bearer ${data.token}` } });
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}
