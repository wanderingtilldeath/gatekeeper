package com.gatekeeper.svc_java.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/** Resolved by Eureka name (svc-go); the request id is a parameter because the circuit breaker runs calls on another thread. */
@FeignClient(name = "svc-go")
public interface InventoryClient {

    record ReserveRequest(int qty) {}

    record ReserveResponse(boolean reserved, int remaining) {}

    @PostMapping("/inventory/{sku}/reserve")
    ReserveResponse reserve(@PathVariable("sku") String sku,
                            @RequestBody ReserveRequest request,
                            @RequestHeader("X-Request-Id") String requestId);
}
