package com.sskings.agendai.booking;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Agendamento pelo painel. Relógio: terça 24/11/2026 06:00 em São Paulo; a segunda seguinte é 30/11. */
class BookingIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    /** Estabelecimento pronto: Corte (30 + 10 de preparo, R$ 45) com o João, segunda das 09:00 às 12:00. */
    record Shop(Session owner, String corte, String joao, String beto) {
        String base() {
            return "/api/tenants/" + owner.tenantId();
        }
    }

    Shop shop() throws Exception {
        Session ana = register();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 10, "priceCents", 4500, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte)))).get("id").asString();
        String beto = body(call(post(base + "/professionals"), ana.token(), Map.of("name", "Beto", "active", true,
                "serviceIds", List.of(corte)))).get("id").asString();
        for (String p : List.of(joao, beto)) {
            call(put(base + "/professionals/" + p + "/working-hours"), ana.token(),
                    Map.of("intervals", List.of(Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        }
        return new Shop(ana, corte, joao, beto);
    }

    static Map<String, Object> booking(String professional, String service, String start, String phone) {
        Map<String, Object> customer = new HashMap<>();
        customer.put("name", "Carla Dias");
        customer.put("phone", phone);
        Map<String, Object> m = new HashMap<>();
        m.put("professionalId", professional);
        m.put("serviceId", service);
        m.put("start", start);
        m.put("customer", customer);
        return m;
    }

    MockHttpServletResponse book(Shop s, String professional, String start) throws Exception {
        return call(post(s.base() + "/bookings"), s.owner().token(), booking(professional, s.corte(), start, "(11) 98765-4321"));
    }

    JsonNode agenda(Shop s, String from, String to) throws Exception {
        MockHttpServletResponse r = call(get(s.base() + "/bookings").param("from", from).param("to", to), s.owner().token(), null);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return body(r);
    }

    List<String> slots(Shop s, String professional) throws Exception {
        JsonNode b = body(call(get(s.base() + "/availability").param("professionalId", professional).param("serviceId", s.corte())
                .param("date", MONDAY), s.owner().token(), null));
        List<String> out = new ArrayList<>();
        b.get("slots").forEach(n -> out.add(n.asString().substring(11, 16)));
        return out;
    }

    void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    Session renewed(Session s) throws Exception {
        MockHttpServletResponse r = refresh(s.refresh());
        assertThat(r.getStatus()).isEqualTo(200);
        return new Session(body(r).get("accessToken").asString(), refreshCookie(r), s.userId(), s.email(), s.tenantId());
    }

    @Test
    @DisplayName("Agenda um horário livre: guarda preço e fim do serviço, aparece na agenda e some dos horários livres (com o preparo)")
    void createAndList() throws Exception {
        Shop s = shop();
        Map<String, Object> req = booking(s.joao(), s.corte(), MONDAY + "T09:30", " (11) 98765-4321 ");
        ((Map<String, Object>) req.get("customer")).put("email", " Carla@Exemplo.COM ");
        req.put("note", "  Prefere  máquina 2 ");
        MockHttpServletResponse r = call(post(s.base() + "/bookings"), s.owner().token(), req);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        JsonNode b = body(r);
        assertThat(b.get("start").asString()).isEqualTo(MONDAY + "T09:30:00-03:00");
        assertThat(b.get("end").asString()).as("fim do atendimento, sem o preparo").isEqualTo(MONDAY + "T10:00:00-03:00");
        assertThat(b.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(b.get("priceCents").asInt()).isEqualTo(4500);
        assertThat(b.get("note").asString()).isEqualTo("Prefere máquina 2");
        assertThat(b.get("outsideHours").asBoolean()).isFalse();
        assertThat(b.get("professionalId").asString()).isEqualTo(s.joao());
        assertThat(b.get("serviceId").asString()).isEqualTo(s.corte());
        JsonNode c = b.get("customer");
        assertThat(c.get("name").asString()).isEqualTo("Carla Dias");
        assertThat(c.get("phone").asString()).isEqualTo("11987654321");
        assertThat(c.get("email").asString()).isEqualTo("carla@exemplo.com");

        // ocupa 09:30–10:10: some 09:00 (09:00–09:40 encostaria), 09:15, 09:30 e 09:45; 10:00 também (até 10:40)
        assertThat(slots(s, s.joao())).containsExactly("10:15", "10:30", "10:45", "11:00", "11:15");
        assertThat(slots(s, s.beto())).as("o Beto continua livre").hasSize(10);

        JsonNode list = agenda(s, MONDAY, MONDAY);
        assertThat(list.get("truncated").asBoolean()).isFalse();
        assertThat(list.get("bookings")).hasSize(1);
        assertThat(list.get("bookings").get(0)).isEqualTo(b);

        // mesmo telefone escrito de outro jeito = mesmo cliente (o nome do cadastro não muda)
        Map<String, Object> again = booking(s.beto(), s.corte(), MONDAY + "T09:30", "11987654321");
        ((Map<String, Object>) again.get("customer")).put("name", "carla DIAS");
        JsonNode second = body(call(post(s.base() + "/bookings"), s.owner().token(), again));
        assertThat(second.get("customer")).isEqualTo(c);
        // outro nome com o mesmo telefone: 409 sem revelar quem é o cliente cadastrado
        Map<String, Object> other = booking(s.beto(), s.corte(), MONDAY + "T10:30", "11987654321");
        ((Map<String, Object>) other.get("customer")).put("name", "Outra Pessoa");
        MockHttpServletResponse taken = call(post(s.base() + "/bookings"), s.owner().token(), other);
        assertError(taken, 409, BookingService.PHONE_TAKEN);
        assertThat(taken.getContentAsString()).doesNotContain("Carla").doesNotContain("carla@");
        assertThat(second.get("note").isNull()).isTrue();
    }

    @Test
    @DisplayName("Horário ocupado (inclusive pelo preparo) é 409; encostado depois do preparo, outro profissional ou cancelado libera")
    void conflicts() throws Exception {
        Shop s = shop();
        String first = body(book(s, s.joao(), MONDAY + "T09:30")).get("id").asString();
        assertError(book(s, s.joao(), MONDAY + "T09:30"), 409, BookingService.TAKEN);
        assertError(book(s, s.joao(), MONDAY + "T09:00"), 409, BookingService.TAKEN);     // 09:00–09:40 encosta
        assertError(book(s, s.joao(), MONDAY + "T10:05"), 409, BookingService.TAKEN);     // dentro do preparo
        assertThat(book(s, s.joao(), MONDAY + "T10:10").getStatus()).as("logo depois do preparo").isEqualTo(201);
        assertThat(book(s, s.beto(), MONDAY + "T09:30").getStatus()).isEqualTo(201);
        assertThat(slots(s, s.joao())).doesNotContain("09:30");
        assertThat(call(patch(s.base() + "/bookings/" + first), s.owner().token(), Map.of("status", "CANCELLED")).getStatus()).isEqualTo(200);
        assertThat(slots(s, s.joao())).as("cancelar devolve o horário aos livres").contains("09:15", "09:30");
        assertThat(book(s, s.joao(), MONDAY + "T09:20").getStatus()).as("o cancelado libera o horário").isEqualTo(201);
        assertThat(agenda(s, MONDAY, MONDAY).get("bookings").findValuesAsString("status"))
                .containsExactlyInAnyOrder("CANCELLED", "CONFIRMED", "CONFIRMED", "CONFIRMED");
    }

    @Test
    @DisplayName("100 pedidos simultâneos para o mesmo horário → exatamente 1 agendamento; 99 recebem 409")
    void hundredConcurrentRequests() throws Exception {
        Shop s = shop();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(100);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String phone = "1199999" + String.format("%04d", i);
                results.add(pool.submit(() -> {
                    start.await();
                    MockHttpServletResponse r = call(post(s.base() + "/bookings"), s.owner().token(),
                            booking(s.joao(), s.corte(), MONDAY + "T10:00", phone));
                    return r.getStatus() == 201 ? "201" : r.getStatus() + " " + body(r).get("message").asString();
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> r : results) statuses.add(r.get(60, TimeUnit.SECONDS));
            assertThat(statuses).filteredOn("201"::equals).hasSize(1);
            assertThat(statuses).filteredOn(("409 " + BookingService.TAKEN)::equals).hasSize(99);
        } finally {
            pool.shutdownNow();
        }
        assertThat(agenda(s, MONDAY, MONDAY).get("bookings")).hasSize(1);
        assertThat(ownerCount("SELECT count(*) FROM customers WHERE tenant_id = ?", UUID.fromString(s.owner().tenantId())))
                .as("os 99 recusados não deixam cliente para trás (dado pessoal sem agendamento)").isEqualTo(1);
    }

    @Test
    @DisplayName("Regras: no passado, fora do passo, além de 180 dias, fora do expediente, em bloqueio, inativo ou serviço que ele não faz")
    void rules() throws Exception {
        Shop s = shop();
        assertError(book(s, s.joao(), "2026-11-23T09:00"), 400, BookingService.PAST);
        assertError(book(s, s.joao(), MONDAY + "T09:03"), 400, BookingService.FIVE_MINUTE_STEPS);
        assertError(book(s, s.joao(), MONDAY + "T09:00:30"), 400, BookingService.FIVE_MINUTE_STEPS);
        assertError(book(s, s.joao(), MONDAY + "T09:00:00.1"), 400, BookingService.FIVE_MINUTE_STEPS);
        assertError(book(s, s.joao(), "2027-05-24T09:00"), 400, BookingService.DATE_OUT_OF_RANGE);   // hoje + 181 (segunda)
        assertError(book(s, s.joao(), MONDAY + "T08:55"), 409, BookingService.OUTSIDE_HOURS);
        assertError(book(s, s.joao(), MONDAY + "T11:25"), 409, BookingService.OUTSIDE_HOURS);      // preparo passaria das 12:00
        assertThat(book(s, s.joao(), MONDAY + "T11:20").getStatus()).as("termina o preparo às 12:00").isEqualTo(201);
        assertError(book(s, s.joao(), "2026-12-01T09:00"), 409, BookingService.OUTSIDE_HOURS);     // terça: sem expediente
        call(post(s.base() + "/time-off"), s.owner().token(), Map.of("professionalId", s.beto(), "start", MONDAY + "T10:00", "end", MONDAY + "T10:30"));
        assertError(book(s, s.beto(), MONDAY + "T09:30"), 409, BookingService.OUTSIDE_HOURS);      // 09:30–10:10 encosta no bloqueio
        assertThat(book(s, s.joao(), MONDAY + "T10:00").getStatus()).as("o bloqueio do Beto não afeta o João").isEqualTo(201);
        call(post(s.base() + "/time-off"), s.owner().token(), Map.of("start", "2026-12-07T00:00", "end", "2026-12-08T00:00"));
        assertError(book(s, s.joao(), "2026-12-07T09:00"), 409, BookingService.OUTSIDE_HOURS);     // feriado do estabelecimento

        String barba = body(call(post(s.base() + "/services"), s.owner().token(), Map.of("name", "Barba", "durationMinutes", 20,
                "bufferMinutes", 0, "priceCents", 0, "active", true))).get("id").asString();
        assertError(call(post(s.base() + "/bookings"), s.owner().token(), booking(s.joao(), barba, MONDAY + "T09:00", "11987654321")),
                400, "Professional does not perform this service");
        call(put(s.base() + "/services/" + s.corte()), s.owner().token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 10, "priceCents", 4500, "active", false));
        assertError(book(s, s.joao(), MONDAY + "T09:00"), 409, BookingService.INACTIVE);
        assertError(call(post(s.base() + "/bookings"), s.owner().token(), booking(UUID.randomUUID().toString(), s.corte(), MONDAY + "T09:00", "11987654321")),
                404, "Professional not found");
    }

    @Test
    @DisplayName("Dados do cliente: nome, telefone de 10 a 15 dígitos, e-mail válido; nada a mais no JSON")
    void validation() throws Exception {
        Shop s = shop();
        String url = s.base() + "/bookings";
        assertError(call(post(url), s.owner().token(), booking(s.joao(), s.corte(), MONDAY + "T09:00", "123 456 789")), 400, BookingService.INVALID_PHONE);
        assertError(call(post(url), s.owner().token(), booking(s.joao(), s.corte(), MONDAY + "T09:00", "+55 (11) 98765-4321-001")), 400,
                BookingService.INVALID_PHONE);
        for (String phone : new String[]{"11 9876a4321", "1198765432\n", " "}) {
            assertError(call(post(url), s.owner().token(), booking(s.joao(), s.corte(), MONDAY + "T09:00", phone)), 400, "Validation failed");
        }
        Map<String, Object> badEmail = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        ((Map<String, Object>) badEmail.get("customer")).put("email", "carla@");
        assertError(call(post(url), s.owner().token(), badEmail), 400, "Validation failed");
        Map<String, Object> badName = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        ((Map<String, Object>) badName.get("customer")).put("name", "Carla\u0000");
        assertError(call(post(url), s.owner().token(), badName), 400, "Validation failed");
        Map<String, Object> longNote = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        longNote.put("note", "x".repeat(201));
        assertError(call(post(url), s.owner().token(), longNote), 400, "Validation failed");
        for (String field : new String[]{"professionalId", "serviceId", "start", "customer"}) {
            Map<String, Object> missing = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
            missing.remove(field);
            assertError(call(post(url), s.owner().token(), missing), 400, "Validation failed");
        }
        Map<String, Object> extra = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        extra.put("status", "COMPLETED");
        assertError(call(post(url), s.owner().token(), extra), 400, "Malformed JSON request");
        Map<String, Object> blankEmail = booking(s.joao(), s.corte(), MONDAY + "T09:00", "+55 11 98765-4321");
        ((Map<String, Object>) blankEmail.get("customer")).put("email", "  ");
        blankEmail.put("note", "  ");
        JsonNode ok = body(call(post(url), s.owner().token(), blankEmail));
        assertThat(ok.get("customer").get("email").isNull()).isTrue();
        assertThat(ok.get("customer").get("phone").asString()).isEqualTo("5511987654321");
        assertThat(ok.get("note").isNull()).as("observação em branco vira vazia").isTrue();
        assertThat(BookingService.digits("1198765432")).isEqualTo("1198765432");
    }

    @Test
    @DisplayName("Status: cancela antes; atendido e falta só depois de começar; finais não mudam")
    void status() throws Exception {
        Shop s = shop();
        String a = body(book(s, s.joao(), MONDAY + "T09:00")).get("id").asString();
        String b = body(book(s, s.joao(), MONDAY + "T10:00")).get("id").asString();
        String c = body(book(s, s.beto(), MONDAY + "T09:00")).get("id").asString();
        String url = s.base() + "/bookings/";
        assertError(call(patch(url + a), s.owner().token(), Map.of("status", "COMPLETED")), 409, BookingService.NOT_STARTED);
        assertError(call(patch(url + a), s.owner().token(), Map.of("status", "NO_SHOW")), 409, BookingService.NOT_STARTED);
        assertError(call(patch(url + a), s.owner().token(), Map.of("status", "CONFIRMED")), 409, BookingService.INVALID_STATUS);
        JsonNode cancelled = body(call(patch(url + c), s.owner().token(), Map.of("status", "CANCELLED")));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("customer").get("name").asString()).isEqualTo("Carla Dias");
        assertError(call(patch(url + c), s.owner().token(), Map.of("status", "CANCELLED")), 409, BookingService.INVALID_STATUS);

        clock.set(Instant.parse("2026-11-30T12:00:00Z"));   // segunda 09:00 local: o das 09:00 começou
        Session owner = renewed(s.owner());
        assertThat(body(call(patch(url + a), owner.token(), Map.of("status", "COMPLETED"))).get("status").asString()).isEqualTo("COMPLETED");
        assertError(call(patch(url + a), owner.token(), Map.of("status", "NO_SHOW")), 409, BookingService.INVALID_STATUS);
        assertError(call(patch(url + b), owner.token(), Map.of("status", "NO_SHOW")), 409, BookingService.NOT_STARTED);
        clock.advance(Duration.ofHours(1));
        owner = renewed(owner);
        assertThat(body(call(patch(url + b), owner.token(), Map.of("status", "NO_SHOW"))).get("status").asString()).isEqualTo("NO_SHOW");
        assertError(call(patch(url + b), owner.token(), Map.of("status", "CANCELLED")), 409, BookingService.INVALID_STATUS);
        assertError(call(patch(url + UUID.randomUUID()), owner.token(), Map.of("status", "CANCELLED")), 404, BookingService.NOT_FOUND);
        assertError(call(patch(url + a), owner.token(), Map.of("status", "PAID")), 400, "Malformed JSON request");
        assertError(call(patch(url + a), owner.token(), "{}"), 400, "Validation failed");
    }

    @Test
    @DisplayName("Mudar o expediente ou bloquear depois não apaga o agendamento: ele aparece como fora do expediente")
    void scheduleChangesKeepBookings() throws Exception {
        Shop s = shop();
        book(s, s.joao(), MONDAY + "T09:00");
        book(s, s.beto(), MONDAY + "T09:00");
        call(put(s.base() + "/professionals/" + s.joao() + "/working-hours"), s.owner().token(), Map.of("intervals", List.of()));
        call(post(s.base() + "/time-off"), s.owner().token(), Map.of("professionalId", s.beto(), "start", MONDAY + "T09:30", "end", MONDAY + "T10:00"));
        JsonNode list = agenda(s, MONDAY, MONDAY).get("bookings");
        assertThat(list).hasSize(2);
        assertThat(list.findValuesAsString("status")).containsOnly("CONFIRMED");
        assertThat(list.findValues("outsideHours")).extracting(JsonNode::asBoolean).containsExactly(true, true);
        assertThat(slots(s, s.beto())).as("e continua ocupando o horário").doesNotContain("09:00", "09:15");
    }

    @Test
    @DisplayName("Agenda: dias do fuso local (a noite de segunda é segunda), filtro por profissional, até 7 dias e datas razoáveis")
    void agendaPeriod() throws Exception {
        Shop s = shop();
        call(put(s.base() + "/professionals/" + s.joao() + "/working-hours"), s.owner().token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "21:00", "end", "24:00"))));
        book(s, s.joao(), MONDAY + "T23:00");   // terça 02:00 em UTC
        book(s, s.beto(), MONDAY + "T09:00");
        assertThat(agenda(s, MONDAY, MONDAY).get("bookings").findValuesAsString("start"))
                .containsExactly(MONDAY + "T09:00:00-03:00", MONDAY + "T23:00:00-03:00");
        assertThat(agenda(s, "2026-12-01", "2026-12-01").get("bookings")).isEmpty();
        assertThat(agenda(s, "2026-11-29", "2026-11-29").get("bookings")).isEmpty();
        assertThat(agenda(s, "2026-11-24", "2026-11-30").get("bookings")).as("semana").hasSize(2);
        JsonNode mine = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY).param("professionalId", s.joao()),
                s.owner().token(), null));
        assertThat(mine.get("bookings").findValuesAsString("professionalId")).containsExactly(s.joao());

        String url = s.base() + "/bookings";
        for (String[] q : new String[][]{{"2026-12-01", "2026-11-30"}, {"2026-11-24", "2026-12-01"}, {"2025-11-22", "2025-11-22"},
                {"2027-05-24", "2027-05-24"}}) {
            assertError(call(get(url).param("from", q[0]).param("to", q[1]), s.owner().token(), null), 400, BookingService.INVALID_PERIOD);
        }
        assertThat(call(get(url).param("from", "2025-11-23").param("to", "2025-11-23"), s.owner().token(), null).getStatus()).isEqualTo(200);
        assertThat(call(get(url).param("from", "2027-05-23").param("to", "2027-05-23"), s.owner().token(), null).getStatus()).isEqualTo(200);
        assertError(call(get(url).param("from", MONDAY), s.owner().token(), null), 400, "Invalid parameter");
    }

    @Test
    @DisplayName("Agenda: teto de 500; os ativos vêm antes dos cancelados, que nunca escondem um agendamento de verdade")
    void agendaLimit() throws Exception {
        Shop s = shop();
        String real = body(book(s, s.joao(), MONDAY + "T11:00")).get("id").asString();   // cria o cliente e o ativo
        String insert = """
                INSERT INTO bookings (id, tenant_id, professional_id, service_id, customer_id, starts_at, ends_at, blocked_until, status,
                                      price_cents, created_at, updated_at)
                SELECT gen_random_uuid(), b.tenant_id, b.professional_id, b.service_id, b.customer_id,
                       timestamptz '2026-11-30 12:00Z' + (n || ' seconds')::interval, timestamptz '2026-11-30 12:01Z' + (n || ' seconds')::interval,
                       timestamptz '2026-11-30 12:01Z' + (n || ' seconds')::interval, 'CANCELLED', 0, now(), now()
                FROM bookings b, generate_series(?, ?) n WHERE b.id = ?""";
        ownerSql(insert, 1, BookingService.MAX_AGENDA - 1, UUID.fromString(real));
        JsonNode full = agenda(s, MONDAY, MONDAY);
        assertThat(full.get("truncated").asBoolean()).as("exatamente no teto").isFalse();
        assertThat(full.get("bookings")).hasSize(BookingService.MAX_AGENDA);
        ownerSql(insert, BookingService.MAX_AGENDA, BookingService.MAX_AGENDA, UUID.fromString(real));
        JsonNode cut = agenda(s, MONDAY, MONDAY);
        assertThat(cut.get("truncated").asBoolean()).isTrue();
        assertThat(cut.get("bookings")).hasSize(BookingService.MAX_AGENDA);
        assertThat(cut.get("bookings").get(0).get("id").asString()).as("o ativo das 11:00 vem antes dos cancelados das 09:00").isEqualTo(real);
    }

    @Test
    @DisplayName("Equipe agenda e vê a agenda; outro estabelecimento: 404 (nem agendamento, nem profissional, nem serviço alheio)")
    void access() throws Exception {
        Shop s = shop();
        Shop eve = shop();
        String evesBooking = body(book(eve, eve.joao(), MONDAY + "T09:00")).get("id").asString();
        Session bia = register("Bia", email(), "Outro");
        addMember(s.owner().tenantId(), bia.userId(), "PROFESSIONAL");
        MockHttpServletResponse byTeam = call(post(s.base() + "/bookings"), bia.token(), booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321"));
        assertThat(byTeam.getStatus()).isEqualTo(201);
        assertThat(body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), bia.token(), null)).get("bookings")).hasSize(1);

        assertError(call(patch(s.base() + "/bookings/" + evesBooking), s.owner().token(), Map.of("status", "CANCELLED")), 404, BookingService.NOT_FOUND);
        assertError(call(post(s.base() + "/bookings"), s.owner().token(), booking(eve.joao(), s.corte(), MONDAY + "T10:00", "11987654321")),
                404, "Professional not found");
        assertError(call(post(s.base() + "/bookings"), s.owner().token(), booking(s.joao(), eve.corte(), MONDAY + "T10:00", "11987654321")),
                404, "Service not found");
        assertError(call(get(eve.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null), 404, "Tenant not found");
        assertThat(body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY).param("professionalId", eve.joao()),
                s.owner().token(), null)).get("bookings")).as("filtro com profissional alheio não traz nada").isEmpty();
        assertThat(agenda(eve, MONDAY, MONDAY).get("bookings").get(0).get("status").asString()).as("o da Eve intacto").isEqualTo("CONFIRMED");
        assertThat(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), null, null).getStatus()).isEqualTo(401);
    }

    // ---------- complementos das revisões ----------

    @Test
    @DisplayName("Horários livres descontam o agendamento que começou antes de agora e ainda ocupa o profissional")
    void busyAcrossNow() throws Exception {
        Shop s = shop();
        book(s, s.joao(), MONDAY + "T09:00");                 // ocupa até 09:40
        clock.set(Instant.parse("2026-11-30T12:10:00Z"));     // 09:10 local
        Shop now = new Shop(renewed(s.owner()), s.corte(), s.joao(), s.beto());
        assertThat(slots(now, now.joao())).startsWith("09:45");
    }

    @Test
    @DisplayName("Mesmo cliente novo em pedidos simultâneos: um cadastro só, todos agendados")
    void sameNewCustomerConcurrently() throws Exception {
        Shop s = shop();
        String[][] slots = {{s.joao(), "09:00"}, {s.joao(), "09:40"}, {s.joao(), "10:20"}, {s.joao(), "11:00"},
                {s.beto(), "09:00"}, {s.beto(), "09:40"}, {s.beto(), "10:20"}, {s.beto(), "11:00"}};
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(slots.length);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String[] slot : slots) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call(post(s.base() + "/bookings"), s.owner().token(), booking(slot[0], s.corte(), MONDAY + "T" + slot[1], "21955554444"))
                            .getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) assertThat(r.get(60, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ownerCount("SELECT count(*) FROM customers WHERE tenant_id = ?", UUID.fromString(s.owner().tenantId()))).isEqualTo(1);
    }

    @Test
    @DisplayName("Horizonte de 180 dias no fuso local: às 22:00 de 24/11 em São Paulo (já 25/11 em UTC), 23/05/2027 é o último dia")
    void horizonInLocalZone() throws Exception {
        clock.set(Instant.parse("2026-11-25T01:00:00Z"));
        Shop s = shop();
        assertError(book(s, s.joao(), "2027-05-24T09:00"), 400, BookingService.DATE_OUT_OF_RANGE);    // segunda, com expediente
        assertError(call(get(s.base() + "/bookings").param("from", "2027-05-24").param("to", "2027-05-24"), s.owner().token(), null),
                400, BookingService.INVALID_PERIOD);
        call(put(s.base() + "/professionals/" + s.joao() + "/working-hours"), s.owner().token(),
                Map.of("intervals", List.of(Map.of("weekday", 7, "start", "09:00", "end", "12:00"))));
        assertThat(book(s, s.joao(), "2027-05-23T09:00").getStatus()).as("último dia aceito (domingo)").isEqualTo(201);
    }

    @Test
    @DisplayName("Horário de verão: horário local que não existe no dia é recusado, nunca deslocado em silêncio")
    void daylightSavingGap() throws Exception {
        Shop s = shop();
        ownerSql("UPDATE tenants SET time_zone = 'America/New_York' WHERE id = ?", UUID.fromString(s.owner().tenantId()));
        call(put(s.base() + "/professionals/" + s.joao() + "/working-hours"), s.owner().token(),
                Map.of("intervals", List.of(Map.of("weekday", 7, "start", "00:00", "end", "24:00"))));
        assertError(book(s, s.joao(), "2027-03-14T02:30"), 400, BookingService.AMBIGUOUS_TIME);
        JsonNode ok = body(book(s, s.joao(), "2027-03-14T03:30"));
        assertThat(ok.get("start").asString()).isEqualTo("2027-03-14T03:30:00-04:00");
    }

    @Test
    @DisplayName("Tamanhos e caracteres invisíveis: nome até 100, e-mail até 254, sem NEL nem inversão de sentido")
    void customerLimits() throws Exception {
        Shop s = shop();
        String url = s.base() + "/bookings";
        Map<String, Object> longName = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        ((Map<String, Object>) longName.get("customer")).put("name", "x".repeat(101));
        assertError(call(post(url), s.owner().token(), longName), 400, "Validation failed");
        Map<String, Object> longEmail = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        ((Map<String, Object>) longEmail.get("customer")).put("email", "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62));
        assertError(call(post(url), s.owner().token(), longEmail), 400, "Validation failed");
        for (String[] bad : new String[][]{{"name", "Carla‮said"}, {"email", "carla@exemplo.com"}, {"name", "Carla"}}) {
            Map<String, Object> req = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
            ((Map<String, Object>) req.get("customer")).put(bad[0], bad[1]);
            assertError(call(post(url), s.owner().token(), req), 400, "Validation failed");
        }
        Map<String, Object> max = booking(s.joao(), s.corte(), MONDAY + "T09:00", "11987654321");
        ((Map<String, Object>) max.get("customer")).put("name", "x".repeat(100));
        assertThat(call(post(url), s.owner().token(), max).getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("Duas mudanças de status ao mesmo tempo: só uma vale; a outra recebe 409")
    void concurrentStatusChange() throws Exception {
        Shop s = shop();
        String id = body(book(s, s.joao(), MONDAY + "T09:00")).get("id").asString();
        clock.set(Instant.parse("2026-11-30T12:30:00Z"));
        Session owner = renewed(s.owner());
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> done = pool.submit(() -> {
                start.await();
                return call(patch(s.base() + "/bookings/" + id), owner.token(), Map.of("status", "COMPLETED")).getStatus();
            });
            Future<Integer> cancel = pool.submit(() -> {
                start.await();
                return call(patch(s.base() + "/bookings/" + id), owner.token(), Map.of("status", "CANCELLED")).getStatus();
            });
            start.countDown();
            assertThat(List.of(done.get(30, TimeUnit.SECONDS), cancel.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Teto de agendamentos criados por estabelecimento em 24 h; depois de um dia volta a aceitar")
    void dailyLimit() throws Exception {
        Shop s = shop();
        String first = body(book(s, s.joao(), MONDAY + "T09:00")).get("id").asString();
        ownerSql("""
                INSERT INTO bookings (id, tenant_id, professional_id, service_id, customer_id, starts_at, ends_at, blocked_until, status,
                                      price_cents, created_at, updated_at)
                SELECT gen_random_uuid(), b.tenant_id, b.professional_id, b.service_id, b.customer_id, b.starts_at, b.ends_at, b.blocked_until,
                       'CANCELLED', 0, timestamptz '2026-11-24 09:00Z', now()
                FROM bookings b, generate_series(1, ?) n WHERE b.id = ?""", BookingService.MAX_CREATED_PER_DAY - 1, UUID.fromString(first));
        assertError(book(s, s.joao(), MONDAY + "T10:00"), 409, BookingService.DAILY_LIMIT);
        clock.advance(Duration.ofHours(25));
        Shop later = new Shop(renewed(s.owner()), s.corte(), s.joao(), s.beto());
        assertThat(book(later, later.joao(), MONDAY + "T10:00").getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("Mudar o serviço depois não mexe no agendamento; cancelar depois do fim do atendimento é permitido")
    void bookingIsASnapshot() throws Exception {
        Shop s = shop();
        String id = body(book(s, s.joao(), MONDAY + "T09:00")).get("id").asString();
        call(put(s.base() + "/services/" + s.corte()), s.owner().token(), Map.of("name", "Corte", "durationMinutes", 60,
                "bufferMinutes", 0, "priceCents", 9900, "active", true));
        JsonNode b = agenda(s, MONDAY, MONDAY).get("bookings").get(0);
        assertThat(b.get("priceCents").asInt()).isEqualTo(4500);
        assertThat(b.get("end").asString()).isEqualTo(MONDAY + "T09:30:00-03:00");
        clock.set(Instant.parse("2026-11-30T13:00:00Z"));     // 10:00 local: já terminou
        JsonNode cancelled = body(call(patch(s.base() + "/bookings/" + id), renewed(s.owner()).token(), Map.of("status", "CANCELLED")));
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("start").asString()).isEqualTo(MONDAY + "T09:00:00-03:00");
        assertThat(cancelled.get("outsideHours").asBoolean()).isFalse();
        assertThat(cancelled.get("priceCents").asInt()).isEqualTo(4500);
    }
}
