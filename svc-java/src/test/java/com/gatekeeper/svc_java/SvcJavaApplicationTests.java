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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {HealthController.class, DemoController.class})
@Import(GatewayAuthFilter.class)
@TestPropertySource(properties = {"gateway.shared-secret=test-secret", "demo.endpoints.enabled=true"})
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
                        .header("X-Gateway-Token", "test-secret")
                        .header("X-User-Id", "u-1").header("X-User-Roles", "USER").header("X-Tenant-Id", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u-1"))
                .andExpect(jsonPath("$.roles").value("USER"))
                .andExpect(jsonPath("$.tenantId").value("acme"));
    }

    @Test
    void blankSecretIsRejectedAtStartup() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new GatewayAuthFilter(" "));
    }

    @Test
    void failEndpointReturns500() throws Exception {
        mvc.perform(get("/api/java/fail").header("X-Gateway-Token", "test-secret"))
                .andExpect(status().isInternalServerError());
    }
}
