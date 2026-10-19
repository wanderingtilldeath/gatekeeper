# GateKeeper Roadmap — From Skeleton to Production-Grade

This is the running list of what to add to level `gatekeeper` up into a real production-grade
microservices platform. It's organized by category, roughly in the order a team would tackle
them. Each item says *why it matters*, not just *what it is* — see [concepts.md](concepts.md)
for the underlying system-design concept explained in plain language.

Checklist state: updated as items land. Unticked items are still open.

## 1. Containerization & Orchestration
- [x] **Dockerfile per service** (`auth`, `gateway`, `config-server`, `discovery-server`, `svc-java`, `svc-go`) — multi-stage builds (Maven/Go build stage → slim JRE/Alpine runtime stage), each with a `HEALTHCHECK`. `svc-go`'s build context is the repo root (its `go.mod` lives there, not under `svc-go/`).
- [x] **`docker-compose.yml`** at repo root wiring up discovery-server → config-server → auth → gateway → svc-java/svc-go, plus **Redis** (the gateway needs it for rate limiting) — with `depends_on: condition: service_healthy` so services start in the right order. Verified end-to-end: register → login → JWT-protected route through the gateway → Eureka-routed to `svc-java` → Redis-backed rate limiting → direct-bypass correctly blocked by `svc-java`'s gateway secret check.
  - Fixed along the way: the gateway config used the Spring Boot 2-era `spring.redis.*` properties, which Spring Boot 3 doesn't bind (silently falls back to `localhost`, breaking Redis connectivity for anyone but a same-host setup) — renamed to `spring.data.redis.*`.
  - Still not containerized: a real database (nothing persists anything yet — see §2 below).
- [ ] **Kubernetes manifests** (Deployment/Service/ConfigMap/Secret per service) once compose works — teaches you the same wiring at a different abstraction level (readiness/liveness probes, resource limits, rolling updates).

## 2. Data Layer
- [x] **Real persistence for `auth`** — users and refresh tokens live in Postgres (compose service `postgres`, volume `pgdata`).
- [x] **Schema migrations** — Flyway, `auth/src/main/resources/db/migration/V1__users_and_refresh_tokens.sql`; Hibernate only validates (`ddl-auto=validate`).
- [ ] **Database-per-service** — as more services get real data needs, give each its own schema/instance rather than a shared DB, and feel the cross-service query pain that motivates the next item.

## 3. Messaging / Async Communication
- [ ] **Introduce a broker** (Kafka or RabbitMQ) for at least one flow — e.g. publish a `UserRegistered` event from `auth` that another service consumes — to feel the difference between synchronous (gateway → auth over HTTP, today) and asynchronous, decoupled communication.
- [ ] **Outbox pattern** once there's a real DB + broker — avoids the classic "wrote to DB but the publish failed" dual-write bug.

## 4. Observability (the "metrics / logs / traces" triad)
- [x] **Prometheus** — gateway exposes `/actuator/prometheus` (route metrics + Resilience4j); `infra/prometheus.yml` scrapes it. Other services are not instrumented yet.
- [x] **Grafana** — provisioned dashboard `infra/grafana/dashboards/gateway.json` (RPS, p95, 429s, 5xx, breaker state) at http://localhost:3000.
- [ ] **Centralized logging** (ELK, or a lighter Loki+Grafana stack) — today logs only exist per-service in each terminal/container, and logs are plain text rather than structured JSON.
- [ ] **Distributed tracing** (Micrometer Tracing + Zipkin/Jaeger) — so a request's path through gateway → auth → svc-java is traceable as one trace instead of four separate log streams.

## 5. Security Hardening
- [x] **JWT signing key** — RS256 key pair from `AUTH_JWT_PRIVATE_KEY` or generated once into a persisted file; no shared secret exists any more (the gateway only needs the public JWKS).
- [x] **Gateway↔service shared secret** — read from `GATEWAY_SECRET` (compose default is demo-only), compared in constant time. Still a static secret: mTLS or signed service-to-service tokens would be the next step.
- [x] **Lock down `auth`'s own security chain** — only register/login/refresh/JWKS/health are open; everything else is denied.
- [x] **Refresh tokens** — 15-minute access tokens, rotating hashed refresh tokens, reuse detection revokes the whole family, and a daily job deletes expired rows. Not done: access-token revocation before expiry (a stolen access token works until it expires) and a logout endpoint.

## 6. Resilience & Completeness
- [x] **Wire `svc-go` into the platform** — routed at `/api/go/**` (static URI, not Eureka) with `/api/go/whoami`. Eureka registration for Go is still open.
- [x] **Retries with backoff** — the per-request call to `auth` is gone; the gateway retries idempotent GETs to backends with backoff.
- [x] **Safe `/auth/register`** — a duplicate username returns 409 and never overwrites the account.

## 7. CI/CD
- [ ] **Decide a build strategy** — there is no parent/aggregator POM today; each of the five Java modules is a fully independent `spring-boot-starter-parent` project built separately via its own `mvnw`. Either introduce a multi-module parent POM or keep per-module CI jobs — either is a valid interview talking point, but pick one deliberately.
- [x] **GitHub Actions pipeline** — `.github/workflows/ci.yml`: `mvn verify` per module, Go vet/test, compose image build. Pushing images to a registry is still open.
- [ ] **Build & push Docker images** in CI once Dockerfiles exist.

## 8. Testing
- [x] **Replace the stub tests** — auth (H2), gateway (WireMock), svc-java and svc-go now test behaviour. Config/discovery servers keep `contextLoads()` only.
- [x] **Unit/flow tests** — register/duplicate/login/refresh/reuse/JWKS verification in `AuthFlowTest`.
- [ ] **Controller/slice tests**: `AuthController` (register/login/validate happy + error paths).
- [x] **Integration tests** — gateway 401/429/timeout/breaker/retry/header-spoofing/key-rotation/Redis-down tests. Run without Docker (Redis is mocked or deliberately unreachable). Testcontainers against real Postgres/Redis is still open.
- [ ] **Contract tests** (e.g. Pact) once there's more than one team/consumer boundary to protect.

## 9. API Surface
- [ ] **OpenAPI/Swagger** per service (springdoc-openapi) — no API docs exist today, so any consumer has to read the controller source.
- [ ] **API versioning strategy** — decide `/v1/...` vs. header-based versioning before you have a second version to support.

## 10. Deployment Strategy (concepts to learn even before you deploy anywhere real)
- [ ] **Health/readiness/liveness probes** via actuator, wired into whatever orchestrator you land on (compose healthchecks → k8s probes).
- [ ] **Blue-green / canary deployment** — worth understanding conceptually and documenting in concepts.md even before there's real infra to practice it on.
