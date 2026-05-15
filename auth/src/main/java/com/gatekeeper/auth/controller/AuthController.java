package com.gatekeeper.auth.controller;

import com.gatekeeper.auth.dto.AuthResponseDto;
import com.gatekeeper.auth.dto.LoginRequestDto;
import com.gatekeeper.auth.dto.RegisterRequestDto;
import com.gatekeeper.auth.jwt.JwtUtil;
import com.gatekeeper.auth.entity.User;
import com.gatekeeper.auth.service.UserService;
import jakarta.validation.Valid;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@EnableDiscoveryClient
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, JwtUtil jwtUtil, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder=passwordEncoder;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        userService.register(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(new AuthResponseDto(null, "User registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        User stored = userService.findByUsername(request.getUsername());
        if (stored != null && passwordEncoder.matches(request.getPassword(), stored.getPassword())) {
            String token = jwtUtil.generateToken(request.getUsername());
            return ResponseEntity.ok(new AuthResponseDto(token, "Login successful"));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new AuthResponseDto(null, "Invalid credentials"));
    }

    @GetMapping("/validate")
    public ResponseEntity<String> validate(@RequestParam String token) {
        String username = jwtUtil.validateToken(token);
        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("");
        }
        return ResponseEntity.ok(username);
    }

}
