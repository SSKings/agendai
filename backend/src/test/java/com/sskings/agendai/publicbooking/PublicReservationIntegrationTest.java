package com.sskings.agendai.publicbooking;

import com.sskings.agendai.booking.BookingTestSupport;
import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.support.MailBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Agendar pela página pública: reserva de 5 min → dados → código por e-mail → confirmado. Segunda: 30/11. */
class PublicReservationIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    @Autowired MailBox mail;
    @Autowired GenericContainer<?> mailpit;
    @Autowired BookingTestSupport support;

    record Shop(Session owner, String slug, String corte, String joao) {
        String pub() {
            return "/api/public/" + slug;
        }

        String base() {
            return "/api/tenants/" + owner.tenantId();
        }
    }

    Shop shop() throws Exception {
        Session ana = register("Ana Souza", email(), "Barbearia " + UUID.randomUUID().toString().substring(0, 8));
        String slug = body(call(get("/api/me"), ana.token(), null)).get("tenants").get(0).get("slug").asString();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 10, "priceCents", 4500, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte)))).get("id").asString();
        call(put(base + "/professionals/" + joao + "/working-hours"), ana.token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        return new Shop(ana, slug, corte, joao);
    }

    MockHttpServletResponse hold(Shop s, String time, String ip) throws Exception {
        return call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T" + time), ip);
    }

    String holdToken(Shop s, String time) throws Exception {
        MockHttpServletResponse r = hold(s, time, ip());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r).get("token").asString();
    }

    static Map<String, Object> client(String email) {
        return Map.of("name", "Carla Dias", "phone", "(11) 98765-4321", "email", email);
    }

    MockHttpServletResponse book(Shop s, String holdToken, String email) throws Exception {
        return call(post(s.pub() + "/bookings"), null, Map.of("holdToken", holdToken, "customer", client(email)));
    }

    /** Reserva e pede o código: devolve o token do pendente. */
    String pending(Shop s, String time, String email) throws Exception {
        MockHttpServletResponse r = book(s, holdToken(s, time), email);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r).get("token").asString();
    }

    MockHttpServletResponse confirm(Shop s, String token, String code) throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put("token", token);
        m.put("code", code);
        return call(post(s.pub() + "/bookings/confirm"), null, m);
    }

    List<String> freeTimes(Shop s) throws Exception {
        JsonNode b = body(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), null, null));
        List<String> out = new java.util.ArrayList<>();
        b.get("slots").forEach(n -> out.add(n.asString().substring(11, 16)));
        return out;
    }

    void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    @Test
    @DisplayName("Fluxo completo: segura o horário, pede o código, o código chega por e-mail, confirma; o horário sai dos livres")
    void fullFlow() throws Exception {
        Shop s = shop();
        String email = email();
        MockHttpServletResponse held = hold(s, "09:15", ip());
        assertThat(held.getStatus()).isEqualTo(201);
        assertThat(body(held).get("expiresAt").asString()).isEqualTo("2026-11-24T09:05:00Z");
        MockHttpServletResponse pend = book(s, body(held).get("token").asString(), email);
        assertThat(pend.getStatus()).as(pend.getContentAsString()).isEqualTo(201);
        assertThat(body(pend).get("expiresAt").asString()).as("código vale 10 min").isEqualTo("2026-11-24T09:10:00Z");
        String token = body(pend).get("token").asString();
        assertThat(freeTimes(s)).as("o pendente já segura o horário").doesNotContain("09:00", "09:15", "09:30", "09:45");

        String text = mail.lastTextTo(email);
        assertThat(text).as("o nome digitado não vai no e-mail (remetente do Agendaí para endereço qualquer)").doesNotContain("Carla")
                .contains("Corte com João").contains("segunda-feira, 30/11 às 09:15").contains("10 minutos");
        String code = mail.lastCodeTo(email);
        assertThat(code).hasSize(6);
        assertThat(text).doesNotContain(token).doesNotContain("<");

        MockHttpServletResponse ok = confirm(s, token, code);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        JsonNode b = body(ok);
        assertThat(b.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(b.get("start").asString()).isEqualTo(MONDAY + "T09:15:00-03:00");
        assertThat(b.get("serviceName").asString()).isEqualTo("Corte");
        assertThat(b.get("professionalName").asString()).isEqualTo("João");
        MockHttpServletResponse again = confirm(s, token, code);
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(body(again)).as("clique duplo devolve o mesmo").isEqualTo(b);
        String[] fromAndSubject = mail.lastFromAndSubjectTo(email);
        assertThat(fromAndSubject[0]).isEqualTo("nao-responda@agendai.app");
        assertThat(fromAndSubject[1]).startsWith("Seu código: " + code + " — Barbearia ");


        JsonNode agenda = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null));
        assertThat(agenda.get("bookings").findValuesAsString("status")).containsExactly("CONFIRMED");
        assertThat(agenda.get("bookings").get(0).get("customer").get("name").asString()).isEqualTo("Carla Dias");
        assertThat(support.codeHashOf(s.owner().tenantId())).as("o código não fica no banco depois de confirmar").isNull();
        // o pendente de outro cliente não é mexido pelo painel: ele confirma pelo código ou vence sozinho
        String other = pending(s, "10:30", email());
        assertThat(other).isNotBlank();
        String pendingId = agendaIdOf(s, "PENDING");
        assertError(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(s.base() + "/bookings/" + pendingId),
                s.owner().token(), Map.of("status", "CANCELLED")), 409, "Invalid status change");
    }

    @Test
    @DisplayName("Reserva: só horário livre; o mesmo horário não é segurado duas vezes; no máximo 3 abertas por origem; usa uma vez")
    void holdRules() throws Exception {
        Shop s = shop();
        assertError(hold(s, "08:00", ip()), 409, Holds.TAKEN);           // fora do expediente
        assertError(hold(s, "09:05", ip()), 409, Holds.TAKEN);           // fora da grade de 15 min
        String first = holdToken(s, "09:00");
        assertError(hold(s, "09:00", ip()), 409, Holds.TAKEN);
        String ip = ip();
        for (String t : List.of("10:00", "10:15", "10:30")) assertThat(hold(s, t, ip).getStatus()).isEqualTo(201);
        assertError(hold(s, "10:45", ip), 429, Holds.TOO_MANY);
        assertThat(hold(s, "10:45", ip()).getStatus()).as("outra origem").isEqualTo(201);

        assertThat(book(s, first, email()).getStatus()).isEqualTo(201);
        assertError(book(s, first, email()), 400, PublicBookingService.HOLD_EXPIRED);
        assertError(book(s, "x".repeat(43), email()), 400, PublicBookingService.HOLD_EXPIRED);
        Shop other = shop();
        String foreign = holdToken(other, "09:00");
        assertError(book(s, foreign, email()), 400, PublicBookingService.HOLD_EXPIRED);
    }

    @Test
    @DisplayName("Pendente segura o horário até vencer; vencido não bloqueia (antes mesmo do job), não confirma, e o job o expira")
    void pendingExpires() throws Exception {
        Shop s = shop();
        String email = email();
        String token = pending(s, "09:00", email);
        String code = mail.lastCodeTo(email);
        assertError(call(post(s.base() + "/bookings"), s.owner().token(), Map.of("professionalId", s.joao(), "serviceId", s.corte(),
                "start", MONDAY + "T09:00", "customer", Map.of("name", "Outro", "phone", "11900000000"))), 409, "Time slot not available");

        clock.advance(Duration.ofMinutes(10));
        assertThat(freeTimes(s)).as("vencido não segura").contains("09:00");
        assertError(confirm(s, token, code), 400, "Booking expired");
        Session owner = renewed(s.owner());
        MockHttpServletResponse panel = call(post(s.base() + "/bookings"), owner.token(), Map.of("professionalId", s.joao(),
                "serviceId", s.corte(), "start", MONDAY + "T09:00", "customer", Map.of("name", "Outro", "phone", "11900000000")));
        assertThat(panel.getStatus()).as("o pendente vencido é expirado na hora de agendar").isEqualTo(201);

        String later = pending(s, "10:00", email());
        clock.advance(Duration.ofMinutes(10));
        assertThat(support.expireAll()).isGreaterThanOrEqualTo(1);
        assertThat(support.expireAll()).as("de novo: nada a expirar").isZero();
        assertThat(support.statusOf(s.owner().tenantId(), "10:00")).isEqualTo("EXPIRED");
        assertThat(later).isNotBlank();
        JsonNode agenda = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), renewed(owner).token(), null));
        assertThat(agenda.get("bookings").findValuesAsString("status")).as("expirado não aparece na agenda").doesNotContain("EXPIRED");
    }

    @Test
    @DisplayName("Código: até 5 tentativas; a 5ª errada expira e libera o horário; código de outro agendamento não serve")
    void codeAttempts() throws Exception {
        Shop s = shop();
        String a = email();
        String b = email();
        String tokenA = pending(s, "09:00", a);
        String tokenB = pending(s, "10:00", b);
        String codeA = mail.lastCodeTo(a);
        String codeB = mail.lastCodeTo(b);
        String wrong = codeA.equals("000000") ? "111111" : "000000";
        if (!codeB.equals(codeA)) assertError(confirm(s, tokenA, codeB), 400, "Invalid code");
        else assertError(confirm(s, tokenA, wrong), 400, "Invalid code");
        for (int i = 0; i < 3; i++) assertError(confirm(s, tokenA, wrong), 400, "Invalid code");
        assertError(confirm(s, tokenA, wrong), 400, "Invalid code");           // a 5ª: expira
        assertError(confirm(s, tokenA, codeA), 400, "Booking expired");
        assertThat(freeTimes(s)).contains("09:00");
        assertThat(confirm(s, tokenB, codeB).getStatus()).isEqualTo(200);

        String tokenC = pending(s, "11:00", email());
        support.setAttempts(s.owner().tenantId(), "11:00", 5);
        assertError(confirm(s, tokenC, "123456"), 400, "Too many attempts");
        assertThat(support.statusOf(s.owner().tenantId(), "11:00")).isEqualTo("EXPIRED");
        assertError(confirm(s, "x".repeat(43), "123456"), 404, "Booking not found");
    }

    @Test
    @DisplayName("Dados: nome, telefone e e-mail obrigatórios e válidos; código de 6 dígitos; nada a mais no JSON")
    void validation() throws Exception {
        Shop s = shop();
        String hold = holdToken(s, "09:00");
        for (Map<String, Object> c : List.of(Map.<String, Object>of("name", "Carla", "phone", "11987654321"),
                Map.<String, Object>of("name", "Carla", "phone", "11987654321", "email", "carla@"),
                Map.<String, Object>of("name", " ", "phone", "11987654321", "email", "c@x.com"),
                Map.<String, Object>of("name", "Carla", "phone", "abc", "email", "c@x.com"))) {
            assertError(call(post(s.pub() + "/bookings"), null, Map.of("holdToken", hold, "customer", c)), 400, "Validation failed");
        }
        assertError(confirm(s, "x".repeat(43), "12345"), 400, "Validation failed");
        assertError(confirm(s, "x".repeat(43), "abcdef"), 400, "Validation failed");
        assertError(call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T09:00", "status", "CONFIRMED")), 400, "Malformed JSON request");
        assertThat(book(s, hold, email()).getStatus()).as("a reserva continuou valendo").isEqualTo(201);
    }

    @Test
    @DisplayName("Telefone de cliente que já existe com outro nome: não revela nada e não muda o cadastro; o código vai ao e-mail digitado")
    void existingCustomer() throws Exception {
        Shop s = shop();
        call(post(s.base() + "/bookings"), s.owner().token(), Map.of("professionalId", s.joao(), "serviceId", s.corte(),
                "start", MONDAY + "T11:00", "customer", Map.of("name", "Outra Pessoa", "phone", "11987654321", "email", "outra@x.com")));
        String email = email();
        MockHttpServletResponse r = book(s, holdToken(s, "09:00"), email);
        assertThat(r.getStatus()).isEqualTo(201);
        assertThat(r.getContentAsString()).doesNotContain("Outra", "outra@");
        assertThat(mail.lastCodeTo(email)).hasSize(6);
        assertThat(mail.lastTextTo("outra@x.com")).isNull();
        JsonNode agenda = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null));
        // na agenda, o agendamento online mostra quem confirmou (nome e e-mail digitados); o do painel, o cadastro
        assertThat(agenda.get("bookings").findValuesAsString("name")).containsExactlyInAnyOrder("Outra Pessoa", "Carla Dias");
        assertThat(agenda.toString()).contains(email).contains("outra@x.com");
    }

    @Test
    @DisplayName("E-mail fora do ar: 503 e nada fica (nem pendente segurando o horário)")
    void mailDown() throws Exception {
        Shop s = shop();
        String hold = holdToken(s, "09:00");
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(mailpit.getContainerId()).exec();
        try {
            assertError(book(s, hold, email()), 503, "Email unavailable, try again");
        } finally {
            docker.unpauseContainerCmd(mailpit.getContainerId()).exec();
        }
        assertThat(freeTimes(s)).as("o pendente sem e-mail não segura o horário").contains("09:00");
        assertThat(support.statusOf(s.owner().tenantId(), "09:00")).isEqualTo("EXPIRED");
        assertThat(hold(s, "09:00", ip()).getStatus()).as("e a reserva do Redis foi liberada").isEqualTo(201);
    }

    String agendaIdOf(Shop s, String status) throws Exception {
        for (JsonNode b : body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null)).get("bookings")) {
            if (b.get("status").asString().equals(status)) return b.get("id").asString();
        }
        return null;
    }

    Session renewed(Session s) throws Exception {
        MockHttpServletResponse r = refresh(s.refresh());
        assertThat(r.getStatus()).isEqualTo(200);
        return new Session(body(r).get("accessToken").asString(), refreshCookie(r), s.userId(), s.email(), s.tenantId());
    }

    // ---------- complementos da revisão ----------

    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    @DisplayName("Confirmar duas vezes ao mesmo tempo com o código certo: as duas respostas 200, um agendamento só")
    void concurrentConfirm() throws Exception {
        Shop s = shop();
        String email = email();
        String token = pending(s, "09:00", email);
        String code = mail.lastCodeTo(email);
        List<Integer> statuses = parallel(2, i -> confirm(s, token, code).getStatus());
        assertThat(statuses).containsOnly(200);
        assertThat(support.statusOf(s.owner().tenantId(), "09:00")).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("10 códigos errados ao mesmo tempo: as tentativas param em 5, o pendente expira, nenhum erro 500")
    void concurrentAttempts() throws Exception {
        Shop s = shop();
        String email = email();
        String token = pending(s, "09:00", email);
        String wrong = mail.lastCodeTo(email).equals("000000") ? "111111" : "000000";
        List<Integer> statuses = parallel(10, i -> confirm(s, token, wrong).getStatus());
        assertThat(statuses).containsOnly(400);
        assertThat(support.attemptsOf(s.owner().tenantId(), "09:00")).isEqualTo(5);
        assertThat(support.statusOf(s.owner().tenantId(), "09:00")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("No Redis, a reserva vence sozinha em até 5 min (horário, token e contador da origem)")
    void holdKeysExpire() throws Exception {
        Shop s = shop();
        String token = holdToken(s, "09:00");
        for (String key : List.of("hold:t:" + token, "hold:s:" + s.owner().tenantId() + ":" + s.joao() + ":" + MONDAY + "T09:00")) {
            assertThat(redis.getExpire(key)).as(key).isBetween(1L, 300L);
        }
        assertThat(redis.keys("hold:ip:" + s.owner().tenantId() + ":*")).singleElement()
                .satisfies(k -> assertThat(redis.getExpire(k)).isBetween(1L, 300L));
    }

    @Test
    @DisplayName("Reservas sobrepostas (09:00 e 09:15): só a primeira vira agendamento; a outra é 409 e não fica pendente")
    void overlappingHolds() throws Exception {
        Shop s = shop();
        String a = holdToken(s, "09:00");
        String b = holdToken(s, "09:15");
        assertThat(book(s, a, email()).getStatus()).isEqualTo(201);
        assertError(book(s, b, email()), 409, "Time slot not available");
        assertThat(support.statusOf(s.owner().tenantId(), "09:15")).isNull();
    }

    @Test
    @DisplayName("Reservas abertas contam por estabelecimento e origem; horário recusado não gasta a reserva da origem")
    void holdsPerBusinessAndSource() throws Exception {
        Shop s = shop();
        Shop other = shop();
        String ip = ip();
        assertThat(hold(s, "09:00", ip).getStatus()).isEqualTo(201);
        for (int i = 0; i < 3; i++) assertThat(hold(s, "09:00", ip).getStatus()).as("ocupado").isEqualTo(409);
        assertThat(hold(s, "09:15", ip).getStatus()).isEqualTo(201);
        assertThat(hold(s, "09:30", ip).getStatus()).isEqualTo(201);
        assertError(hold(s, "09:45", ip), 429, Holds.TOO_MANY);
        assertThat(hold(other, "09:00", ip).getStatus()).as("outro estabelecimento, mesma origem").isEqualTo(201);
    }

    @Test
    @DisplayName("Tetos por origem: pendentes da página não travam o painel; a página tem teto próprio e de pendentes abertos")
    void perSourceLimits() throws Exception {
        Shop s = shop();
        support.fillPublic(s.owner().tenantId(), 1000);
        MockHttpServletResponse panel = call(post(s.base() + "/bookings"), s.owner().token(), Map.of("professionalId", s.joao(),
                "serviceId", s.corte(), "start", MONDAY + "T11:00", "customer", Map.of("name", "Outro", "phone", "11900000000")));
        assertThat(panel.getStatus()).as("o painel segue").isEqualTo(201);
        assertError(book(s, holdToken(s, "09:00"), email()), 409, "Booking limit reached");

        Shop t = shop();
        support.fillOpenPending(t.owner().tenantId(), 50);
        assertError(book(t, holdToken(t, "09:00"), email()), 429, "Too many pending bookings, try again later");
    }

    @Test
    @DisplayName("Tentativa e fechamento do pendente filtram pelo estabelecimento (2ª camada do ADR-006)")
    void attemptsFilterByTenant() throws Exception {
        Shop s = shop();
        pending(s, "09:00", email());
        assertThat(support.attemptAndCloseAs(s.owner().tenantId(), UUID.randomUUID().toString(), "09:00")).isEqualTo(List.of(0, 0));
        assertThat(support.attemptAndCloseAs(s.owner().tenantId(), s.owner().tenantId(), "09:00")).isEqualTo(List.of(1, 1));
    }

    interface Call {
        int status(int i) throws Exception;
    }

    static List<Integer> parallel(int n, Call call) throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> { start.await(); return call.status(k); }));
            }
            start.countDown();
            List<Integer> out = new java.util.ArrayList<>();
            for (var f : fs) out.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
