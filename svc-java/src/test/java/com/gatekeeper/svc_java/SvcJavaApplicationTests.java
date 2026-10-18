package com.gatekeeper.svc_java;

import com.gatekeeper.svc_java.controller.HealthController;
import com.gatekeeper.svc_java.security.GatewayAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(GatewayAuthFilter.class)
class SvcJavaApplicationTests {

    @Autowired MockMvc mvc;

    @Test
    void directAccessWithoutGatewayTokenIsForbidden() throws Exception {
        mvc.perform(get("/api/java/hello")).andExpect(status().isForbidden());
        mvc.perform(get("/api/java/hello").header("X-Gateway-Token", "wrong")).andExpect(status().isForbidden());
    }

    @Test
    void whoamiEchoesGatewayHeaders() throws Exception {
        mvc.perform(get("/api/java/whoami")
                        .header("X-Gateway-Token", "dev-only-gateway-secret")
                        .header("X-User-Id", "u-1").header("X-User-Roles", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-1"))
                .andExpect(jsonPath("$.roles").value("USER"));
    }

    @Test
    void failEndpointReturns500() throws Exception {
        mvc.perform(get("/api/java/fail").header("X-Gateway-Token", "dev-only-gateway-secret"))
                .andExpect(status().isInternalServerError());
    }
}
