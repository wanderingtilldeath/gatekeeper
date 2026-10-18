package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWhoamiRequiresGatewayTokenAndEchoesIdentity(t *testing.T) {
	mux := newMux("s3cret")

	direct := httptest.NewRecorder()
	mux.ServeHTTP(direct, httptest.NewRequest("GET", "/api/go/whoami", nil))
	if direct.Code != http.StatusForbidden {
		t.Fatalf("direct access: got %d, want 403", direct.Code)
	}

	req := httptest.NewRequest("GET", "/api/go/whoami", nil)
	req.Header.Set("X-Gateway-Token", "s3cret")
	req.Header.Set("X-User-Id", "u-1")
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), `"userId":"u-1"`) {
		t.Fatalf("got %d %s", rec.Code, rec.Body.String())
	}
}

func TestHealthIsOpen(t *testing.T) {
	rec := httptest.NewRecorder()
	newMux("s3cret").ServeHTTP(rec, httptest.NewRequest("GET", "/health", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("got %d", rec.Code)
	}
}
