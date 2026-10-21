package main

import (
	"fmt"
	"net/http"
	"os"
	"os/signal"
)

const port = 8082

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("Go service healthy"))
	})
	newInventory(map[string]int{"sku1": 5, "sku2": 2}).routes(mux)

	deregister := registerWithEureka("svc-go", port)
	go func() {
		c := make(chan os.Signal, 1)
		signal.Notify(c, os.Interrupt)
		<-c
		deregister()
		os.Exit(0)
	}()

	fmt.Printf("svc-go running on :%d\n", port)
	http.ListenAndServe(fmt.Sprintf(":%d", port), mux)
}
