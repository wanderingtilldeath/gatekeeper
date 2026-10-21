package main

import (
	"bytes"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

const eurekaURL = "http://localhost:8761/eureka/apps/"

// registerWithEureka registers via Eureka's REST API and heartbeats every 30s so
// Spring services can resolve lb://<name>. Returns a deregister func.
// shortcut: host is hardcoded to localhost, parametrize when containerized.
func registerWithEureka(name string, port int) func() {
	app := strings.ToUpper(name)
	id := fmt.Sprintf("localhost:%s:%d", name, port)
	body := fmt.Sprintf(`{"instance":{"instanceId":%q,"hostName":"localhost","app":%q,"ipAddr":"127.0.0.1",
"vipAddress":%q,"status":"UP","port":{"$":%d,"@enabled":"true"},
"dataCenterInfo":{"@class":"com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo","name":"MyOwn"}}}`,
		id, app, name, port)

	do := func(method, url string, payload string) int {
		req, _ := http.NewRequest(method, url, bytes.NewBufferString(payload))
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			log.Printf("eureka %s: %v", method, err)
			return 0
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	register := func() { do("POST", eurekaURL+app, body) }

	register()
	go func() {
		for range time.Tick(30 * time.Second) {
			if do("PUT", eurekaURL+app+"/"+id, "") == http.StatusNotFound {
				register() // Eureka restarted and forgot us
			}
		}
	}()
	return func() { do("DELETE", eurekaURL+app+"/"+id, "") }
}
