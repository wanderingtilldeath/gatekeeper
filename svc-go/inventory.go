package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
)

// inventory is the stock service the order service calls synchronously.
type inventory struct {
	mu    sync.Mutex
	stock map[string]int
}

func newInventory(seed map[string]int) *inventory { return &inventory{stock: seed} }

func (i *inventory) routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /inventory/{sku}", i.get)
	mux.HandleFunc("POST /inventory/{sku}/reserve", i.reserve)
}

func (i *inventory) get(w http.ResponseWriter, r *http.Request) {
	i.mu.Lock()
	n := i.stock[r.PathValue("sku")]
	i.mu.Unlock()
	json.NewEncoder(w).Encode(map[string]int{"remaining": n})
}

// reserve answers 200 {"reserved":false} for out of stock or unknown sku, so
// a business "no" is not counted as a failure by the caller's circuit breaker.
func (i *inventory) reserve(w http.ResponseWriter, r *http.Request) {
	var req struct{ Qty int }
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Qty <= 0 {
		http.Error(w, "qty must be positive", http.StatusBadRequest)
		return
	}
	sku := r.PathValue("sku")

	i.mu.Lock()
	ok := i.stock[sku] >= req.Qty
	if ok {
		i.stock[sku] -= req.Qty
	}
	left := i.stock[sku]
	i.mu.Unlock()

	log.Printf("[%s] reserve sku=%s qty=%d reserved=%t remaining=%d", r.Header.Get("X-Request-Id"), sku, req.Qty, ok, left)
	json.NewEncoder(w).Encode(map[string]any{"reserved": ok, "remaining": left})
}
