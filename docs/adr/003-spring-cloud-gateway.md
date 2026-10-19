# ADR 003: Spring Cloud Gateway instead of a hand-written proxy

**Status:** accepted

## Context
The edge needs routing, service-discovery lookups, auth, rate limiting, timeouts, retries, circuit breaking and metrics. A custom proxy (for example on Netty or a servlet filter) means writing and testing all of that, including the hard parts: streaming bodies, connection pooling, header handling, backpressure.

## Decision
Use Spring Cloud Gateway (WebFlux/Netty). Routes, filters, Redis rate limiting, Resilience4j breakers, Eureka load balancing and Micrometer metrics come with it. Our own code is small: key resolvers, a global filter that strips and sets identity headers, a JSON fallback controller, and the security configuration.

## Consequences
- Good: less code to own and secure; behaviour is covered by integration tests (WireMock) rather than hand-rolled plumbing.
- Bad: tied to Spring's release train. Boot 3.5 / Spring Cloud 2025.0 is the last open-source line for the 3.x generation; the next step is Spring Cloud 2025.1 with Boot 4. Reactive code is also harder to debug than servlet code.
- Not chosen: Nginx/Envoy as the edge. They are excellent proxies, but this project is about the application-level pieces (JWT claims, per-user limits, Spring service discovery), which are easier to express and test in the same stack.
