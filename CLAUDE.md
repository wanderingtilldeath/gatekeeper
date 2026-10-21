# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

GateKeeper is a multi-module API gateway demo (no parent POM — each Java module is an independent Maven project with its own `mvnw`). Java 17, Spring Boot 3 / Spring Cloud. `infra/docker-compose.yml` runs Redis + RabbitMQ; `docs/flow.svg` is the README animation.

| Module | Port | Role |
|---|---|---|
| `config-server` | 8888 | Spring Cloud Config, `native` profile; serves `src/main/resources/configs/<app-name>.properties` |
| `discovery-server` | 8761 | Eureka server (does not self-register) |
| `gateway` | 8080 | Spring Cloud Gateway (reactive), routes via `lb://` Eureka names |
| `auth` | 8081 | User registration/login, issues and validates JWTs (`JwtUtil`, `UserService`, `SecurityConfig`) |
| `svc-java` | 8083 | Order service: `/api/java/orders`; calls svc-go via Feign (circuit breaker), publishes `order.placed` to RabbitMQ; `/api/java/health` |
| `svc-go` | 8082 | Inventory (`go.mod` at repo root, `svc-go/*.go`); registers itself with Eureka over REST |
| `svc-ai` | 8085 | AI safety checkpoint: `/ai/chat` (redact private data, daily token allowance, Redis cache, primary→backup provider failover), `/ai/agent/*` (per-agent tool allow-list, speed limit, human approval for risky tools, audit). `/ai/incident/*` (admin only: reads the gateway's request log from Redis list `incidents:requests`, rule-based diagnosis + plain-English explanation, 30s watcher alerts `INCIDENT`). Fake providers/tools; publishes alerts to exchange `ai` |
| `svc-notify` | 8084 | Consumes `order.placed` and `ai.event` (queue `ai.notify`) from RabbitMQ (retry 3x, then `orders.notify.dlq`); no Eureka |

## Commands

Run per module from its directory (use `./mvnw`, not a system `mvn`):

```
./mvnw spring-boot:run
./mvnw test
./mvnw test -Dtest=ClassName#method     # single test
go run ./svc-go                          # from repo root
```

Startup order matters: **config-server → discovery-server → auth / svc-go / svc-notify → svc-java / svc-ai → gateway**. Needs Redis (`:6379`) and RabbitMQ (`:5672`): `docker compose -f infra/docker-compose.yml up -d`. svc-notify must start once before ordering (it declares the queue).

## Architecture

- **Request flow:** client → gateway (`:8080`) → Eureka-resolved service. Routes are defined in `gateway/src/main/resources/application.properties`: `/auth/**` → `lb://auth`, `/ai/**` → `lb://svc-ai` and `/api/java/**` → `lb://svc-java` (both with `RequestRateLimiter` backed by Redis, 10/s burst 20).
- **Auth is validated by calling the auth service, not locally:** `gateway/.../security/JwtAuthFilter` (a `WebFilter`) skips paths starting with `/auth`; otherwise it requires `Authorization: Bearer <token>` and calls `http://auth/auth/validate?token=...` through the load-balanced `WebClient`. Missing header → 401; the auth service returning the literal string `"Invalid token"` or any error → 403. The gateway has no JWT key/dependency of its own.
- **Config:** every service imports `optional:configserver:http://localhost:8888` (both `bootstrap.properties` and `application.properties` are present in some modules). Because the import is `optional`, a service silently falls back to local properties if config-server is down. Note ports/names are duplicated between local `application.properties` and `config-server/.../configs/*.properties`; keep them in sync. The config file for svc-java is named `src-java.properties` (not `svc-java.properties`), so it does not match the app name `svc-java`.
- **Discovery:** all services point at Eureka `http://localhost:8761/eureka/`; hosts are hardcoded to localhost (no Docker/profile config yet).

- **Request log:** `gateway/.../filter/RequestLogFilter` (WebFilter, just inside `RequestIdFilter`) pushes one JSON record per request (service=route id, status, latency, short error) to Redis `incidents:requests`, capped at 5000; fire-and-forget. `JwtAuthFilter` maps only token-check failures to 403; downstream errors keep their real status.
- **Inter-service:** gateway sets `X-User` (overwriting any client value) and `X-Request-Id` on downstream requests. svc-java and svc-ai trust `X-User`, so it must only be reachable through the gateway. Inventory reserve returns 200 `{reserved:false}` for out of stock so the breaker only counts real failures. Event publish failure is logged, not retried (no outbox).

## Index

See [INDEX.md](INDEX.md) for a compact map of every module/feature to its entry points, schemas, and tests.
