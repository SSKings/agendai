package com.sskings.agendai.publicbooking;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Página pública (sem login): só o que o cliente precisa ver, só do estabelecimento do link. Segunda: 30/11. */
class PublicBookingIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    record Shop(Session owner, String slug, String corte, String barba, String joao, String beto) {
        String base() {
            return "/api/tenants/" + owner.tenantId();
        }

        String pub() {
            return "/api/public/" + slug;
        }
    }

    Shop shop(String business) throws Exception {
        Session ana = register("Ana Souza", email(), business);
        String slug = body(call(get("/api/me"), ana.token(), null)).get("tenants").get(0).get("slug").asString();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 10, "priceCents", 4500, "active", true))).get("id").asString();
        String barba = body(call(post(base + "/services"), ana.token(), Map.of("name", "Barba", "durationMinutes", 20,
                "bufferMinutes", 0, "priceCents", 3000, "active", false))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte, barba)))).get("id").asString();
        String beto = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "Beto", "active", false,
                "serviceIds", List.of(corte)))).get("id").asString();
        call(put(base + "/professionals/" + joao + "/working-hours"), ana.token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "09:00", "end", "10:00"))));
        return new Shop(ana, slug, corte, barba, joao, beto);
    }

    MockHttpServletResponse anonymous(String path) throws Exception {
        return call(get(path), null, null);
    }

    void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    @Test
    @DisplayName("Sem login: nome e fuso do estabelecimento, serviços ativos (sem intervalo), profissionais ativos que fazem o serviço")
    void browse() throws Exception {
        Shop s = shop("Barbearia Pública");
        JsonNode business = body(anonymous(s.pub()));
        assertThat(business.get("name").asString()).isEqualTo("Barbearia Pública");
        assertThat(business.get("timeZone").asString()).isEqualTo("America/Sao_Paulo");
        assertThat(business.get("bookingWindowDays").asInt()).isEqualTo(60);
        assertThat(business.get("cancelUntilHours").asInt()).isEqualTo(24);
        assertThat(business.size()).as("nada além disso (nem id, nem link)").isEqualTo(4);

        JsonNode services = body(anonymous(s.pub() + "/services"));
        assertThat(services).hasSize(1);
        assertThat(services.get(0).get("name").asString()).isEqualTo("Corte");
        assertThat(services.get(0).get("durationMinutes").asInt()).isEqualTo(30);
        assertThat(services.get(0).get("priceCents").asInt()).isEqualTo(4500);
        assertThat(services.get(0).has("bufferMinutes")).as("o preparo é interno").isFalse();

        JsonNode pros = body(anonymous(s.pub() + "/services/" + s.corte() + "/professionals"));
        assertThat(pros.findValuesAsString("name")).as("o Beto está inativo").containsExactly("João");
        assertThat(pros.get(0).size()).isEqualTo(2);
        assertError(anonymous(s.pub() + "/services/" + s.barba() + "/professionals"), 404, "Service not found");
        assertError(anonymous(s.pub() + "/services/" + UUID.randomUUID() + "/professionals"), 404, "Service not found");

        JsonNode slots = body(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), null, null));
        assertThat(slots.get("slots")).hasSize(2);   // 09:00 e 09:15 (atendimento + preparo = 40 min até 10:00)
        assertThat(slots.get("slots").get(0).asString()).isEqualTo(MONDAY + "T09:00:00-03:00");
    }

    @Test
    @DisplayName("Os horários livres da página são os mesmos do painel, já sem os agendamentos de outros clientes (e sem dados deles)")
    void sameRulesAsPanel() throws Exception {
        Shop s = shop("Barbearia Igual");
        call(post(s.base() + "/bookings"), s.owner().token(), Map.of("professionalId", s.joao(), "serviceId", s.corte(),
                "start", MONDAY + "T09:00", "customer", Map.of("name", "Cliente Secreto", "phone", "11987654321", "email", "secreto@x.com")));
        MockHttpServletResponse r = call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), null, null);
        JsonNode panel = body(call(get(s.base() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), s.owner().token(), null));
        assertThat(body(r)).isEqualTo(panel);
        assertThat(body(r).get("slots")).isEmpty();
        assertThat(r.getContentAsString()).doesNotContain("Secreto", "secreto", "11987654321");
        assertError(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", "2026-11-23"), null, null), 400, "Date out of range");
    }

    @Test
    @DisplayName("Link inexistente ou malformado é 404 igual; ids de outro estabelecimento não servem pelo link deste")
    void isolation() throws Exception {
        Shop ana = shop("Salão Ana");
        Shop eve = shop("Salão Eve");
        for (String bad : new String[]{"nao-existe", "MAIUSCULA", "x".repeat(61), "tem_sublinhado", "tem.ponto"}) {
            assertError(anonymous("/api/public/" + bad), 404, "Business not found");
        }
        assertError(anonymous(ana.pub() + "/services/" + eve.corte() + "/professionals"), 404, "Service not found");
        assertError(call(get(ana.pub() + "/availability").param("professionalId", eve.joao()).param("serviceId", eve.corte())
                .param("date", MONDAY), null, null), 404, "Service not found");
        assertError(call(get(ana.pub() + "/availability").param("professionalId", eve.joao()).param("serviceId", ana.corte())
                .param("date", MONDAY), null, null), 404, "Professional not found");
        assertError(call(get(ana.pub() + "/availability").param("professionalId", ana.joao()).param("serviceId", eve.corte())
                .param("date", MONDAY), null, null), 404, "Service not found");
        assertThat(body(anonymous(eve.pub() + "/services")).get(0).get("id").asString()).isEqualTo(eve.corte());
    }

    @Test
    @DisplayName("Só leitura: POST, PUT, PATCH e DELETE pela rota pública são 401 e nada muda")
    void readOnly() throws Exception {
        Shop s = shop("Barbearia Só Leitura");
        for (var req : List.of(post(s.pub() + "/services"), put(s.pub() + "/services/" + s.corte()),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(s.pub() + "/services/" + s.corte()),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(s.pub() + "/services/" + s.corte()))) {
            assertThat(call(req, null, Map.of("name", "x")).getStatus()).isEqualTo(401);
        }
        assertThat(body(anonymous(s.pub() + "/services")).findValuesAsString("name")).containsExactly("Corte");
    }

    @Test
    @DisplayName("Horários pela página: serviço inativo, profissional inativo ou que não faz o serviço — o mesmo 404 (nada a deduzir)")
    void inactiveIsNotFound() throws Exception {
        Shop s = shop("Barbearia Discreta");
        for (String[] q : new String[][]{{s.joao(), s.barba()}, {s.beto(), s.corte()}, {s.beto(), s.barba()}}) {
            MockHttpServletResponse r = call(get(s.pub() + "/availability").param("professionalId", q[0]).param("serviceId", q[1])
                    .param("date", MONDAY), null, null);
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(404);
        }
        call(put(s.base() + "/services/" + s.barba()), s.owner().token(), Map.of("name", "Barba", "durationMinutes", 20,
                "bufferMinutes", 0, "priceCents", 3000, "active", true));
        String caio = body(call(post(s.base() + "/professionals"), s.owner().token(), Map.of("name", "Caio", "active", true,
                "serviceIds", List.of(s.corte())))).get("id").asString();
        assertError(call(get(s.pub() + "/availability").param("professionalId", caio).param("serviceId", s.barba())
                .param("date", MONDAY), null, null), 404, "Professional not found");
    }

    @Test
    @DisplayName("Link no tamanho máximo (50 + sufixo '-2') abre")
    void longestSlug() throws Exception {
        String name = "Barbearia " + "x".repeat(60);
        shop(name);
        Shop second = shop(name);
        assertThat(second.slug()).hasSize(52).endsWith("-2");
        assertThat(body(anonymous(second.pub())).get("name").asString()).isEqualTo(name);
    }

    @Test
    @DisplayName("Limite por IP próprio da página pública (120/min); não consome o da autenticação; outro IP segue")
    void rateLimit() throws Exception {
        Shop s = shop("Barbearia Limitada");
        clock.set(Instant.parse("2026-11-24T14:00:05Z"));
        String ip = ip();
        for (int i = 0; i < 120; i++) assertThat(call(get(s.pub()), null, null, ip).getStatus()).isEqualTo(200);
        MockHttpServletResponse blocked = call(get(s.pub() + "/services"), null, null, ip);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("55");
        assertThat(call(post("/api/auth/login"), null, Map.of("email", "x@y.com", "password", "Errada12345"), ip).getStatus())
                .as("o orçamento da autenticação é outro").isEqualTo(401);
        assertThat(call(get(s.pub()), null, null, ip()).getStatus()).as("outro IP").isEqualTo(200);
        clock.advance(Duration.ofSeconds(60));
        assertThat(call(get(s.pub()), null, null, ip).getStatus()).as("minuto seguinte").isEqualTo(200);
    }
}
