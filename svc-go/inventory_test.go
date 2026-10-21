package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func reserve(mux *http.ServeMux, sku, body string) (int, string) {
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest("POST", "/inventory/"+sku+"/reserve", strings.NewReader(body)))
	return rec.Code, rec.Body.String()
}

func TestReserve(t *testing.T) {
	mux := http.NewServeMux()
	newInventory(map[string]int{"a": 2}).routes(mux)

	cases := []struct {
		sku, body, want string
		code            int
	}{
		{"a", `{"qty":2}`, `"reserved":true`, 200},
		{"a", `{"qty":1}`, `"reserved":false`, 200},   // now empty
		{"zzz", `{"qty":1}`, `"reserved":false`, 200}, // unknown sku
		{"a", `{"qty":0}`, "", 400},
	}
	for _, c := range cases {
		code, out := reserve(mux, c.sku, c.body)
		if code != c.code || !strings.Contains(out, c.want) {
			t.Errorf("%s %s: got %d %q", c.sku, c.body, code, out)
		}
	}
}
