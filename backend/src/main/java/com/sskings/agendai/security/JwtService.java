package com.sskings.agendai.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.sskings.agendai.config.AgendaiProperties;
import com.sskings.agendai.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * JWT HS256: sujeito = id do usuário (não o e-mail), issuer fixo, expiração e a versão de token ("ver"). Logout e
 * troca de senha incrementam User.tokenVersion: todos os tokens anteriores param de valer na hora.
 */
@Service
public class JwtService {

    static final String ISSUER = "agendai-api";
    static final String VERSION_CLAIM = "ver";

    private final Algorithm algorithm;
    private final long expirationMinutes;
    private final Clock clock;

    public JwtService(AgendaiProperties props, Clock clock) {
        this(props, clock, true);
    }

    /**
     * @param required {@code false} só nos papéis sem HTTP da API (worker, migrate), que não recebem o segredo: lá a
     *                 chave é sorteada e nenhum token vale. Onde a API roda, segredo ausente impede a partida (várias
     *                 instâncias com chaves sorteadas recusariam os tokens umas das outras).
     */
    @Autowired
    public JwtService(AgendaiProperties props, Clock clock, @Value("${agendai.jwt.required:true}") boolean required) {
        String secret = props.jwt().secret();
        boolean blank = secret == null || secret.isBlank();
        if (blank && required) {
            throw new IllegalStateException("JWT_SECRET: informe o segredo dos access tokens (openssl rand -base64 48)");
        }
        this.algorithm = blank ? Algorithm.HMAC256(randomKey()) : Algorithm.HMAC256(secret);
        this.expirationMinutes = props.jwt().accessMinutes();
        this.clock = clock;
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    public String generate(User user) {
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(user.getId().toString())
                .withClaim(VERSION_CLAIM, user.getTokenVersion())
                .withIssuedAt(clock.instant())
                .withExpiresAt(clock.instant().plus(expirationMinutes, ChronoUnit.MINUTES))
                .sign(algorithm);
    }

    /** Válido → (id, versão); inválido, adulterado, de outro emissor ou expirado (no relógio da aplicação) → null. */
    public Claims validate(String token) {
        try {
            var verification = (JWTVerifier.BaseVerification) JWT.require(algorithm)
                    .withIssuer(ISSUER)
                    .withClaimPresence(VERSION_CLAIM);
            DecodedJWT jwt = verification.build(clock).verify(token);
            return new Claims(UUID.fromString(jwt.getSubject()), jwt.getClaim(VERSION_CLAIM).asInt());
        } catch (JWTVerificationException | IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    public record Claims(UUID userId, int version) {}
}
