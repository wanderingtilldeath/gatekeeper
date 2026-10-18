# System Design Concepts in GateKeeper (Plain-Language Notes for Interview Prep)

This file explains, in plain language, every system design concept currently used in this
repo — plus the concepts on the [roadmap](ROADMAP.md) that aren't built yet. For each one:
**what it is**, **where it lives here**, **why it's used**, **questions an interviewer might
ask**, and **the gap** — because interviewers love asking "what's wrong with this
implementation," and this repo has several honest answers ready-made.

---

## Part 1 — Concepts already in this repo

### 1. Microservices vs. a monolith

**What it is:** Instead of one big application, the system is split into small, independently
deployable services, each owning one responsibility.

**Where it lives here:** `discovery-server`, `config-server`, `gateway`, `auth`, `svc-java`,
`svc-go` — six separate Spring Boot / Go apps, each with its own `pom.xml`/`go.mod`, each
runnable and deployable on its own.

**Why:** independent scaling (scale `auth` without scaling `svc-java`), independent deploys
(ship a fix to `gateway` without redeploying everything), fault isolation (one service crashing
doesn't necessarily take down the others), and teams can own a service each.

**Interview angle:** "Why not a monolith?" → faster to build alone, but microservices buy you
independent scaling/deployment/failure isolation at the cost of operational complexity
(network calls instead of function calls, distributed data, harder debugging). "When would you
NOT use microservices?" → small team, unclear domain boundaries, early-stage product — a
monolith is usually the right *first* choice.

**Gap here:** there's no aggregator/parent POM tying the five Java services together — each is
a fully independent Maven project. That's a legitimate choice (true independence) but means
there's no single `mvn install` at the root; see [ROADMAP.md §7](ROADMAP.md#7-cicd).

---

### 2. Service Discovery (Eureka)

**What it is:** In a system with many service instances whose IPs/ports change (scaling,
restarts, deploys), services need a way to find each other without hardcoding addresses. A
**service registry** is a directory: services register themselves on startup, and callers ask
the registry "where is `auth` right now?" instead of hardcoding an address.

**Where it lives here:** `discovery-server` runs a Netflix Eureka server
([DiscoveryServerApplication.java](../discovery-server/src/main/java/com/gatekeeper/discovery_server/DiscoveryServerApplication.java),
`@EnableEurekaServer`, port 8761). `auth` and `gateway` register with it as clients. The
gateway routes to `auth` via `lb://auth` (see routing config below) instead of
`http://<hardcoded-ip>:8081`.

**Why:** this is the **client-side discovery pattern** — the calling service (or its
load-balancer library) asks the registry directly and picks an instance itself, as opposed to
**server-side discovery** where a dedicated load balancer/proxy does the lookup on the caller's
behalf (e.g. AWS ALB + target groups, or a service mesh sidecar).

**Interview angle:** "Client-side vs server-side discovery, trade-offs?" → client-side (Eureka
style) avoids an extra network hop but couples every client to the discovery library; server-side
(a load balancer or mesh) centralizes the logic but adds a hop and another piece of
infrastructure to run. "What happens if the registry goes down?" → clients typically cache the
last-known registry snapshot, so short outages degrade gracefully rather than failing instantly
— that resilience is *why* Eureka clients poll-and-cache instead of doing a live lookup per call.

**Gap here:** `discovery-server` is configured standalone
(`register-with-eureka=false`, `fetch-registry=false` — it doesn't peer with other registry
instances), so it's a single point of failure with no registry-level HA. `svc-go` never
registers at all — it's disconnected from discovery entirely (see [ROADMAP.md §6](ROADMAP.md#6-resilience--completeness)).

---

### 3. Centralized Configuration (Spring Cloud Config Server)

**What it is:** Instead of each service carrying all its own config baked into its jar, a
central **config server** serves configuration to every service at startup (and optionally on
refresh), so config changes don't require rebuilding/redeploying every service.

**Where it lives here:** `config-server`
([ConfigServerApplication.java](../config-server/src/main/java/com/gatekeeper/config_server/ConfigServerApplication.java),
`@EnableConfigServer`, port 8888) serves property files from
`src/main/resources/configs/` (now just `gateway.properties`).
Each service points at it via `spring.config.import=optional:configserver:http://localhost:8888`.

**Why:** one source of truth for config across services; in a "real" setup this is usually
backed by a Git repo (so config changes are versioned, reviewable, and auditable) rather than
files bundled in the server's own jar.

**Interview angle:** "How would you roll out a config change to 50 running instances without
restarting them?" → Spring Cloud Config supports a `/refresh` (or bus-based `/refresh` broadcast
via Spring Cloud Bus + a message broker) so `@RefreshScope` beans pick up new values live.

**Gap here:** this config server uses the **native profile**
(`spring.cloud.config.server.native.search-locations=classpath:/configs`) — configs are files
packaged *inside the jar itself*, not pulled from a real Git-backed repo, so a change still needs
a rebuild of `config-server` (a Git backend would remove that). It now serves only the gateway's
tunables (rate limits and the `svc-java` timeout, see
[gateway.properties](../config-server/src/main/resources/configs/gateway.properties)); the routes
themselves live in the gateway so they can be tested.

---

### 4. API Gateway pattern

**What it is:** A single entry point that sits in front of all backend services, handling
cross-cutting concerns (routing, auth, rate limiting, circuit breaking) in one place instead of
duplicating that logic into every service.

**Where it lives here:** `gateway` (Spring Cloud Gateway, WebFlux-based, port 8080). Routes are
defined in [application.yml](../gateway/src/main/resources/application.yml): `/auth/login` (strict
per-IP limit), `/auth/**`, `/api/java/**` (per-user rate limit, circuit breaker, GET-only retry,
injected gateway header) and `/api/go/**`. Requests are authenticated locally with Spring
Security's resource server before any route runs.

**Why:** clients only ever talk to one host; you can add auth/rate-limiting/observability once
at the gateway instead of in every service; backend services can move/rename/scale without
clients noticing.

**Interview angle:** "What's the downside of an API gateway?" → it's a single point of failure
and a potential bottleneck if it's not scaled/made resilient itself; it can also become a
"God object" if too much business logic creeps into it (it should stay cross-cutting concerns
only, not business logic).

---

### 5. Authentication & Authorization (JWT)

**What it is:** **Authentication** = proving who you are (login). **Authorization** = what
you're allowed to do. A **JWT (JSON Web Token)** is a signed, self-contained token: it carries
claims (like the username and an expiry) and a cryptographic signature, so any service holding
the signer's **public key** can verify it *without* calling back to a central service or database
— this is called **stateless authentication**. (With asymmetric RS256 only `auth` can *create*
tokens; everyone else just verifies. A shared HMAC secret would let every verifier forge tokens.)

**Where it lives here:**
[AuthController](../auth/src/main/java/com/gatekeeper/auth/web/AuthController.java)
exposes `/auth/register`, `/auth/login`, `/auth/refresh` and `/auth/.well-known/jwks.json`.
[SigningKey](../auth/src/main/java/com/gatekeeper/auth/token/SigningKey.java) signs 15-minute
access tokens with an **RS256 private key** (public half published as JWKS, `kid` in the header).
[AuthService](../auth/src/main/java/com/gatekeeper/auth/token/AuthService.java) issues rotating
refresh tokens (stored only as SHA-256 hashes) and revokes a whole token family if an old one is
replayed. Passwords are hashed with BCrypt
([PasswordConfig](../auth/src/main/java/com/gatekeeper/auth/config/PasswordConfig.java)); users
and refresh tokens live in Postgres.

**Why stateless auth:** any service (or the gateway) can validate a token independently without
a shared session store — good for horizontal scaling, since there's no server-side session
state to replicate.

**Interview angle:** "Stateless JWT vs. server-side sessions?" → JWTs scale better (no shared
session store, no sticky sessions needed) but are harder to revoke early (a session can be
deleted from the store instantly; a JWT is valid until it expires, unless you add a revocation
mechanism). "How would you support logout / revoke a compromised token?" → short-lived access
tokens + a refresh-token flow, or a server-side blocklist of revoked token IDs.

**What's still open (good to be honest about in an interview):**
- There is **no access-token revocation**: a stolen access token works until it expires (15
  minutes). Refresh tokens can be revoked (reuse detection), but there is no logout endpoint yet.
- Key rotation: the gateway picks up a new `kid` automatically (it re-fetches the JWKS on an
  unknown key id, at most once per 30 seconds so junk tokens can't flood `auth`), but `auth` doesn't rotate keys by itself; publishing old+new keys during an
  overlap is a manual step today.
- `auth` denies every route except register/login/refresh/JWKS/health, but it still trusts the
  network for those (no mTLS).

---

### 6. Load Balancing (client-side)

**What it is:** When a service has multiple running instances, something has to decide which
instance handles each request. **Client-side load balancing** means the *caller* (or a library
it uses) picks the instance, using data from the service registry — as opposed to a dedicated
load-balancer appliance/proxy sitting in the network path.

**Where it lives here:** the gateway's routes use `lb://auth` and `lb://svc-java` URIs
([application.yml](../gateway/src/main/resources/application.yml)) — the `lb://`
scheme tells Spring Cloud Gateway to resolve the hostname through Eureka + Spring Cloud
LoadBalancer, picking one of the registered instances (round-robin by default), rather than
calling a fixed host:port.

**Why:** as `auth` or `svc-java` scale to multiple instances, the gateway automatically spreads
requests across all of them without any config change.

**Interview angle:** "Round robin vs. least-connections vs. weighted?" round robin (the
default here) is simplest but ignores instance load; least-connections adapts to slow
instances; weighted lets you send more traffic to bigger instances or do gradual canary
rollouts.

---

### 7. Circuit Breaker pattern (Resilience4j)

**What it is:** A safety mechanism borrowed from electrical circuit breakers: if calls to a
downstream service keep failing, "trip" the circuit and stop calling it for a while (failing
fast instead), then periodically test if it's recovered before fully resuming traffic. This
prevents one failing service from cascading into slow, resource-exhausting calls throughout the
whole system.

**Where it lives here:** the `svc-java` route in
[application.yml](../gateway/src/main/resources/application.yml) wraps
calls in a `CircuitBreaker` filter (instance name `javaService`), configured under `resilience4j`
with a sliding window of 10 calls, a minimum of 5 calls before evaluating, a 50% failure-rate
threshold to trip, a 10s wait in the open state, then 2 trial calls in "half-open" state before
deciding whether to fully close again. A 3s response timeout bounds each call, and only GETs are
retried (2 retries with backoff). On trip (or timeout, or a 5xx), requests are forwarded to
[FallbackController](../gateway/src/main/java/com/gatekeeper/gateway/fallback/FallbackController.java),
which answers `503` with a JSON body for any HTTP method. Behaviour is pinned by
[GatewayResilienceTest](../gateway/src/test/java/com/gatekeeper/gateway/GatewayResilienceTest.java).

**Why:** without this, a slow/failing `svc-java` would cause the gateway to pile up slow
requests waiting on it, exhausting threads/connections and potentially taking down the gateway
itself — the circuit breaker fails fast instead.

**Interview angle:** "Explain the three circuit breaker states." Closed (normal, calls pass
through, failures counted) → Open (calls fail immediately without even trying, no traffic
reaches the failing service) → Half-Open (a few trial calls are let through to test recovery) →
back to Closed if they succeed, or back to Open if they don't. "Circuit breaker vs. retry vs.
timeout — how do they combine?" Timeout bounds how long you wait for one call; retry handles
transient blips; circuit breaker stops you from *retrying into* a service that's clearly down.
They're complementary, not alternatives.

---

### 8. Rate Limiting

**What it is:** Capping how many requests a client (or the system as a whole) can make in a
given time window, to protect backend services from being overwhelmed — by an abusive client,
a bug in another service, or just organic traffic spikes.

**Where it lives here:** the `svc-java` and `svc-go` routes use a Redis-backed **token bucket** limiter
(`RequestRateLimiter`, default `replenishRate=100`, `burstCapacity=100`, tunable via Config Server).
Requests are keyed by the verified JWT `sub` (`userKeyResolver`), so minting a new token doesn't
buy a new bucket. `/auth/login` has its own much stricter per-IP limiter against password
guessing. Over-limit requests get `429` plus `Retry-After`
([GatewayHeadersFilter](../gateway/src/main/java/com/gatekeeper/gateway/filter/GatewayHeadersFilter.java)).
If Redis is unreachable the limiter **fails open** — see [ADR 002](adr/002-rate-limit-fails-open.md).

**Why token bucket specifically:** it allows bursts up to the bucket capacity while enforcing a
steady average rate over time (`replenishRate`) — friendlier to real traffic patterns than a
strict fixed window that resets sharply at interval boundaries.

**Interview angle:** "Token bucket vs. fixed window vs. sliding window log — trade-offs?" Fixed
window is simplest but allows bursts right at window boundaries (2x traffic possible right
around the reset instant); sliding window log is precise but memory-heavy (must store every
request timestamp); token bucket (used here) is a good middle ground — smooths bursts, cheap to
implement, doesn't need per-request history. "Why key by user token or IP instead of a global
limit?" — a global limit lets one abusive client starve everyone else; per-key limits isolate
the impact.

**Gap here:** the limit is per-IP/per-user at one gateway; there is no global or per-tenant
quota, and behind a proxy the IP key would see the proxy's address (X-Forwarded-For is
deliberately ignored because clients can spoof it).

---

### 9. Gateway-to-service trust ("zero trust from outside, trust the gateway")

**What it is:** Backend services shouldn't just trust that every request they receive came
through the gateway (and therefore already passed auth/rate-limiting) — someone could call them
directly if the network allows it. One mitigation: require a shared secret header that only the
gateway knows to inject, so a service can reject any request that didn't come through the
gateway.

**Where it lives here:** the gateway sets `X-Gateway-Token` (from `GATEWAY_SECRET`) on the
`svc-java` and `svc-go` routes
([application.yml](../gateway/src/main/resources/application.yml)), replacing any value a client
sent. [svc-java's GatewayAuthFilter](../svc-java/src/main/java/com/gatekeeper/svc_java/security/GatewayAuthFilter.java)
(and `svc-go`) reject any request lacking that exact value with 403, comparing in constant time.

**Why:** this is a cheap way to implement "only the gateway may call me directly" without full
network-level isolation.

**Interview angle:** "Why is a static shared secret weaker than mTLS?" — it's a single static
value: if it leaks (say, committed to a repo), anyone can impersonate
the gateway forever, and there's no per-service identity or rotation. The production-grade
answer is **mutual TLS (mTLS)** between services (each service has its own certificate, proving
identity cryptographically per-connection) or a **service mesh** (e.g. Istio/Linkerd) that
handles this transparently via sidecars, plus short-lived rotated credentials instead of one
eternal string.

**Gap here:** it is still one static shared secret (now read from the `GATEWAY_SECRET` environment
variable instead of source code). The compose default is for local demos only; real deployments
set their own. Per-service identity and rotation would need mTLS or a mesh.

---

## Part 2 — Concepts on the roadmap (not built yet, but worth knowing now)

These are covered in more implementation detail in [ROADMAP.md](ROADMAP.md); here's the
plain-language "what and why" for interview purposes.

### Containers & orchestration (Docker, Kubernetes)
A **container** packages an app with everything it needs to run (dependencies, runtime) into one
portable unit, so "works on my machine" becomes "works everywhere the container runtime runs."
**Orchestration** (Kubernetes) is the layer above that: given a fleet of containers across many
machines, it handles scheduling (which machine runs which container), self-healing (restart a
crashed container), scaling (add more instances under load), and rolling updates (replace old
versions with new ones without downtime). Interview framing: "containers solve *packaging and
portability*; orchestration solves *fleet management*." This repo has neither yet — everything
runs via `mvnw spring-boot:run` on the host.

### Message queues & event-driven architecture
So far every call in this system is **synchronous**: the caller blocks waiting for a response
(gateway calls `auth` and waits). A **message queue** (Kafka, RabbitMQ) lets a service instead
**publish an event** ("user registered") and move on immediately; other services **subscribe**
and react whenever they're ready. This decouples services in time (the subscriber doesn't need
to be up *right now*) and in knowledge (the publisher doesn't need to know who's listening).
Trade-off: you gain resilience and decoupling but lose the simplicity of "I called it, I know
immediately if it worked" — you now have **eventual consistency** to reason about.

### The observability triad: metrics, logs, traces
- **Metrics** (Prometheus/Grafana): numeric time series — request rate, error rate, latency
  percentiles — good for dashboards and alerting ("p99 latency > 2s for 5 minutes").
- **Logs** (ELK/Loki): detailed text records of individual events — good for "what exactly
  happened at 3:04:12pm."
- **Traces** (Zipkin/Jaeger): follow *one request* as it hops across services (gateway → auth →
  svc-java), showing where time was spent — essential once a request touches more than one
  service, since logs alone can't easily reconstruct that path.
Interview framing: "metrics tell you *something* is wrong, traces tell you *where*, logs tell
you *why*."

### CI/CD
**Continuous Integration** = every code change is automatically built and tested before it can
merge, catching breakage early. **Continuous Deployment/Delivery** = passing changes are
automatically packaged (and optionally deployed) without manual steps. This repo has none yet —
every module would need to be built and tested by hand today.

### Database-per-service & distributed transactions
Once each service owns its own database (rather than sharing one), you lose the ability to do a
single ACID transaction across two services' data. Two common patterns that replace it:
- **Saga pattern**: a sequence of local transactions, each publishing an event that triggers the
  next step; if a later step fails, earlier steps run **compensating actions** to undo their
  work (there's no single rollback anymore).
- **CQRS** (Command Query Responsibility Segregation): separate the model used to *write* data
  from the model used to *read* it, often with the read model being a denormalized, eventually-
  consistent projection built from events — useful when read and write patterns have very
  different shapes/scale needs.
Neither exists in this repo yet (there's effectively no persistence at all today), but they're
exactly what comes up once "database-per-service" stops being a slide and starts being your
actual constraint.

### Deployment strategies: blue-green & canary
**Blue-green**: run two full environments ("blue" = current, "green" = new); switch traffic over
all at once (usually via a router/load balancer flip) once green is verified, keeping blue ready
as an instant rollback. **Canary**: shift a small percentage of real traffic to the new version
first, watch metrics, then gradually increase — catches problems on a subset of users before a
full rollout. Trade-off: blue-green needs 2x infrastructure but gives instant rollback; canary
needs traffic-splitting infrastructure but limits blast radius during the rollout itself.

---

## How to use this file for interview prep

For each concept above, practice answering out loud: (1) what problem does it solve, (2) how is
it implemented here specifically (cite the file), (3) what's the honest trade-off or gap in
*this* implementation, and (4) what would you change to make it production-grade. That last
question is answered, concept by concept, in [ROADMAP.md](ROADMAP.md).
