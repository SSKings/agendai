package com.sskings.agendai.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class SecurityHardeningIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("Respostas da API: CSP sem nada, nosniff, sem frame, sem referrer e sem cache")
    void securityHeaders() throws Exception {
        Session s = register();
        MockHttpServletResponse r = call(get("/api/me"), s.token(), null);
        assertThat(r.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(r.getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("CORS: só a origem configurada; outra origem não recebe permissão")
    void cors() throws Exception {
        MockHttpServletResponse ok = call(options("/api/me").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"), null, null);
        assertThat(ok.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo("http://localhost:5173");
        // credenciais só por causa do cookie do refresh (HttpOnly, SameSite=Strict), e só para a origem configurada
        assertThat(ok.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");

        MockHttpServletResponse evil = call(options("/api/me").header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"), null, null);
        assertThat(evil.getStatus()).isEqualTo(403);
        assertThat(evil.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    @DisplayName("Corpo maior que 64 KB → 413, com ou sem Content-Length")
    void requestSizeLimit() throws Exception {
        String big = "{\"name\":\"" + "a".repeat(70_000) + "\",\"email\":\"a@b.com\",\"password\":\"x\"}";
        assertThat(call(post("/api/auth/register"), null, big).getStatus()).isEqualTo(413);
    }

    @Test
    @DisplayName("Tokens forjados: alg none, outro segredo, outro emissor, sem versão, usuário inexistente → 401")
    void forgedTokens() throws Exception {
        Session s = register();
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"agendai-api\",\"sub\":\"" + s.userId() + "\",\"ver\":0}").getBytes(StandardCharsets.UTF_8));
        String none = b64 + "." + payload + ".";
        String otherSecret = JWT.create().withIssuer("agendai-api").withSubject(s.userId()).withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(600)).sign(Algorithm.HMAC256("outro-segredo-qualquer-0123456789-abcdefghij"));
        Algorithm real = Algorithm.HMAC256("test-secret-only-for-tests-0123456789-abcdefghijklmnopqrstuvwxyz");
        String otherIssuer = JWT.create().withIssuer("shopeasy").withSubject(s.userId()).withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(600)).sign(real);
        String noVersion = JWT.create().withIssuer("agendai-api").withSubject(s.userId())
                .withExpiresAt(clock.instant().plusSeconds(600)).sign(real);
        String ghost = JWT.create().withIssuer("agendai-api").withSubject("00000000-0000-0000-0000-000000000000").withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(600)).sign(real);
        String notUuid = JWT.create().withIssuer("agendai-api").withSubject("admin").withClaim("ver", 0)
                .withExpiresAt(clock.instant().plusSeconds(600)).sign(real);
        for (String t : new String[]{none, otherSecret, otherIssuer, noVersion, ghost, notUuid, "abc", ""}) {
            assertThat(call(get("/api/me"), t, null).getStatus()).as(t).isEqualTo(401);
        }
        assertThat(call(get("/api/me").header("Authorization", "Basic YTpi"), null, null).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Actuator: só o health, sem detalhes; o resto não existe para quem está de fora")
    void actuator() throws Exception {
        MockHttpServletResponse health = call(get("/actuator/health"), null, null);
        assertThat(health.getStatus()).isEqualTo(200);
        assertThat(health.getContentAsString()).isEqualTo("{\"status\":\"UP\"}");
        for (String p : new String[]{"/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator"}) {
            assertThat(call(get(p), null, null).getStatus()).as(p).isEqualTo(401);
        }
        Session s = register();
        for (String p : new String[]{"/actuator/env", "/actuator/heapdump"}) {
            assertThat(call(get(p), s.token(), null).getStatus()).as(p + " logado").isEqualTo(404);
        }
    }

    @Test
    @DisplayName("Documentação da API (Swagger) desligada fora do perfil dev")
    void docsDisabledOutsideDev() throws Exception {
        assertThat(call(get("/v3/api-docs"), null, null).getStatus()).isEqualTo(401);
        Session s = register();
        assertThat(call(get("/v3/api-docs"), s.token(), null).getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Erros não vazam detalhe interno: rota inexistente, método, JSON quebrado, tipo de mídia")
    void errorsDoNotLeak() throws Exception {
        Session s = register();
        MockHttpServletResponse missing = call(get("/api/nao-existe"), s.token(), null);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(missing.getContentAsString()).isEqualTo("{\"status\":404,\"message\":\"Resource not found\",\"errors\":null}");
        MockHttpServletResponse method = call(patch("/api/me"), s.token(), null);
        assertThat(method.getStatus()).isEqualTo(405);
        assertThat(method.getContentAsString()).isEqualTo("{\"status\":405,\"message\":\"Method not allowed\",\"errors\":null}");
        MockHttpServletResponse broken = call(post("/api/auth/login"), null, "{\"email\": ");
        assertThat(broken.getStatus()).isEqualTo(400);
        assertThat(broken.getContentAsString()).isEqualTo("{\"status\":400,\"message\":\"Malformed JSON request\",\"errors\":null}");
        MockHttpServletResponse media = call(post("/api/auth/login").contentType("text/plain").content("x"), null, null);
        assertThat(media.getStatus()).isEqualTo(415);
        assertThat(media.getContentAsString()).isEqualTo("{\"status\":415,\"message\":\"Unsupported media type\",\"errors\":null}");
        for (MockHttpServletResponse r : new MockHttpServletResponse[]{missing, method, broken, media}) {
            assertThat(r.getContentAsString()).doesNotContain("Exception", "org.", "SQL", "at com.");
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("Sem o usuário padrão do Spring (\"user\" com senha gerada no log): o login é só o do Agendaí")
    void noDefaultUser() {
        assertThat(context.getBeanNamesForType(org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
    }

    @Test
    @DisplayName("Rotas exigem login (exceto cadastro, login e health)")
    void authenticationRequired() throws Exception {
        for (String p : new String[]{"/api/me", "/api/tenants/00000000-0000-0000-0000-000000000000"}) {
            MockHttpServletResponse r = call(get(p), null, null);
            assertThat(r.getStatus()).as(p).isEqualTo(401);
            assertThat(r.getContentAsString()).contains("Authentication required");
        }
        assertThat(call(post("/api/auth/logout"), null, null).getStatus()).isEqualTo(401);
        assertThat(call(put("/api/me/password"), null, Map.of("currentPassword", "x", "newPassword", "y")).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Autenticação presente mas não confirmada: 403 genérico do tratador de acesso negado, sem detalhe")
    void accessDeniedHandler() throws Exception {
        var context = new org.springframework.security.core.context.SecurityContextImpl(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("alguém", null));
        MockHttpServletResponse r = call(get("/api/me").requestAttr(
                org.springframework.security.web.context.RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, context), null, null);
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains("\"message\":\"Access denied\"").doesNotContain("alguém");
    }

    @Autowired private org.testcontainers.postgresql.PostgreSQLContainer postgres;

    @Test
    @DisplayName("Trava presa por outra transação: a escrita desiste em ~3 s com 503 (lock_timeout), sem segurar a conexão")
    void lockTimeout() throws Exception {
        Session ana = register();
        try (var other = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "agendai_owner", "owner-test")) {
            other.setAutoCommit(false);
            try (var st = other.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                st.setString(1, ana.tenantId());
                st.execute();
            }
            long t0 = System.nanoTime();
            MockHttpServletResponse r = call(post("/api/tenants/" + ana.tenantId() + "/services"), ana.token(),
                    Map.of("name", "Corte", "durationMinutes", 30, "bufferMinutes", 0, "priceCents", 0, "active", true));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(503);
            assertThat(r.getContentAsString()).contains("Busy, try again");
            assertThat(ms).isBetween(2_500L, 8_000L);
            other.rollback();
        }
        assertThat(call(post("/api/tenants/" + ana.tenantId() + "/services"), ana.token(),
                Map.of("name", "Corte", "durationMinutes", 30, "bufferMinutes", 0, "priceCents", 0, "active", true)).getStatus())
                .as("trava solta: volta ao normal").isEqualTo(201);
    }
}
