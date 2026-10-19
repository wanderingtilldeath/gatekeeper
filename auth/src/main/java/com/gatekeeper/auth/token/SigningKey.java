package com.gatekeeper.auth.token;

import com.gatekeeper.auth.domain.User;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the RS256 key pair: loaded from AUTH_JWT_PRIVATE_KEY (PKCS#8 PEM) or from a key file,
 * generated and written to that file on first start. Signs access tokens and publishes the JWKS.
 */
@Component
public class SigningKey {

    private final RSAKey rsaKey;
    private final JwtEncoder encoder;
    private final JwtProperties props;

    public SigningKey(JwtProperties props) throws Exception {
        this.props = props;
        RSAPrivateCrtKey privateKey = load(props);
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        RSAKey unnamed = new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE).build();
        // kid = RFC 7638 thumbprint, so the same key always gets the same id
        this.rsaKey = new RSAKey.Builder(unnamed).keyID(unnamed.computeThumbprint().toString()).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    public Map<String, Object> jwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    public String accessToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .id(UUID.randomUUID().toString())
                .claim("username", user.getUsername())
                .claim("roles", user.getRoleList())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(rsaKey.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static RSAPrivateCrtKey load(JwtProperties props) throws Exception {
        if (props.privateKey() != null && !props.privateKey().isBlank()) {
            return parse(props.privateKey());
        }
        Path file = props.keyFile();
        if (Files.exists(file)) {
            return parse(Files.readString(file));
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        persist(file, pair);
        return (RSAPrivateCrtKey) pair.getPrivate();
    }

    private static RSAPrivateCrtKey parse(String pem) throws Exception {
        String body = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static void persist(Path file, KeyPair pair) throws IOException {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        Files.writeString(file, pem);
        file.toFile().setReadable(false, false);
        file.toFile().setReadable(true, true);
        file.toFile().setWritable(false, false);
        file.toFile().setWritable(true, true);
    }
}
