# Build context for this Dockerfile is the REPO ROOT (see docker-compose.yml),
# because go.mod lives at the repo root while main.go lives in svc-go/.

# ---- Build stage ----
FROM golang:1.25 AS build
WORKDIR /app
COPY go.mod ./
RUN go mod download
COPY svc-go/main.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -o svc-go .

# ---- Runtime stage ----
FROM alpine:3.20
RUN apk add --no-cache curl
RUN adduser -S app
WORKDIR /app
COPY --from=build /app/svc-go .
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s --retries=5 \
    CMD curl -f http://localhost:8082/health || exit 1
USER app
ENTRYPOINT ["./svc-go"]
