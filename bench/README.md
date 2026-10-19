# Benchmarks

Numbers depend on the machine. Always write down the hardware next to the results.

| # | Question | Script | How |
|---|----------|--------|-----|
| 1 | What does the gateway add? | `gateway.js` | Run `MODE=direct`, then `MODE=gateway`, on the current code. |
| 2 | Local JWKS validation vs a remote call per request | `gateway.js` | Run `MODE=gateway` on the `v0-baseline` checkout, then on the current code. Same `RATE`, same machine. |
| 3 | Does the rate limit hold under load? | `ratelimit.js` | Expect ~100 req/s of 200s, the rest 429 with `Retry-After`. |
| 4 | Does the breaker open? | `breaker.js` | Expect 503 JSON; watch the "Circuit breaker state" panel in Grafana (http://localhost:3000). |

Run with the k6 container (no local install) from the repo root:

```bash
docker run --rm -i -e MODE=gateway -v "$PWD/bench:/bench" grafana/k6 run /bench/gateway.js | tee bench/results/after/gateway.txt
```

Scenario 2 step by step:

```bash
# before: old design (gateway calls auth /auth/validate on every request)
git worktree add ../gatekeeper-baseline v0-baseline
(cd ../gatekeeper-baseline && docker compose up -d --build)
docker run --rm -i -e MODE=gateway -v "$PWD/bench:/bench" grafana/k6 run /bench/gateway.js | tee bench/results/baseline/gateway.txt
(cd ../gatekeeper-baseline && docker compose down)

# after: this branch
docker compose up -d --build
docker run --rm -i -e MODE=gateway -v "$PWD/bench:/bench" grafana/k6 run /bench/gateway.js | tee bench/results/after/gateway.txt
```

The baseline checkout has no `bench/` folder, which is why the commands above mount this repo's `bench/`.
Its register/login responses use `token` instead of `accessToken`; `gateway.js` handles both.

To measure maximum throughput instead of latency at a fixed rate, lift the limiter first
(`GATEWAY_LIMITS_API_RATE=100000 GATEWAY_LIMITS_API_BURST=100000` on the gateway) and raise `RATE`.

Put the measured p95 (before/after) in `bench/results/` and in the README table. Nothing is filled in until it is measured.
