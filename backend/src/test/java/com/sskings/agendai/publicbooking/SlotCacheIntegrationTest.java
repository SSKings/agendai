package com.sskings.agendai.publicbooking;

import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.support.MailBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Cache dos horários livres da página (ADR-012): serve do Redis e se renova a cada escrita gravada. Segunda: 30/11. */
class SlotCacheIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    @Autowired MailBox mail;
    @Autowired StringRedisTemplate redis;

    record Shop(Session owner, String slug, String corte, String joao) {
        String base() {
            return "/api/tenants/" + owner.tenantId();
        }

        String pub() {
            return "/api/public/" + slug;
        }
    }

    Shop shop() throws Exception {
        Session ana = register("Ana Souza", email(), "Barbearia " + UUID.randomUUID().toString().substring(0, 8));
        String slug = body(call(get("/api/me"), ana.token(), null)).get("tenants").get(0).get("slug").asString();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 0, "priceCents", 4500, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte)))).get("id").asString();
        hours(new Shop(ana, slug, corte, joao), "09:00", "10:00");
        return new Shop(ana, slug, corte, joao);
    }

    void hours(Shop s, String start, String end) throws Exception {
        MockHttpServletResponse r = call(put(s.base() + "/professionals/" + s.joao() + "/working-hours"), s.owner().token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", start, "end", end))));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    List<String> slots(Shop s) throws Exception {
        MockHttpServletResponse r = call(get(s.pub() + "/availability?professionalId=" + s.joao() + "&serviceId=" + s.corte()
                + "&date=" + MONDAY), null, null);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        for (JsonNode n : body(r).get("slots")) out.add(n.asString().substring(11, 16));
        return out;
    }

    String version(Shop s) {
        return redis.opsForValue().get("slots:v:" + s.owner().tenantId());
    }

    @Test
    @DisplayName("Segunda leitura vem do cache: mudança feita por fora da API (sem escrita do estabelecimento) não aparece")
    void servedFromCache() throws Exception {
        Shop s = shop();
        assertThat(slots(s)).containsExactly("09:00", "09:15", "09:30");
        // por baixo da API: nenhuma transação de escrita, nenhuma versão nova
        ownerSql("DELETE FROM working_hours WHERE tenant_id = ?::uuid", s.owner().tenantId());
        assertThat(slots(s)).as("do cache").containsExactly("09:00", "09:15", "09:30");
        // a reserva olha o banco, não o cache
        MockHttpServletResponse h = call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T09:00"));
        assertThat(h.getStatus()).as(h.getContentAsString()).isEqualTo(409);
    }

    @Test
    @DisplayName("Toda escrita gravada no estabelecimento renova: expediente pelo painel, agendamento pela página")
    void writesRenew() throws Exception {
        Shop s = shop();
        assertThat(slots(s)).containsExactly("09:00", "09:15", "09:30");
        hours(s, "09:00", "11:00");
        assertThat(slots(s)).containsExactly("09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30");

        String email = email();
        MockHttpServletResponse h = call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T10:00"));
        call(post(s.pub() + "/bookings"), null, Map.of("holdToken", body(h).get("token").asString(),
                "customer", Map.of("name", "Carla Dias", "phone", "11987654321", "email", email)));
        assertThat(slots(s)).as("o pendente ocupa na hora").containsExactly("09:00", "09:15", "09:30", "10:30");
    }

    @Test
    @DisplayName("Leitura e escrita que falhou não renovam (a versão só sobe depois de um commit de escrita)")
    void onlyCommittedWrites() throws Exception {
        Shop s = shop();
        slots(s);
        String v = version(s);
        slots(s);
        call(get(s.base() + "/professionals"), s.owner().token(), null);
        assertThat(version(s)).as("leituras").isEqualTo(v);
        // expediente inválido: a transação desfaz
        MockHttpServletResponse bad = call(put(s.base() + "/professionals/" + s.joao() + "/working-hours"), s.owner().token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "10:00", "end", "09:00"))));
        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(version(s)).as("escrita recusada").isEqualTo(v);
        hours(s, "09:00", "10:30");
        assertThat(version(s)).isNotEqualTo(v);
    }

    @Test
    @DisplayName("A antecedência mínima vale por cima do cache: o horário que entrou nela sai da lista sem renovar nada")
    void noticeOverCache() throws Exception {
        clock.set(Instant.parse("2026-11-30T10:55:00Z"));     // 07:55: 09:00 respeita 1 h
        Shop s = shop();
        assertThat(slots(s)).containsExactly("09:00", "09:15", "09:30");
        String v = version(s);
        clock.set(Instant.parse("2026-11-30T11:05:00Z"));     // 08:05
        assertThat(slots(s)).containsExactly("09:15", "09:30");
        assertThat(version(s)).isEqualTo(v);
    }

    @Test
    @DisplayName("Cada estabelecimento tem a sua versão: escrever num não renova o outro")
    void perTenant() throws Exception {
        Shop ana = shop();
        Shop eve = shop();
        slots(ana);
        String v = version(ana);
        hours(eve, "09:00", "11:00");
        assertThat(version(ana)).isEqualTo(v);
    }

    @Test
    @DisplayName("Tentativa anônima que não grava nada (confirmar com token inexistente) não renova o cache")
    void failedAttemptKeepsCache() throws Exception {
        Shop s = shop();
        slots(s);
        String v = version(s);
        for (int i = 0; i < 3; i++) {
            assertThat(call(post(s.pub() + "/bookings/confirm"), null, Map.of("token", "x".repeat(43), "code", "123456")).getStatus())
                    .isEqualTo(404);
        }
        assertThat(version(s)).isEqualTo(v);
    }

    @Test
    @DisplayName("A chave separa estabelecimento, profissional e serviço: nenhum serve a lista do outro")
    void keysDoNotMix() throws Exception {
        Shop ana = shop();
        Shop eve = shop();
        hours(eve, "14:00", "15:00");
        hours(ana, "09:00", "10:00");     // as duas com o mesmo número de escritas: mesma versão
        assertThat(version(ana)).isEqualTo(version(eve));
        assertThat(slots(ana)).containsExactly("09:00", "09:15", "09:30");
        assertThat(slots(eve)).containsExactly("14:00", "14:15", "14:30");

        String base = ana.base();
        String longo = body(call(post(base + "/services"), ana.owner().token(), Map.of("name", "Pacote", "durationMinutes", 60,
                "bufferMinutes", 0, "priceCents", 9000, "active", true))).get("id").asString();
        String beto = body(call(post(base + "/professionals"), ana.owner().token(), Map.of("name", "Beto", "active", true,
                "serviceIds", List.of(ana.corte(), longo)))).get("id").asString();
        call(put(base + "/professionals/" + ana.joao()), ana.owner().token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(ana.corte(), longo)));
        call(put(base + "/professionals/" + beto + "/working-hours"), ana.owner().token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "11:00", "end", "12:00"))));
        assertThat(slots(ana)).containsExactly("09:00", "09:15", "09:30");
        assertThat(slots(new Shop(ana.owner(), ana.slug(), longo, ana.joao()))).as("outro serviço").containsExactly("09:00");
        assertThat(slots(new Shop(ana.owner(), ana.slug(), ana.corte(), beto))).as("outro profissional")
                .containsExactly("11:00", "11:15", "11:30");
    }
}

