package com.sskings.agendai.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base dos testes de integração: a aplicação inteira contra PostgreSQL e Redis de verdade. Cada requisição sai de um
 * IP diferente, a menos que o teste fixe um: o limite por IP não interfere nos outros testes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    public static final String PASSWORD = "Vagalume4821";
    private static final AtomicInteger IPS = new AtomicInteger();
    private static final Pattern REFRESH_COOKIE = Pattern.compile("agendai_refresh=([^;]*)");

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected MutableClock clock;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    protected static String ip() {
        int n = IPS.incrementAndGet();
        return "10." + (n >> 16 & 255) + "." + (n >> 8 & 255) + "." + (n & 255);
    }

    protected static String email() {
        return "u-" + UUID.randomUUID().toString().substring(0, 12) + "@teste.com";
    }

    /** Requisição completa: origem, token (opcional) e corpo JSON (opcional, objeto ou String crua). */
    protected MockHttpServletResponse call(AbstractMockHttpServletRequestBuilder<?> req, String token, Object body, String ip) throws Exception {
        req.with(r -> { r.setRemoteAddr(ip); return r; });
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    protected MockHttpServletResponse call(AbstractMockHttpServletRequestBuilder<?> req, String token, Object body) throws Exception {
        return call(req, token, body, ip());
    }

    protected JsonNode body(MockHttpServletResponse r) throws Exception {
        return json.readTree(r.getContentAsString());
    }

    /** Valor do cookie de refresh no Set-Cookie da resposta (null se não houver). */
    protected static String refreshCookie(MockHttpServletResponse r) {
        String header = r.getHeader(HttpHeaders.SET_COOKIE);
        if (header == null) return null;
        Matcher m = REFRESH_COOKIE.matcher(header);
        return m.find() ? m.group(1) : null;
    }

    /** POST /api/auth/refresh com o cookie e o cabeçalho exigido. */
    protected MockHttpServletResponse refresh(String cookie) throws Exception {
        var req = post("/api/auth/refresh").header("X-Requested-With", "agendai");
        if (cookie != null) req.cookie(new jakarta.servlet.http.Cookie("agendai_refresh", cookie));
        return call(req, null, null);
    }

    /** Dono recém-cadastrado: access token, refresh token, id e o estabelecimento criado no cadastro. */
    public record Session(String token, String refresh, String userId, String email, String tenantId) {}

    protected Session register() throws Exception {
        return register("Ana Souza", email(), "Barbearia da Ana");
    }

    protected Session register(String name, String email, String business) throws Exception {
        MockHttpServletResponse r = call(post("/api/auth/register"), null,
                Map.of("name", name, "email", email, "password", PASSWORD, "businessName", business));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        String token = body(r).get("accessToken").asString();
        JsonNode me = body(call(get("/api/me"), token, null));
        return new Session(token, refreshCookie(r), me.get("id").asString(), email, me.get("tenants").get(0).get("id").asString());
    }
}
