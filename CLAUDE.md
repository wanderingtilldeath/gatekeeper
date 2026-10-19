# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

GateKeeper is a multi-module API gateway demo (no parent POM — each Java module is an independent Maven project with its own `mvnw`). Java 17, Spring Boot 3 / Spring Cloud. README mentions `infra/` (Docker Compose, Redis, monitoring) and `docs/`, but neither exists yet.

| Module | Port | Role |
|---|---|---|
| `config-server` | 8888 | Spring Cloud Config, `native` profile; serves `src/main/resources/configs/<app-name>.properties` |
| `discovery-server` | 8761 | Eureka server (does not self-register) |
| `gateway` | 8080 | Spring Cloud Gateway (reactive), routes via `lb://` Eureka names |
| `auth` | 8081 | User registration/login, issues and validates JWTs (`JwtUtil`, `UserService`, `SecurityConfig`) |
| `svc-java` | 8083 | Example backend (`/health`) |
| `svc-go` | 8082 | Example Go backend (`go.mod` at repo root, `svc-go/main.go`); not registered with Eureka |

## Commands

Run per module from its directory (use `./mvnw`, not a system `mvn`):

```
./mvnw spring-boot:run
./mvnw test
./mvnw test -Dtest=ClassName#method     # single test
go run ./svc-go                          # from repo root
```

Startup order matters: **config-server → discovery-server → auth / svc-java → gateway**. Gateway rate limiting needs Redis on `localhost:6379`.

## Architecture

- **Request flow:** client → gateway (`:8080`) → Eureka-resolved service. Routes are defined in `gateway/src/main/resources/application.properties`: `/auth/**` → `lb://auth`, `/api/java/**` → `lb://svc-java` (with `RequestRateLimiter` backed by Redis, 10/s burst 20).
- **Auth is validated by calling the auth service, not locally:** `gateway/.../security/JwtAuthFilter` (a `WebFilter`) skips paths starting with `/auth`; otherwise it requires `Authorization: Bearer <token>` and calls `http://auth/auth/validate?token=...` through the load-balanced `WebClient`. Missing header → 401; the auth service returning the literal string `"Invalid token"` or any error → 403. The gateway has no JWT key/dependency of its own.
- **Config:** every service imports `optional:configserver:http://localhost:8888` (both `bootstrap.properties` and `application.properties` are present in some modules). Because the import is `optional`, a service silently falls back to local properties if config-server is down. Note ports/names are duplicated between local `application.properties` and `config-server/.../configs/*.properties`; keep them in sync. The config file for svc-java is named `src-java.properties` (not `svc-java.properties`), so it does not match the app name `svc-java`.
- **Discovery:** all services point at Eureka `http://localhost:8761/eureka/`; hosts are hardcoded to localhost (no Docker/profile config yet).

## Index

See [INDEX.md](INDEX.md) for a compact map of every module/feature to its entry points, schemas, and tests.
