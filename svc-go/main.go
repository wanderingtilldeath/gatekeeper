package main

import (
	"crypto/subtle"
	"encoding/json"
	"log"
	"net/http"
	"os"
)

// gatewaySecret has no default on purpose: a default would be public (it is in the repo).
func gatewaySecret() string {
	s := os.Getenv("GATEWAY_SECRET")
	if s == "" {
		log.Fatal("GATEWAY_SECRET is not set; refusing to start")
	}
	return s
}

// requireGateway rejects requests that did not come through the gateway (it adds X-Gateway-Token).
func requireGateway(secret string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-Gateway-Token")
		if subtle.ConstantTimeCompare([]byte(got), []byte(secret)) != 1 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			w.Write([]byte(`{"error":"Forbidden","message":"Direct access is not allowed"}`))
			return
		}
		next(w, r)
	}
}

// whoami echoes the identity headers the gateway set, proving they were propagated.
func whoami(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"service":   "svc-go",
		"userId":    r.Header.Get("X-User-Id"),
		"roles":     r.Header.Get("X-User-Roles"),
		"requestId": r.Header.Get("X-Request-Id"),
	})
}

func newMux(secret string) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("Go service healthy"))
	})
	mux.HandleFunc("/api/go/whoami", requireGateway(secret, whoami))
	return mux
}

func main() {
	log.Println("svc-go running on :8082")
	log.Fatal(http.ListenAndServe(":8082", newMux(gatewaySecret())))
}
