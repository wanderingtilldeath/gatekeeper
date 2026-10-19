# ADR 002: When Redis is down, the rate limiter lets requests through (fail open)

**Status:** accepted

## Context
Rate limiting uses a Redis token bucket. If Redis is unreachable the gateway has to choose: reject traffic (fail closed) or allow it (fail open).

## Decision
**Fail open.** Spring Cloud Gateway's `RedisRateLimiter` already behaves this way (it logs the error and allows the request). `GatewayResilienceTest.redisDownFailsOpenSoRequestsStillGoThrough` pins that behaviour down, and the gateway's `/actuator/health` deliberately ignores Redis, so a Redis outage doesn't make the orchestrator restart healthy gateways.

## Why
Rate limiting protects the backends from overload and abuse. It is not an authentication control. Taking the whole API offline because a cache is down is a worse outcome than a few minutes without limits, and the circuit breaker and timeouts still protect the backends.

## Consequences
- During a Redis outage there is **no brute-force protection on `/auth/login`**. Passwords are BCrypt-hashed, which slows guessing, but this is a real gap. If login protection must hold during an outage, add a small in-memory per-instance limiter on that one route (fail closed there only).
- Alert on the gateway's Redis errors; fail-open should be noticed, not silent.
