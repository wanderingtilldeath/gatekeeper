// Scenario 4: circuit breaker. /api/java/fail always returns 500. Expect 503 JSON from the fallback;
// once the breaker is open the fallback answers without calling svc-java (watch the Grafana breaker panel).
//   docker run --rm -i -v "$PWD/bench:/bench" grafana/k6 run /bench/breaker.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://host.docker.internal:8080';
const fallback = new Counter('fallback_503');
const other = new Counter('other_status');

export const options = {
  scenarios: {
    failing: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '30s', preAllocatedVUs: 10, maxVUs: 50 },
  },
  summaryTrendStats: ['avg', 'p(95)', 'max'],
};

export function setup() {
  const json = { headers: { 'Content-Type': 'application/json' } };
  const creds = JSON.stringify({ username: `br${Date.now()}`, password: 'pass1234' });
  http.post(`${GATEWAY}/auth/register`, creds, json);
  return { token: http.post(`${GATEWAY}/auth/login`, creds, json).json().accessToken };
}

export default function (data) {
  const res = http.get(`${GATEWAY}/api/java/fail`, { headers: { Authorization: `Bearer ${data.token}` } });
  if (res.status === 503) {
    fallback.add(1);
    check(res, { 'fallback is JSON': (r) => (r.headers['Content-Type'] || '').includes('application/json') });
  } else {
    other.add(1);
  }
}
