package com.gatekeeper.auth.web;

import com.gatekeeper.auth.domain.User;
import com.gatekeeper.auth.token.AuthService;
import com.gatekeeper.auth.token.SigningKey;
import com.gatekeeper.auth.token.TokenResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "letters, digits, _ . - only") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            // shortcut: any caller can pick any tenant at register; real systems assign it via invite or admin
            @Pattern(regexp = "[a-z0-9-]{1,64}", message = "lowercase letters, digits, - only") String tenant) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record RegisterResponse(UUID id, String username) {}

    private final AuthService authService;
    private final SigningKey signingKey;

    public AuthController(AuthService authService, SigningKey signingKey) {
        this.authService = authService;
        this.signingKey = signingKey;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = authService.register(request.username(), request.password(), request.tenant());
        return new RegisterResponse(user.getId(), user.getUsername());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return signingKey.jwks();
    }
}
