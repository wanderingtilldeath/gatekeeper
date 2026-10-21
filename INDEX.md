# INDEX
Compact map: module/feature -> entry | schema | tests. Paths relative to repo root.
Java pkg roots: `<m>/src/main/java/com/gatekeeper/<pkg>/` (pkg: auth, gateway, config_server, discovery_server, svc_java). Abbrev: `S`=src/main/java/com/gatekeeper, `R`=src/main/resources, `T`=src/test/java/com/gatekeeper.
Notation: `->` calls/routes to, `|` alt, `!` gotcha, `[none]` absent.

## Modules
```
config-server :8888  E: S/config_server/ConfigServerApplication (@EnableConfigServer)
  schema: R/application.properties (profile=native, search=classpath:/configs)
          R/configs/{auth,gateway,src-java}.properties  (name+port only)
  tests: T/config_server/ConfigServerApplicationTests (contextLoads)
  ! src-java.properties != app name svc-java -> not matched

discovery-server :8761  E: S/discovery_server/DiscoveryServerApplication (@EnableEurekaServer)
  schema: R/application.properties (register-with-eureka=false, fetch-registry=false)
  tests: T/discovery_server/DiscoveryServerApplicationTests (contextLoads)

gateway :8080  E: S/gateway/GatewayApplication
  filter: S/gateway/security/JwtAuthFilter (WebFilter) | S/gateway/filter/RequestLogFilter -> Redis incidents:requests (test: RequestLogFilterTest)
  schema: R/application.properties (routes[], redis host/port)
          R/bootstrap.properties (config import + eureka url)
  tests: T/gateway/GatewayApplicationTests (contextLoads)
  deps: spring-cloud-gateway, eureka-client, config, redis-reactive, webflux WebClient

auth :8081  E: S/auth/AuthApplication
  http: S/auth/controller/AuthController  @RequestMapping /auth
  logic: S/auth/service/UserService   jwt: S/auth/jwt/JwtUtil   sec: S/auth/security/SecurityConfig
  schema: S/auth/model/User{username,password}  [POJO, not @Entity]
          R/application.properties, R/bootstrap.properties
  tests: T/auth/AuthApplicationTests (contextLoads)
  deps: web, security, data-jpa, h2, jjwt 0.x, actuator, eureka-client, config

svc-java :8083  E: S/svc_java/SvcJavaApplication (@EnableFeignClients)
  http: controller/HealthController GET /api/java/health | controller/OrderController POST,GET /api/java/orders (needs X-User from gateway)
  logic: service/OrderService (reserve via client/InventoryClient -> publish event/OrderPlaced); config/RabbitConfig (exchange "orders", JSON converter)
  schema: R/application.properties (feign timeouts, resilience4j breaker, rabbitmq), R/bootstrap.properties
  tests: T/svc_java/OrderServiceTest (4 unit), SvcJavaApplicationTests (contextLoads)

svc-go :8082  E: svc-go/main.go  mod: go.mod (go 1.25.2, root)
  http: inventory.go GET /inventory/{sku}, POST /inventory/{sku}/reserve -> {reserved,remaining}; eureka.go REST register + 30s heartbeat
  tests: svc-go/inventory_test.go   ! not routed by gateway (only called by svc-java)

svc-ai :8085  E: S/svc_ai/SvcAiApplication (@ConfigurationPropertiesScan)
  http: AiController /ai: POST chat, GET usage, POST agent/act, GET agent/audit[?status=], POST agent/approvals/{id}/approve|reject (admin only)
  logic: ChatService (Redactor -> Redis cache -> daily budget -> providers in @Order) | ActionService (allow-list -> per-min limit -> risky=PENDING -> audit)
  incident: IncidentService.diagnose (rules: NOT_REGISTERED|ERRORS|SLOW|RATE_LIMITED|AUTH) -> explain via ChatService.callProviders; GET /ai/incident/summary?minutes=, /ai/incident/request/{id} (admin); @Scheduled watch -> INCIDENT event
  schema: AiProps (R/application.properties: ai.incident-rate, ai.incident-slow-ms, ai.daily-tokens, ai.primary-fails, ai.risky, ai.admins, ai.agents.<name>=tools); AiEvent -> exchange "ai"
  tests: T/svc_ai/AiTest (7, fake Redis), IncidentTest (7 rules)   ! providers + tools are fakes; audit is in-memory; tokens ~ chars/4

svc-notify :8084  E: svc_notify/SvcNotifyApplication
  async: OrderListener @RabbitListener orders.notify; RabbitConfig queue+DLQ (orders.notify.dlq), retry 3x (R/application.properties)
  tests: T/svc_notify/OrderListenerTest
```
All Java modules: Java 21 (pom), independent Maven projects, wrapper `<m>/mvnw`. README says 17/Boot 3 ! stale on version.

## Features
```
register    POST /auth/register {username,password} -> UserService.register
            AuthController.register -> ConcurrentHashMap put (in-memory)
login       POST /auth/login {username,password} -> JwtUtil.generateToken (HS256, 1h)
            ! plaintext compare; failure returns 200 "Invalid credentials"
validate    GET /auth/validate?token= -> JwtUtil.validateToken -> username | "Invalid token"
            ! key = Keys.secretKeyFor at startup -> tokens die on restart, per-instance
gw-authn    JwtAuthFilter: skip path ^/auth; else Bearer required
            -> GET http://auth/auth/validate (lb WebClient)
            no header 401 | "Invalid token" or error 403
gw-routing  gateway R/application.properties:
            routes[0] auth     /auth/**      -> lb://auth
            routes[2] svc-ai /ai/** -> lb://svc-ai + RequestRateLimiter 10,20
            routes[1] svc-java /api/java/**  -> lb://svc-java  + RequestRateLimiter 10,20
gw-ratelim  RequestRateLimiter redis-rate-limiter=10,20 ! needs redis localhost:6379
            ! props use spring.redis.* (Boot 3 wants spring.data.redis.*)
health-java GET /api/java/health -> HealthController.health
health-go   GET :8082/health -> inline handler in main.go (direct only)
discovery   every Java svc: eureka.client.service-url.defaultZone=localhost:8761
central-cfg every Java svc: spring.config.import=optional:configserver:localhost:8888
            ! optional -> silent fallback to local props
actuator    auth only: health,info exposed (R/application.properties)
```

## Ports
```
8080 gateway | 8081 auth | 8082 svc-go | 8083 svc-java | 8084 svc-notify | 8085 svc-ai | 8761 eureka | 8888 config | 6379 redis | 5672/15672 rabbitmq
```

## Run / test
```
<m>: ./mvnw spring-boot:run | ./mvnw test | ./mvnw test -Dtest=Class#method
go:  go run ./svc-go            (repo root)
infra: docker compose -f infra/docker-compose.yml up -d
order: config-server -> discovery-server -> auth,svc-go,svc-notify -> svc-java,svc-ai -> gateway
```

## Test coverage
Only generated `contextLoads` smoke tests per Java module. Unit tests: OrderService, OrderListener, inventory reserve. [none] for: register/login/validate, JwtAuthFilter/RequestIdFilter, routing, rate limit, end-to-end (manual only).
