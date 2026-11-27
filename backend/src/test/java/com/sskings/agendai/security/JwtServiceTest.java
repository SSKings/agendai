package com.sskings.agendai.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sskings.agendai.config.AgendaiProperties;
import com.sskings.agendai.support.MutableClock;
import com.sskings.agendai.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    static final String SECRET = "test-secret-only-for-tests-0123456789-abcdefghijklmnopqrstuvwxyz";
    private final MutableClock clock = new MutableClock();
    private final JwtService jwt = new JwtService(new AgendaiProperties(new AgendaiProperties.Jwt(SECRET, 15),
            new AgendaiProperties.Refresh(30, true), new AgendaiProperties.Login(5, 15), new AgendaiProperties.RateLimit(20, 120, 1200),
            65536, new AgendaiProperties.Cors(List.of())), clock);

    @Test
    @DisplayName("Ida e volta: o token traz o id e a versão do usuário; vale 15 min no relógio da aplicação")
    void roundTrip() {
        User u = new User("Ana", "ana@x.com", "hash", Instant.now());
        u.revokeTokens();
        String token = jwt.generate(u);
        assertThat(token).isNotBlank();
        JwtService.Claims c = jwt.validate(token);
        assertThat(c.userId()).isEqualTo(u.getId());
        assertThat(c.version()).isEqualTo(1);
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertThat(jwt.validate(token).userId()).as("1 s antes de vencer").isEqualTo(u.getId());
        clock.advance(Duration.ofSeconds(2));
        assertThat(jwt.validate(token)).as("1 s depois de vencer").isNull();
    }

    @Test
    @DisplayName("Recusa: adulterado, outro segredo, outro emissor, sem versão, sujeito que não é UUID, lixo")
    void rejects() {
        User u = new User("Ana", "ana@x.com", "hash", Instant.now());
        String good = jwt.generate(u);
        Algorithm real = Algorithm.HMAC256(SECRET);
        assertThat(jwt.validate(good.substring(0, good.length() - 2) + "xx")).isNull();
        assertThat(jwt.validate(JWT.create().withIssuer("agendai-api").withSubject(u.getId().toString()).withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(60)).sign(Algorithm.HMAC256("outro-segredo-0123456789-abcdefghijkl")))).isNull();
        assertThat(jwt.validate(JWT.create().withIssuer("shopeasy").withSubject(u.getId().toString()).withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(60)).sign(real))).isNull();
        assertThat(jwt.validate(JWT.create().withIssuer("agendai-api").withSubject(u.getId().toString())
                .withExpiresAt(clock.instant().plusSeconds(60)).sign(real))).isNull();
        assertThat(jwt.validate(JWT.create().withIssuer("agendai-api").withSubject("admin").withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(60)).sign(real))).isNull();
        assertThat(jwt.validate("abc")).isNull();
    }

    static AgendaiProperties props(String secret) {
        return new AgendaiProperties(new AgendaiProperties.Jwt(secret, 15), new AgendaiProperties.Refresh(30, true),
                new AgendaiProperties.Login(5, 15), new AgendaiProperties.RateLimit(20, 120, 1200), 65536, new AgendaiProperties.Cors(List.of()));
    }

    @Test
    @DisplayName("Onde a API roda, sem segredo não sobe (instâncias com chaves sorteadas recusariam os tokens umas das outras)")
    void blankSecretRequired() {
        for (String blank : new String[]{null, "", "  "}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtService(props(blank), clock))
                    .as(String.valueOf(blank)).isInstanceOf(IllegalStateException.class).hasMessageStartingWith("JWT_SECRET");
        }
        assertThat(new JwtService(props(SECRET), clock, false).validate(jwt.generate(new User("Ana", "ana@x.com", "hash", clock.instant()))))
                .as("com segredo, não exigir não muda nada").isNotNull();
    }

    @Test
    @DisplayName("Papel sem a API (worker, migrate): chave sorteada — token da API e o de outro processo não valem")
    void blankSecretRandomKey() {
        for (String blank : new String[]{null, "", "  "}) {
            JwtService noSecret = new JwtService(props(blank), clock, false);
            User u = new User("Ana", "ana@x.com", "hash", clock.instant());
            assertThat(noSecret.validate(noSecret.generate(u))).as("o próprio processo ainda funciona").isNotNull();
            assertThat(noSecret.validate(jwt.generate(u))).as("token da API (outro segredo): " + blank).isNull();
            assertThat(new JwtService(props(blank), clock, false).validate(noSecret.generate(u))).as("cada processo sorteia a sua").isNull();
        }
    }
}
