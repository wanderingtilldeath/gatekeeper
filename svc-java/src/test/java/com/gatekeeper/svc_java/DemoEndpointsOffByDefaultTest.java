package com.gatekeeper.svc_java;

import com.gatekeeper.svc_java.controller.DemoController;
import com.gatekeeper.svc_java.controller.HealthController;
import com.gatekeeper.svc_java.security.GatewayAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without demo.endpoints.enabled, /slow and /fail must not exist, even for a caller with the gateway token. */
@WebMvcTest(controllers = {HealthController.class, DemoController.class})
@Import(GatewayAuthFilter.class)
@TestPropertySource(properties = "gateway.shared-secret=test-secret")
class DemoEndpointsOffByDefaultTest {

    @Autowired MockMvc mvc;

    @Test
    void failAndSlowAreNotMapped() throws Exception {
        mvc.perform(get("/api/java/fail").header("X-Gateway-Token", "test-secret")).andExpect(status().isNotFound());
        mvc.perform(post("/api/java/fail").header("X-Gateway-Token", "test-secret")).andExpect(status().isNotFound());
        mvc.perform(get("/api/java/slow").header("X-Gateway-Token", "test-secret")).andExpect(status().isNotFound());
    }
}
