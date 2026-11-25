package com.sskings.agendai.auth;

import com.sskings.agendai.security.PasswordStrength;
import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Cadastro, login, sessão com refresh rotativo, logout e troca de senha (docs/seguranca.md, ADR-005). */
class AuthIntegrationTest extends IntegrationTest {

    private Map<String, Object> registerBody(String email, String password) {
        return new HashMap<>(Map.of("name", "Ana Souza", "email", email, "password", password, "businessName", "Salão da Ana"));
    }

    private MockHttpServletResponse login(String email, String password, String ip) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("email", email, "password", password), ip);
    }

    @Test
    @DisplayName("Cadastro: cria o dono e o estabelecimento (link a partir do nome), devolve o access token e o refresh em cookie seguro")
    void registerCreatesOwnerAndTenant() throws Exception {
        String email = email();
        MockHttpServletResponse r = call(post("/api/auth/register"), null, registerBody("  " + email.toUpperCase() + " ", PASSWORD));
        assertThat(r.getStatus()).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("accessToken").asString()).isNotBlank();
        assertThat(b.get("user").get("email").asString()).isEqualTo(email);
        assertThat(b.has("refreshToken")).as("o refresh nunca vai no corpo").isFalse();
        String cookie = r.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cookie).contains("agendai_refresh=").contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/auth").contains("Max-Age=2592000");

        JsonNode me = body(call(get("/api/me"), b.get("accessToken").asString(), null));
        assertThat(me.get("tenants")).hasSize(1);
        assertThat(me.get("tenants").get(0).get("name").asString()).isEqualTo("Salão da Ana");
        assertThat(me.get("tenants").get(0).get("slug").asString()).startsWith("salao-da-ana");
        assertThat(me.get("tenants").get(0).get("role").asString()).isEqualTo("OWNER");
        assertThat(me.get("tenants").get(0).get("timeZone").asString()).isEqualTo("America/Sao_Paulo");
    }

    @Test
    @DisplayName("Link público único entre estabelecimentos: o segundo com o mesmo nome ganha -2, o terceiro -3")
    void uniqueSlug() throws Exception {
        String name = "Estúdio Único " + java.util.UUID.randomUUID().toString().substring(0, 6);
        String[] slugs = new String[3];
        for (int i = 0; i < 3; i++) {
            Session s = register("Dono " + i, email(), name);
            slugs[i] = body(call(get("/api/me"), s.token(), null)).get("tenants").get(0).get("slug").asString();
        }
        assertThat(slugs[1]).isEqualTo(slugs[0] + "-2");
        assertThat(slugs[2]).isEqualTo(slugs[0] + "-3");
    }

    @Test
    @DisplayName("Cadastro recusa e-mail repetido, senha fraca (comum, disfarçada ou com o nome do app) e campo extra")
    void registerValidation() throws Exception {
        String email = email();
        assertThat(call(post("/api/auth/register"), null, registerBody(email, PASSWORD)).getStatus()).isEqualTo(201);
        MockHttpServletResponse dup = call(post("/api/auth/register"), null, registerBody(email, PASSWORD));
        assertThat(dup.getStatus()).isEqualTo(409);
        assertThat(body(dup).get("message").asString()).isEqualTo("Email already in use");
        for (String weak : new String[]{"password1", "99s3nh499"}) {
            assertError(call(post("/api/auth/register"), null, registerBody(email(), weak)), 400, PasswordStrength.TOO_COMMON);
        }
        for (String personal : new String[]{"Agendai2026x", "anasouza2026"}) {
            assertError(call(post("/api/auth/register"), null, registerBody(email(), personal)), 400, PasswordStrength.PERSONAL);
        }
        Map<String, Object> extra = registerBody(email(), PASSWORD);
        extra.put("role", "OWNER");
        assertError(call(post("/api/auth/register"), null, extra), 400, "Malformed JSON request");
        Map<String, Object> noEmail = registerBody(email(), PASSWORD);
        noEmail.remove("email");
        MockHttpServletResponse missing = call(post("/api/auth/register"), null, noEmail);
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(body(missing).get("message").asString()).as("campo faltando é validação, não JSON quebrado").isEqualTo("Validation failed");
        for (String bad : new String[]{"Ana\nINFO falso no log", "Bar\u0000bearia", "Ana\u2028Souza"}) {
            Map<String, Object> control = registerBody(email(), PASSWORD);
            control.put("businessName", bad);
            MockHttpServletResponse r = call(post("/api/auth/register"), null, control);
            assertThat(r.getStatus()).as("caractere de controle").isEqualTo(400);
            assertThat(body(r).get("errors").get(0).asString()).isEqualTo("businessName: must not contain control characters");
        }
        Map<String, Object> noBusiness = registerBody(email(), PASSWORD);
        noBusiness.put("businessName", " ");
        MockHttpServletResponse blank = call(post("/api/auth/register"), null, noBusiness);
        assertError(blank, 400, "Validation failed");
        assertThat(body(blank).get("errors").get(0).asString()).startsWith("businessName: ");
    }

    @Test
    @DisplayName("Login: mesma resposta para senha errada e e-mail inexistente; certo devolve token e cookie")
    void login() throws Exception {
        Session s = register();
        MockHttpServletResponse wrong = login(s.email(), "Errada12345", ip());
        MockHttpServletResponse ghost = login(email(), "Errada12345", ip());
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(wrong.getContentAsString()).isEqualTo(ghost.getContentAsString());
        MockHttpServletResponse ok = login(s.email().toUpperCase(), PASSWORD, ip());
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(refreshCookie(ok)).isNotBlank();
        assertThat(call(get("/api/me"), body(ok).get("accessToken").asString(), null).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Bloqueio progressivo: 5 falhas → 15 min (nem a senha certa entra); o 2º bloqueio seguido dura 30 min; acertar zera")
    void lockout() throws Exception {
        Session s = register();
        for (int i = 0; i < 5; i++) assertThat(login(s.email(), "Errada12345", ip()).getStatus()).isEqualTo(401);
        assertError(login(s.email(), PASSWORD, ip()), 429, AuthService.LOCKED);
        clock.advance(Duration.ofMinutes(16));
        for (int i = 0; i < 5; i++) login(s.email(), "Errada12345", ip());
        clock.advance(Duration.ofMinutes(16));
        assertThat(login(s.email(), PASSWORD, ip()).getStatus()).as("2º bloqueio: 30 min").isEqualTo(429);
        clock.advance(Duration.ofMinutes(15));
        assertThat(login(s.email(), PASSWORD, ip()).getStatus()).isEqualTo(200);
        for (int i = 0; i < 4; i++) login(s.email(), "Errada12345", ip());
        assertThat(login(s.email(), PASSWORD, ip()).getStatus()).isEqualTo(200);
        for (int i = 0; i < 4; i++) login(s.email(), "Errada12345", ip());
        assertThat(login(s.email(), PASSWORD, ip()).getStatus()).as("acerto zerou as falhas (4 + 4 não bloqueia)").isEqualTo(200);
        for (int i = 0; i < 5; i++) login(s.email(), "Errada12345", ip());
        clock.advance(Duration.ofMinutes(16));
        login(s.email(), "Errada12345", ip());
        assertThat(login(s.email(), PASSWORD, ip()).getStatus())
                .as("acerto zerou a escada (bloqueio de novo em 15 min) e o bloqueio vencido recomeça a contagem de falhas")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Bloqueio nunca passa de 24 h, mesmo depois de muitos bloqueios seguidos")
    void lockoutCap() throws Exception {
        Session s = register();
        // 7 bloqueios (15 min, 30 min, ... 16 h), cada um esperado até o fim
        for (int round = 0; round < 7; round++) {
            for (int i = 0; i < 5; i++) login(s.email(), "Errada12345", ip());
            clock.advance(Duration.ofHours(24).plusMinutes(1));
        }
        // o 8º dobraria para 32 h: fica em 24 h
        for (int i = 0; i < 5; i++) login(s.email(), "Errada12345", ip());
        clock.advance(Duration.ofHours(23).plusMinutes(59));
        assertError(login(s.email(), PASSWORD, ip()), 429, AuthService.LOCKED);   // ainda bloqueado antes de 24 h
        clock.advance(Duration.ofMinutes(2));
        assertThat(login(s.email(), PASSWORD, ip()).getStatus()).as("liberado depois de 24 h").isEqualTo(200);
    }

    @Test
    @DisplayName("Refresh: troca o token (o novo funciona, o cookie muda) e exige o cabeçalho próprio")
    void refreshRotates() throws Exception {
        Session s = register();
        MockHttpServletResponse noHeader = call(post("/api/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("agendai_refresh", s.refresh())), null, null);
        assertThat(noHeader.getStatus()).isEqualTo(403);
        MockHttpServletResponse r = refresh(s.refresh());
        assertThat(r.getStatus()).isEqualTo(200);
        String next = refreshCookie(r);
        assertThat(next).isNotBlank().isNotEqualTo(s.refresh());
        assertThat(call(get("/api/me"), body(r).get("accessToken").asString(), null).getStatus()).isEqualTo(200);
        assertThat(refresh(next).getStatus()).isEqualTo(200);
        assertThat(refresh(null).getStatus()).isEqualTo(401);
        assertThat(refresh("nao-e-um-token").getStatus()).isEqualTo(401);
        assertThat(refresh("A".repeat(43)).getStatus()).as("bem formado, mas inexistente").isEqualTo(401);
    }

    @Test
    @DisplayName("Reuso de refresh já trocado (roubo): 401 e a família inteira é revogada — nem o token novo vale mais")
    void refreshReuseRevokesFamily() throws Exception {
        Session s = register();
        String stolen = s.refresh();
        String legit = refreshCookie(refresh(stolen));
        assertThat(refresh(stolen).getStatus()).isEqualTo(401);
        assertThat(refresh(legit).getStatus()).as("família revogada").isEqualTo(401);
        MockHttpServletResponse other = login(s.email(), PASSWORD, ip());
        assertThat(refresh(refreshCookie(other)).getStatus()).as("outro login (outra família) continua").isEqualTo(200);
    }

    @Test
    @DisplayName("Access token vence em 15 min; refresh vence em 30 dias")
    void expiry() throws Exception {
        Session s = register();
        clock.advance(Duration.ofMinutes(16));
        assertThat(call(get("/api/me"), s.token(), null).getStatus()).isEqualTo(401);
        MockHttpServletResponse renewed = refresh(s.refresh());
        assertThat(renewed.getStatus()).isEqualTo(200);
        // o banco guarda microssegundos (arredonda os nanos do relógio): as fronteiras são testadas com folga
        clock.advance(Duration.ofDays(30).minusMinutes(1));
        MockHttpServletResponse stillValid = refresh(refreshCookie(renewed));
        assertThat(stillValid.getStatus()).as("um minuto antes de vencer").isEqualTo(200);
        clock.advance(Duration.ofDays(30).plusSeconds(1));
        assertThat(refresh(refreshCookie(stillValid)).getStatus()).as("um segundo depois de vencer").isEqualTo(401);
    }

    @Test
    @DisplayName("Logout revoga tudo: access token, todos os refresh (de todos os aparelhos) e limpa o cookie")
    void logout() throws Exception {
        Session s = register();
        String otherDevice = refreshCookie(login(s.email(), PASSWORD, ip()));
        MockHttpServletResponse r = call(post("/api/auth/logout"), s.token(), null);
        assertThat(r.getStatus()).isEqualTo(204);
        assertThat(r.getHeader(HttpHeaders.SET_COOKIE)).contains("agendai_refresh=;").contains("Max-Age=0");
        assertThat(call(get("/api/me"), s.token(), null).getStatus()).isEqualTo(401);
        assertThat(refresh(s.refresh()).getStatus()).isEqualTo(401);
        assertThat(refresh(otherDevice).getStatus()).isEqualTo(401);
        assertThat(call(post("/api/auth/logout"), null, null).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Troca de senha: exige a atual, recusa igual e fraca; revoga as sessões antigas e devolve uma nova")
    void changePassword() throws Exception {
        Session s = register();
        assertError(call(put("/api/me/password"), s.token(), Map.of("currentPassword", "Errada12345", "newPassword", "Pirilampo9182")),
                400, "Invalid password");
        assertError(call(put("/api/me/password"), s.token(), Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD)),
                400, "New password must be different from the current one");
        assertError(call(put("/api/me/password"), s.token(), Map.of("currentPassword", PASSWORD, "newPassword", "senha12345")),
                400, PasswordStrength.TOO_COMMON);
        MockHttpServletResponse ok = call(put("/api/me/password"), s.token(), Map.of("currentPassword", PASSWORD, "newPassword", "Pirilampo9182"));
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(call(get("/api/me"), s.token(), null).getStatus()).isEqualTo(401);
        assertThat(refresh(s.refresh()).getStatus()).isEqualTo(401);
        assertThat(call(get("/api/me"), body(ok).get("accessToken").asString(), null).getStatus()).isEqualTo(200);
        assertThat(refresh(refreshCookie(ok)).getStatus()).isEqualTo(200);
        assertThat(login(s.email(), "Pirilampo9182", ip()).getStatus()).isEqualTo(200);
    }

    private void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }
}
