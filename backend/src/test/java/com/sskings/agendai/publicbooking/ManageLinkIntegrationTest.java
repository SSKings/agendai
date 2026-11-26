package com.sskings.agendai.publicbooking;

import com.sskings.agendai.booking.BookingTestSupport;
import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.support.MailBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Política do estabelecimento e link pessoal do cliente (consultar, cancelar, remarcar). Segunda: 30/11. */
class ManageLinkIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    @Autowired MailBox mail;
    @Autowired BookingTestSupport support;

    record Shop(Session owner, String slug, String corte, String barba, String joao) {
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
                "bufferMinutes", 0, "priceCents", 4500, "active", true))).get("id").asString();
        String barba = body(call(post(base + "/services"), ana.token(), Map.of("name", "Barba", "durationMinutes", 30,
                "bufferMinutes", 0, "priceCents", 3000, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte, barba)))).get("id").asString();
        call(put(base + "/professionals/" + joao + "/working-hours"), ana.token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        return new Shop(ana, slug, corte, barba, joao);
    }

    String hold(Shop s, String service, String time) throws Exception {
        MockHttpServletResponse r = call(post(s.pub() + "/holds"), null, Map.of("serviceId", service, "professionalId", s.joao(),
                "start", MONDAY + "T" + time));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r).get("token").asString();
    }

    /** Agenda e confirma pela página; devolve o token do link pessoal. */
    String confirmed(Shop s, String time, String email) throws Exception {
        MockHttpServletResponse p = call(post(s.pub() + "/bookings"), null, Map.of("holdToken", hold(s, s.corte(), time),
                "customer", Map.of("name", "Carla Dias", "phone", "11987654321", "email", email)));
        String token = body(p).get("token").asString();
        MockHttpServletResponse c = call(post(s.pub() + "/bookings/confirm"), null, Map.of("token", token, "code", mail.lastCodeTo(email)));
        assertThat(c.getStatus()).as(c.getContentAsString()).isEqualTo(200);
        return token;
    }

    MockHttpServletResponse action(Shop s, String what, Map<String, Object> body) throws Exception {
        return call(post(s.pub() + "/bookings/" + what), null, body);
    }

    void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    Session renewed(Session s) throws Exception {
        MockHttpServletResponse r = refresh(s.refresh());
        return new Session(body(r).get("accessToken").asString(), refreshCookie(r), s.userId(), s.email(), s.tenantId());
    }

    @Test
    @DisplayName("Política: padrão 60 min / 24 h / 60 dias; o dono altera (passos de 5 min, limites); a equipe só lê")
    void policy() throws Exception {
        Shop s = shop();
        String url = s.base() + "/policy";
        JsonNode p = body(call(get(url), s.owner().token(), null));
        assertThat(p.get("minNoticeMinutes").asInt()).isEqualTo(60);
        assertThat(p.get("cancelUntilHours").asInt()).isEqualTo(24);
        assertThat(p.get("bookingWindowDays").asInt()).isEqualTo(60);
        assertThat(body(call(put(url), s.owner().token(), Map.of("minNoticeMinutes", 120, "cancelUntilHours", 2, "bookingWindowDays", 30)))
                .get("bookingWindowDays").asInt()).isEqualTo(30);
        assertThat(body(call(get(url), s.owner().token(), null)).get("minNoticeMinutes").asInt()).isEqualTo(120);
        assertError(call(put(url), s.owner().token(), Map.of("minNoticeMinutes", 7, "cancelUntilHours", 2, "bookingWindowDays", 30)), 400,
                "Times must be multiples of 5 minutes");
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("minNoticeMinutes", -5, "cancelUntilHours", 2, "bookingWindowDays", 30),
                Map.<String, Object>of("minNoticeMinutes", 10085, "cancelUntilHours", 2, "bookingWindowDays", 30),
                Map.<String, Object>of("minNoticeMinutes", 0, "cancelUntilHours", 721, "bookingWindowDays", 30),
                Map.<String, Object>of("minNoticeMinutes", 0, "cancelUntilHours", 0, "bookingWindowDays", 0),
                Map.<String, Object>of("minNoticeMinutes", 0, "cancelUntilHours", 0, "bookingWindowDays", 181),
                Map.<String, Object>of("minNoticeMinutes", 0, "cancelUntilHours", 0))) {
            assertError(call(put(url), s.owner().token(), bad), 400, "Validation failed");
        }
        assertThat(call(put(url), s.owner().token(), Map.of("minNoticeMinutes", 10080, "cancelUntilHours", 720, "bookingWindowDays", 180))
                .getStatus()).as("limites exatos").isEqualTo(200);
        Session bia = register("Bia", email(), "Outro");
        addMember(s.owner().tenantId(), bia.userId(), "PROFESSIONAL");
        assertThat(call(get(url), bia.token(), null).getStatus()).isEqualTo(200);
        assertError(call(put(url), bia.token(), Map.of("minNoticeMinutes", 0, "cancelUntilHours", 0, "bookingWindowDays", 1)), 403,
                "Only owners can do this");
    }

    @Test
    @DisplayName("A página segue a política: nada antes de agora + antecedência; nada além da janela; o painel não segue")
    void publicFollowsPolicy() throws Exception {
        clock.set(Instant.parse("2026-11-30T11:00:00Z"));    // segunda 08:00 local
        Shop s = shop();
        call(put(s.base() + "/policy"), s.owner().token(), Map.of("minNoticeMinutes", 120, "cancelUntilHours", 24, "bookingWindowDays", 7));
        JsonNode free = body(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), null, null));
        assertThat(free.get("slots").get(0).asString()).as("08:00 + 2 h").isEqualTo(MONDAY + "T10:00:00-03:00");
        JsonNode panel = body(call(get(s.base() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), s.owner().token(), null));
        assertThat(panel.get("slots").get(0).asString()).as("o painel não segue a antecedência").isEqualTo(MONDAY + "T09:00:00-03:00");
        assertThat(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", "2026-12-07"), null, null).getStatus()).as("hoje + 7").isEqualTo(200);
        assertError(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", "2026-12-08"), null, null), 400, "Date out of range");
        assertThat(call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T09:00")).getStatus()).as("reserva também segue").isEqualTo(409);
    }

    @Test
    @DisplayName("Link pessoal: o e-mail de confirmação traz o link (token no fragmento); consulta; cancela dentro do prazo e avisa")
    void lookupAndCancel() throws Exception {
        Shop s = shop();
        String email = email();
        String token = confirmed(s, "09:00", email);
        String text = mail.lastTextTo(email);
        assertThat(text).contains("confirmado").contains("http://localhost:5173/agendar/" + s.slug() + "/gerenciar#" + token)
                .contains("até 24 h antes");

        JsonNode found = body(action(s, "lookup", Map.of("token", token)));
        assertThat(found.get("booking").get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(found.get("booking").get("start").asString()).isEqualTo(MONDAY + "T09:00:00-03:00");
        assertThat(found.get("changeable").asBoolean()).isTrue();
        assertThat(found.get("changeUntil").asString()).isEqualTo("2026-11-29T12:00:00Z");
        assertThat(found.toString()).doesNotContain("11987654321", email);

        JsonNode cancelled = body(action(s, "cancel", Map.of("token", token)));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(mail.lastTextTo(email)).contains("cancelado");
        JsonNode after = body(action(s, "lookup", Map.of("token", token)));
        assertThat(after.get("booking").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(after.get("changeable").asBoolean()).isFalse();
        assertError(action(s, "cancel", Map.of("token", token)), 409, "Invalid status change");
        assertThat(body(call(get(s.pub() + "/availability").param("professionalId", s.joao()).param("serviceId", s.corte())
                .param("date", MONDAY), null, null)).get("slots").get(0).asString()).as("horário livre de novo")
                .isEqualTo(MONDAY + "T09:00:00-03:00");
    }

    @Test
    @DisplayName("Fora do prazo da política: não cancela nem remarca (409); depois do fim do atendimento o link não responde")
    void tooLateAndExpiredLink() throws Exception {
        Shop s = shop();
        String token = confirmed(s, "09:00", email());
        clock.set(Instant.parse("2026-11-29T12:00:00Z"));     // exatamente 24 h antes: já fora
        assertError(action(s, "cancel", Map.of("token", token)), 409, "Too late to change");
        assertThat(body(action(s, "lookup", Map.of("token", token))).get("changeable").asBoolean()).isFalse();
        clock.set(Instant.parse("2026-11-29T11:55:00Z"));
        assertThat(body(action(s, "lookup", Map.of("token", token))).get("changeable").asBoolean()).as("5 min antes do prazo").isTrue();
        clock.set(Instant.parse("2026-11-30T12:30:00Z"));     // 09:30 local: o atendimento terminou
        assertError(action(s, "lookup", Map.of("token", token)), 404, "Booking not found");
        assertError(action(s, "lookup", Map.of("token", "x".repeat(43))), 404, "Booking not found");
        assertError(action(s, "lookup", Map.of("token", "")), 400, "Validation failed");
    }

    @Test
    @DisplayName("Remarcar: com a reserva do horário novo, o antigo cancela e o novo confirma; o mesmo link segue valendo; avisa")
    void reschedule() throws Exception {
        Shop s = shop();
        String email = email();
        String token = confirmed(s, "09:00", email);
        MockHttpServletResponse r = action(s, "reschedule", Map.of("token", token, "holdToken", hold(s, s.corte(), "10:30")));
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(body(r).get("start").asString()).isEqualTo(MONDAY + "T10:30:00-03:00");
        assertThat(body(r).get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(body(action(s, "lookup", Map.of("token", token))).get("booking").get("start").asString())
                .isEqualTo(MONDAY + "T10:30:00-03:00");
        assertThat(mail.lastTextTo(email)).contains("remarcado").contains("#" + token);
        JsonNode agenda = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null));
        assertThat(agenda.get("bookings").findValuesAsString("status")).containsExactlyInAnyOrder("CONFIRMED", "CANCELLED");
        assertThat(agenda.get("bookings").findValuesAsString("name")).containsOnly("Carla Dias");
    }

    @Test
    @DisplayName("Remarcar falhando não muda nada: outro serviço (400), horário que deixou de caber (409), reserva vencida (400)")
    void rescheduleFailuresKeepBooking() throws Exception {
        Shop s = shop();
        String token = confirmed(s, "09:00", email());
        assertError(action(s, "reschedule", Map.of("token", token, "holdToken", hold(s, s.barba(), "10:30"))), 400,
                "Reschedule keeps the service");
        String held = hold(s, s.corte(), "11:00");
        // o dono bloqueia o horário depois da reserva: o novo não cabe mais e o antigo fica como estava
        call(post(s.base() + "/time-off"), s.owner().token(), Map.of("professionalId", s.joao(), "start", MONDAY + "T11:00",
                "end", MONDAY + "T12:00"));
        assertError(action(s, "reschedule", Map.of("token", token, "holdToken", held)), 409, "Outside working hours");
        assertError(action(s, "reschedule", Map.of("token", token, "holdToken", "x".repeat(43))), 400, "Hold expired");
        JsonNode still = body(action(s, "lookup", Map.of("token", token)));
        assertThat(still.get("booking").get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(still.get("booking").get("start").asString()).isEqualTo(MONDAY + "T09:00:00-03:00");
        Map<String, Object> missing = new HashMap<>();
        missing.put("token", token);
        assertError(action(s, "reschedule", missing), 400, "Validation failed");
    }

    @Test
    @DisplayName("Token de outro estabelecimento não serve pelo link deste")
    void isolation() throws Exception {
        Shop ana = shop();
        Shop eve = shop();
        String token = confirmed(eve, "09:00", email());
        assertError(action(ana, "lookup", Map.of("token", token)), 404, "Booking not found");
        assertError(action(ana, "cancel", Map.of("token", token)), 404, "Booking not found");
        assertThat(body(action(eve, "lookup", Map.of("token", token))).get("booking").get("status").asString()).isEqualTo("CONFIRMED");
    }

    // ---------- complementos da revisão ----------

    @Autowired org.testcontainers.containers.GenericContainer<?> mailpit;

    @Test
    @DisplayName("Aviso que não sai depois de gravado não desfaz nada: cancelar responde 200 e fica cancelado")
    void noticeFailureKeepsResult() throws Exception {
        Shop s = shop();
        String email = email();
        String token = confirmed(s, "09:00", email);
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(mailpit.getContainerId()).exec();
        try {
            MockHttpServletResponse r = action(s, "cancel", Map.of("token", token));
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        } finally {
            docker.unpauseContainerCmd(mailpit.getContainerId()).exec();
        }
        assertThat(body(action(s, "lookup", Map.of("token", token))).get("booking").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(mail.countTo(email, "Agendamento cancelado")).isZero();
    }

    @Test
    @DisplayName("Confirmar duas vezes manda um e-mail de confirmação só; consultar um pendente: não dá para mudar")
    void singleConfirmationEmailAndPendingLookup() throws Exception {
        Shop s = shop();
        String email = email();
        String token = confirmed(s, "09:00", email);
        assertThat(action(s, "confirm", Map.of("token", token, "code", "000000")).getStatus()).isEqualTo(200);
        assertThat(mail.countTo(email, "Agendamento confirmado")).isEqualTo(1);

        String other = email();
        MockHttpServletResponse p = call(post(s.pub() + "/bookings"), null, Map.of("holdToken", hold(s, s.corte(), "10:30"),
                "customer", Map.of("name", "Carla Dias", "phone", "11987654321", "email", other)));
        JsonNode pending = body(action(s, "lookup", Map.of("token", body(p).get("token").asString())));
        assertThat(pending.get("booking").get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("changeable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Remarcar fora do prazo é 409; até 3 remarcações por agendamento (a 4ª é 409)")
    void rescheduleLimits() throws Exception {
        Shop s = shop();
        String token = confirmed(s, "09:00", email());
        for (String t : List.of("09:30", "10:00", "10:30")) {
            assertThat(action(s, "reschedule", Map.of("token", token, "holdToken", hold(s, s.corte(), t))).getStatus()).isEqualTo(200);
        }
        assertError(action(s, "reschedule", Map.of("token", token, "holdToken", hold(s, s.corte(), "11:00"))), 409, "Reschedule limit reached");
        String late = confirmed(s, "11:30", email());
        clock.set(Instant.parse("2026-11-29T15:00:00Z"));
        assertError(action(s, "reschedule", Map.of("token", late, "holdToken", hold(s, s.corte(), "09:00"))), 409, "Too late to change");
    }

    @Test
    @DisplayName("Remarcação entre estabelecimentos: token alheio 404, reserva alheia 400; o agendamento fica como estava")
    void rescheduleIsolation() throws Exception {
        Shop ana = shop();
        Shop eve = shop();
        String anaToken = confirmed(ana, "09:00", email());
        String eveToken = confirmed(eve, "09:00", email());
        assertError(action(ana, "reschedule", Map.of("token", eveToken, "holdToken", hold(ana, ana.corte(), "10:00"))), 404, "Booking not found");
        assertError(action(ana, "reschedule", Map.of("token", anaToken, "holdToken", hold(eve, eve.corte(), "10:00"))), 400, "Hold expired");
        assertThat(body(action(ana, "lookup", Map.of("token", anaToken))).get("booking").get("start").asString())
                .isEqualTo(MONDAY + "T09:00:00-03:00");
        assertThat(body(action(eve, "lookup", Map.of("token", eveToken))).get("booking").get("status").asString()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Política de outro estabelecimento: 404 para ler e para alterar")
    void policyIsolation() throws Exception {
        Shop ana = shop();
        Session eve = register("Eve", email(), "Loja da Eve");
        assertError(call(get(ana.base() + "/policy"), eve.token(), null), 404, "Tenant not found");
        assertError(call(put(ana.base() + "/policy"), eve.token(), Map.of("minNoticeMinutes", 0, "cancelUntilHours", 0, "bookingWindowDays", 1)),
                404, "Tenant not found");
    }

    @Test
    @DisplayName("Cancelar e remarcar ao mesmo tempo: um vale, o outro é 409")
    void concurrentCancelAndReschedule() throws Exception {
        Shop s = shop();
        String token = confirmed(s, "09:00", email());
        String held = hold(s, s.corte(), "10:30");
        List<Integer> statuses = PublicReservationIntegrationTest.parallel(2, i -> i == 0
                ? action(s, "cancel", Map.of("token", token)).getStatus()
                : action(s, "reschedule", Map.of("token", token, "holdToken", held)).getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    @DisplayName("Antecedência conferida de novo ao usar a reserva: o horário que entrou nela enquanto o cliente preenchia é recusado")
    void noticeRecheckedOnUse() throws Exception {
        clock.set(Instant.parse("2026-11-30T10:55:00Z"));     // 07:55 local; 09:00 ainda respeita 1 h de antecedência
        Shop s = shop();
        String held = hold(s, s.corte(), "09:00");
        clock.set(Instant.parse("2026-11-30T11:05:00Z"));     // 08:05: agora 09:00 está a menos de 1 h
        assertError(call(post(s.pub() + "/bookings"), null, Map.of("holdToken", held,
                "customer", Map.of("name", "Carla Dias", "phone", "11987654321", "email", email()))), 409, "Time slot not available");
    }
}
