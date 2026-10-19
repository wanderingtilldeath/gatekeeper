package com.gatekeeper.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatekeeper.auth.token.JwtProperties;
import com.gatekeeper.auth.token.SigningKey;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SigningKey signingKey;

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String creds(String user, String pass) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}";
    }

    private String newUser() throws Exception {
        String name = "u" + UUID.randomUUID().toString().substring(0, 8);
        postJson("/auth/register", creds(name, "pass1234")).andExpect(status().isCreated());
        return name;
    }

    private JsonNode login(String user) throws Exception {
        String body = postJson("/auth/login", creds(user, "pass1234")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void registerRejectsDuplicateUsernameInsteadOfOverwriting() throws Exception {
        String name = newUser();
        postJson("/auth/register", creds(name, "another-password")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
        // the original password still works
        postJson("/auth/login", creds(name, "pass1234")).andExpect(status().isOk());
        postJson("/auth/login", creds(name, "another-password")).andExpect(status().isUnauthorized());
    }

    @Test
    void registerValidatesInput() throws Exception {
        postJson("/auth/register", creds("", "pass1234")).andExpect(status().isBadRequest());
        postJson("/auth/register", creds("alice", "short")).andExpect(status().isBadRequest());
        postJson("/auth/register", "not json").andExpect(status().isBadRequest());
    }

    @Test
    void loginFailsForWrongPasswordAndUnknownUser() throws Exception {
        String name = newUser();
        postJson("/auth/login", creds(name, "wrong-password")).andExpect(status().isUnauthorized());
        postJson("/auth/login", creds("nobody-here", "pass1234")).andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenVerifiesAgainstPublishedJwks() throws Exception {
        String name = newUser();
        String access = login(name).get("accessToken").asText();

        String jwks = mvc.perform(get("/auth/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var key = JWKSet.parse(jwks).getKeys().get(0).toRSAKey();
        assertThat(jwks).doesNotContain("\"d\"").doesNotContain("\"p\""); // public part only

        Jwt jwt = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build().decode(access);
        assertThat(jwt.getHeaders()).containsEntry("kid", key.getKeyID()).containsEntry("alg", "RS256");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://gatekeeper.local");
        assertThat(jwt.<java.util.List<String>>getClaim("roles")).containsExactly("USER");
        assertThat(jwt.getClaimAsString("username")).isEqualTo(name);
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void refreshRotatesAndReuseRevokesTheFamily() throws Exception {
        String name = newUser();
        String first = login(name).get("refreshToken").asText();

        String body = postJson("/auth/refresh", "{\"refreshToken\":\"" + first + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = json.readTree(body).get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);

        // replaying the old token is theft: 401, and the newer token from the same family dies too
        postJson("/auth/refresh", "{\"refreshToken\":\"" + first + "\"}").andExpect(status().isUnauthorized());
        postJson("/auth/refresh", "{\"refreshToken\":\"" + second + "\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRejectsUnknownToken() throws Exception {
        postJson("/auth/refresh", "{\"refreshToken\":\"made-up\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void validateEndpointIsGoneAndUnknownRoutesAreDenied() throws Exception {
        mvc.perform(get("/auth/validate").param("token", "x")).andExpect(status().is4xxClientError());
        mvc.perform(get("/auth/anything-else")).andExpect(status().is4xxClientError());
    }

    @Test
    void keyIsPersistedSoTokensSurviveRestart() throws Exception {
        Path file = Path.of("target/test-jwt-key.pem");
        assertThat(file).exists();
        SigningKey restarted = new SigningKey(new JwtProperties("", file, "https://gatekeeper.local",
                Duration.ofMinutes(15), Duration.ofDays(7)));
        assertThat(restarted.jwks()).isEqualTo(signingKey.jwks());
        assertThat(Files.readString(file)).startsWith("-----BEGIN PRIVATE KEY-----");
    }
}
