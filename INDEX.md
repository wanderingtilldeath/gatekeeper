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
  filter: S/gateway/security/JwtAuthFilter (WebFilter)
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

svc-java :8083  E: S/svc_java/SvcJavaApplication
  http: S/svc_java/controller/HealthController  @RequestMapping /api
  schema: R/application.properties, R/bootstrap.properties
  tests: T/svc_java/SvcJavaApplicationTests (contextLoads)

svc-go :8082  E: svc-go/main.go (main)  mod: go.mod (go 1.25.2, root)
  schema: [none]   tests: [none]   ! not in Eureka, not routed by gateway
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
8080 gateway | 8081 auth | 8082 svc-go | 8083 svc-java | 8761 eureka | 8888 config | 6379 redis
```

## Run / test
```
<m>: ./mvnw spring-boot:run | ./mvnw test | ./mvnw test -Dtest=Class#method
go:  go run ./svc-go            (repo root)
order: config-server -> discovery-server -> auth,svc-java -> gateway
```

## Test coverage
Only generated `contextLoads` smoke tests per Java module. [none] for: register/login/validate, JwtAuthFilter, routing, rate limit, svc-go.
