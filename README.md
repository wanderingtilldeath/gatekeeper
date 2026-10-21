# GateKeeper

A single front door for microservices: **auth, rate limiting and service discovery handled once, at the gateway.**

<p align="center"><img src="docs/flow.svg" alt="Request flow: green request is authenticated and forwarded, red request is rejected at the gateway" width="800"></p>

## Problem
Every microservice re-implementing JWT checks, throttling and service lookup means duplicated code, inconsistent security, and hardcoded hosts.

## Solution
Spring Cloud Gateway is the only public entry point. It validates the token with the Auth service, rate-limits via Redis, and routes to services by name through Eureka.

## Impact
- Auth written once. Backends (Java and Go) contain zero auth code.
- New service = register with Eureka + one route line.
- Abusive clients capped at 10 req/s (burst 20) before reaching any service.
- Invalid or missing tokens rejected at the edge (401 / 403).
- Config served centrally by Spring Cloud Config.

## Modules
| Module | Port | Role |
|---|---|---|
| gateway | 8080 | Routing, JWT check, rate limit |
| auth | 8081 | Register, login, issue/validate JWT |
| svc-java | 8083 | Sample backend |
| svc-go | 8082 | Sample Go backend |
| discovery-server | 8761 | Eureka |
| config-server | 8888 | Central config |

## Run
Start in order (`./mvnw spring-boot:run` in each): config-server, discovery-server, auth + svc-java, gateway. Redis on `:6379`.

```bash
curl -XPOST localhost:8080/auth/register -H 'Content-Type: application/json' -d '{"username":"u","password":"p"}'
TOKEN=$(curl -s -XPOST localhost:8080/auth/login -H 'Content-Type: application/json' -d '{"username":"u","password":"p"}')
curl localhost:8080/api/java/health -H "Authorization: Bearer $TOKEN"   # 200
curl -i localhost:8080/api/java/health                                   # 401
```

**Stack:** Java 17, Spring Boot 3, Spring Cloud (Gateway, Eureka, Config), Redis, Go.
