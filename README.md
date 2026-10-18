# GateKeeper - Advanced API Gateway Project

## Services Overview
- **discovery-server** → Eureka service registry
- **config-server** → Spring Cloud Config Server (native/classpath-backed)
- **gateway** → Spring Cloud Gateway (routes, rate limiting, auth)
- **auth** → Authentication microservice (JWT + user management)
- **svc-java** → Example backend (Spring Boot)
- **svc-go** → Example backend (Go)
- **docs** → [concepts.md](docs/concepts.md) (system design concepts explained, interview-prep style) and [ROADMAP.md](docs/ROADMAP.md) (what's left to reach production-grade)

### Tech Stack
Java 21, Go 1.25, Spring Boot 3.4, Spring Cloud, Redis, Docker / Docker Compose.
Prometheus, Grafana, and ELK are on the [roadmap](docs/ROADMAP.md) — not wired up yet.

## Running locally with Docker

```bash
docker compose up -d --build
```

This builds and starts all six services plus Redis, in the right order (discovery-server and
config-server come up first, then auth, then the gateway and the backend services), waiting on
each dependency's healthcheck before starting the next.

Once everything is healthy (`docker compose ps`), try the flow through the gateway:

```bash
# Register a user
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"pass1234"}'

# Log in to get a JWT
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"pass1234"}'

# Call a protected backend route through the gateway (replace <token>)
curl http://localhost:8080/api/java/health -H "Authorization: Bearer <token>"
```

The Eureka dashboard is at http://localhost:8761. To stop everything: `docker compose down`.