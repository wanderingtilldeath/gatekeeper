package com.gatekeeper.auth.token;

import com.gatekeeper.auth.domain.RefreshToken;
import com.gatekeeper.auth.domain.RefreshTokenRepository;
import com.gatekeeper.auth.domain.User;
import com.gatekeeper.auth.domain.UserRepository;
import com.gatekeeper.auth.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final SigningKey signingKey;
    private final JwtProperties props;
    private final SecureRandom random = new SecureRandom();
    /** Compared against when the username is unknown, so "no such user" costs as much as "wrong password". */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       SigningKey signingKey, JwtProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.signingKey = signingKey;
        this.props = props;
        this.dummyHash = passwordEncoder.encode("dummy-password");
    }

    /** tenant may be null (falls back to "default"). */
    public User register(String username, String password, String tenant) {
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.conflict("Username already taken");
        }
        try {
            return users.saveAndFlush(new User(username, passwordEncoder.encode(password), "USER", tenant != null ? tenant : "default"));
        } catch (DataIntegrityViolationException e) { // lost a race with a concurrent register
            throw ApiException.conflict("Username already taken");
        }
    }

    public TokenResponse login(String username, String password) {
        User user = users.findByUsername(username).orElse(null);
        boolean ok = passwordEncoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !ok) {
            throw ApiException.unauthorized("Invalid credentials");
        }
        return issue(user, UUID.randomUUID());
    }

    /**
     * Rotates a refresh token. Presenting one that was already used means it leaked, so the whole
     * family is deleted (noRollbackFor keeps that delete when we then answer 401).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawToken) {
        RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (token.isUsed() || refreshTokens.markUsed(token.getId()) == 0) {
            refreshTokens.deleteFamily(token.getFamilyId());
            throw ApiException.unauthorized("Refresh token reuse detected; log in again");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Refresh token expired");
        }
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        return issue(user, token.getFamilyId());
    }

    private TokenResponse issue(User user, UUID familyId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), familyId, sha256(raw), Instant.now().plus(props.refreshTtl())));
        return new TokenResponse(signingKey.accessToken(user), raw, "Bearer", props.accessTtl().toSeconds());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
