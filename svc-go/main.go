package main

import (
    "fmt"
    "net/http"
)

func main() {
    http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
        w.Write([]byte("Go service healthy"))
    })
    fmt.Println("svc-go running on :8082")
    http.ListenAndServe(":8082", nil)
}
