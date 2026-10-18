# GateKeeper Roadmap — From Skeleton to Production-Grade

This is the running list of what to add to level `gatekeeper` up into a real production-grade
microservices platform. It's organized by category, roughly in the order a team would tackle
them. Each item says *why it matters*, not just *what it is* — see [concepts.md](concepts.md)
for the underlying system-design concept explained in plain language.

Checklist state: nothing below is done yet. Update the checkboxes as items land.

## 1. Containerization & Orchestration
- [x] **Dockerfile per service** (`auth`, `gateway`, `config-server`, `discovery-server`, `svc-java`, `svc-go`) — multi-stage builds (Maven/Go build stage → slim JRE/Alpine runtime stage), each with a `HEALTHCHECK`. `svc-go`'s build context is the repo root (its `go.mod` lives there, not under `svc-go/`).
- [x] **`docker-compose.yml`** at repo root wiring up discovery-server → config-server → auth → gateway → svc-java/svc-go, plus **Redis** (the gateway needs it for rate limiting) — with `depends_on: condition: service_healthy` so services start in the right order. Verified end-to-end: register → login → JWT-protected route through the gateway → Eureka-routed to `svc-java` → Redis-backed rate limiting → direct-bypass correctly blocked by `svc-java`'s gateway secret check.
  - Fixed along the way: `gateway/application.properties` used the Spring Boot 2-era `spring.redis.*` properties, which Spring Boot 3 doesn't bind (silently falls back to `localhost`, breaking Redis connectivity for anyone but a same-host setup) — renamed to `spring.data.redis.*`.
  - Still not containerized: a real database (nothing persists anything yet — see §2 below).
- [ ] **Kubernetes manifests** (Deployment/Service/ConfigMap/Secret per service) once compose works — teaches you the same wiring at a different abstraction level (readiness/liveness probes, resource limits, rolling updates).

## 2. Data Layer
- [ ] **Real persistence for `auth`** — [UserService](../auth/src/main/java/com/gatekeeper/auth/service/UserService.java) currently stores users in a `ConcurrentHashMap`, wiped on every restart. `spring-boot-starter-data-jpa` + `h2` are already in the POM but unused — wire up Postgres (or start with H2-file / embedded Postgres for learning, then graduate).
- [ ] **Schema migrations** (Flyway or Liquibase) — so schema changes are versioned and reproducible instead of relying on `spring.jpa.hibernate.ddl-auto=update`.
- [ ] **Database-per-service** — as more services get real data needs, give each its own schema/instance rather than a shared DB, and feel the cross-service query pain that motivates the next item.

## 3. Messaging / Async Communication
- [ ] **Introduce a broker** (Kafka or RabbitMQ) for at least one flow — e.g. publish a `UserRegistered` event from `auth` that another service consumes — to feel the difference between synchronous (gateway → auth over HTTP, today) and asynchronous, decoupled communication.
- [ ] **Outbox pattern** once there's a real DB + broker — avoids the classic "wrote to DB but the publish failed" dual-write bug.

## 4. Observability (the "metrics / logs / traces" triad)
- [ ] **Prometheus** — export metrics from every service (`spring-boot-starter-actuator` is already present, add `micrometer-registry-prometheus`); currently only `health`/`info`/`gateway` actuator endpoints are exposed ([gateway/application.properties:5](../gateway/src/main/resources/application.properties)).
- [ ] **Grafana** dashboards on top of those metrics.
- [ ] **Centralized logging** (ELK, or a lighter Loki+Grafana stack) — today logs only exist per-service in each terminal/container, including a stray `System.out.println` debug line in [JwtAuthFilter](../gateway/src/main/java/com/gatekeeper/gateway/security/JwtAuthFilter.java#L26) that should become structured logging.
- [ ] **Distributed tracing** (Micrometer Tracing + Zipkin/Jaeger) — so a request's path through gateway → auth → svc-java is traceable as one trace instead of four separate log streams.

## 5. Security Hardening
- [ ] **Externalize the JWT signing secret** — it's a literal string in [JwtUtil.java:13-14](../auth/src/main/java/com/gatekeeper/auth/jwt/JwtUtil.java) (`"my-super-secret-key-my-super-secret-key"`); move to an env var, then a real secrets manager (Vault/AWS Secrets Manager).
- [ ] **Externalize the gateway↔service shared secret** — `GATEWAY_SECRET_123` is hardcoded in *two* places ([config-server/configs/gateway.properties:34](../config-server/src/main/resources/configs/gateway.properties#L34) and [svc-java/GatewayAuthFilter.java:16](../svc-java/src/main/java/com/gatekeeper/svc_java/security/GatewayAuthFilter.java#L16)) — same fix, and consider replacing the static-secret pattern entirely with mTLS or signed service-to-service JWTs once you're comfortable with the basics.
- [ ] **Lock down `auth`'s own security chain** — [SecurityConfig.java](../auth/src/main/java/com/gatekeeper/auth/security/SecurityConfig.java) currently does `anyRequest().permitAll()`, meaning `auth` trusts the gateway completely for enforcement; add defense-in-depth so a compromised/misconfigured gateway isn't a total bypass.
- [ ] **Refresh tokens / token revocation** — JWTs today are stateless with no revoke path; a logout or compromised-token scenario has no story yet.

## 6. Resilience & Completeness
- [ ] **Wire `svc-go` into the platform** — it's currently a standalone `/health` stub with no Eureka registration and no gateway route (see [main.go](../svc-go/main.go)); register it with Eureka and add a gateway route so it's an actual second polyglot backend, not a disconnected file.
- [ ] **Retries with backoff** on the gateway→auth validation call in [JwtAuthFilter](../gateway/src/main/java/com/gatekeeper/gateway/security/JwtAuthFilter.java) — right now any transient failure there surfaces straight to the client.
- [ ] **Idempotency** for `/auth/register` — calling it twice with the same username currently just depends on whatever `UserService` does; make double-submits safe explicitly.

## 7. CI/CD
- [ ] **Decide a build strategy** — there is no parent/aggregator POM today; each of the five Java modules is a fully independent `spring-boot-starter-parent` project built separately via its own `mvnw`. Either introduce a multi-module parent POM or keep per-module CI jobs — either is a valid interview talking point, but pick one deliberately.
- [ ] **GitHub Actions pipeline** — build + run tests for every module on push/PR; this alone will surface today's total lack of real test coverage.
- [ ] **Build & push Docker images** in CI once Dockerfiles exist.

## 8. Testing
- [ ] **Replace the stub tests** — every module's only test is the Spring Initializr default `contextLoads()` ([e.g. AuthApplicationTests.java](../auth/src/test/java/com/gatekeeper/auth/AuthApplicationTests.java)); none of them test actual behavior.
- [ ] **Unit tests**: `JwtUtil` (generate/validate/expired/tampered token), `UserService` (register/duplicate/password hashing).
- [ ] **Controller/slice tests**: `AuthController` (register/login/validate happy + error paths).
- [ ] **Integration tests**: gateway routing + JWT filter + circuit breaker behavior (e.g. using WireMock or Testcontainers once there's a real DB/broker/Redis to spin up).
- [ ] **Contract tests** (e.g. Pact) once there's more than one team/consumer boundary to protect.

## 9. API Surface
- [ ] **OpenAPI/Swagger** per service (springdoc-openapi) — no API docs exist today, so any consumer has to read the controller source.
- [ ] **API versioning strategy** — decide `/v1/...` vs. header-based versioning before you have a second version to support.

## 10. Deployment Strategy (concepts to learn even before you deploy anywhere real)
- [ ] **Health/readiness/liveness probes** via actuator, wired into whatever orchestrator you land on (compose healthchecks → k8s probes).
- [ ] **Blue-green / canary deployment** — worth understanding conceptually and documenting in concepts.md even before there's real infra to practice it on.

## Fix the README while you're at it
The README currently overstates the project's state (claims Java 17 — code is Java 21; claims Docker/Prometheus/Grafana/ELK exist — none do yet) and describes `infra`/`docs` as already populated when both were empty until this pass. Update it as each roadmap item actually lands, rather than describing the target state as current.
