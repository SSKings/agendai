package com.sskings.agendai.schedule;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Expediente, bloqueios e horários livres pela API. O relógio começa em 2026-11-24T09:00Z (terça, 06:00 em São Paulo);
 * a segunda seguinte é 2026-11-30.
 */
class ScheduleIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    static Map<String, Object> interval(int weekday, String start, String end) {
        Map<String, Object> m = new HashMap<>();
        m.put("weekday", weekday);
        m.put("start", start);
        m.put("end", end);
        return m;
    }

    static Map<String, Object> week(Map<?, ?>... intervals) {
        return Map.of("intervals", List.of(intervals));
    }

    static Map<String, Object> timeOff(String professionalId, String start, String end, String reason) {
        Map<String, Object> m = new HashMap<>();
        m.put("professionalId", professionalId);
        m.put("start", start);
        m.put("end", end);
        m.put("reason", reason);
        return m;
    }

    private static String base(Session s) {
        return "/api/tenants/" + s.tenantId();
    }

    private static String hours(Session s, String professional) {
        return base(s) + "/professionals/" + professional + "/working-hours";
    }

    private String service(Session s, String name, int duration, int buffer) throws Exception {
        Map<String, Object> m = new HashMap<>(Map.of("name", name, "durationMinutes", duration, "bufferMinutes", buffer, "priceCents", 0, "active", true));
        MockHttpServletResponse r = call(post(base(s) + "/services"), s.token(), m);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private String professional(Session s, String name, String... services) throws Exception {
        Map<String, Object> m = Map.of("name", name, "active", true, "serviceIds", List.of(services));
        MockHttpServletResponse r = call(post(base(s) + "/professionals"), s.token(), m);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r).get("id").asString();
    }

    private void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    private List<String> slots(Session s, String professional, String service, String date) throws Exception {
        MockHttpServletResponse r = call(get(base(s) + "/availability").param("professionalId", professional)
                .param("serviceId", service).param("date", date), s.token(), null);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        JsonNode b = body(r);
        assertThat(b.get("date").asString()).isEqualTo(date);
        assertThat(b.get("timeZone").asString()).isEqualTo("America/Sao_Paulo");
        List<String> out = new ArrayList<>();
        b.get("slots").forEach(n -> out.add(n.asString()));
        return out;
    }

    /** O relógio andou além dos 15 min do access token: renova pela rota de refresh, como o front faz. */
    private Session renewed(Session s) throws Exception {
        MockHttpServletResponse r = refresh(s.refresh());
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        return new Session(body(r).get("accessToken").asString(), refreshCookie(r), s.userId(), s.email(), s.tenantId());
    }

    private static List<String> at(String date, String... times) {
        List<String> out = new ArrayList<>();
        for (String t : times) out.add(date + "T" + t + ":00-03:00");
        return out;
    }

    // ---------- expediente ----------

    @Test
    @DisplayName("Expediente: o dono define a semana (com pausa para o almoço), lê em ordem e troca tudo de uma vez")
    void workingHoursReplaceWeek() throws Exception {
        Session ana = register();
        String joao = professional(ana, "João");
        assertThat(body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals")).isEmpty();

        MockHttpServletResponse saved = call(put(hours(ana, joao)), ana.token(), week(
                interval(6, "08:00", "12:00"), interval(1, "13:00", "18:00"), interval(1, "09:00", "12:00")));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        JsonNode list = body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals");
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("weekday").asInt()).isEqualTo(1);
        assertThat(list.get(0).get("start").asString()).isEqualTo("09:00");
        assertThat(list.get(0).get("end").asString()).isEqualTo("12:00");
        assertThat(list.get(1).get("start").asString()).isEqualTo("13:00");
        assertThat(list.get(2).get("weekday").asInt()).isEqualTo(6);
        assertThat(body(saved).get("intervals")).isEqualTo(list);

        call(put(hours(ana, joao)), ana.token(), week(interval(2, "00:00", "24:00")));
        JsonNode replaced = body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals");
        assertThat(replaced).hasSize(1);
        assertThat(replaced.get(0).get("weekday").asInt()).isEqualTo(2);
        assertThat(replaced.get(0).get("start").asString()).isEqualTo("00:00");
        assertThat(replaced.get(0).get("end").asString()).as("dia inteiro").isEqualTo("24:00");

        assertThat(call(put(hours(ana, joao)), ana.token(), week()).getStatus()).isEqualTo(200);
        assertThat(body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals")).as("semana vazia = de folga").isEmpty();
    }

    @Test
    @DisplayName("Expediente: fim depois do início, faixas sem sobreposição (encostadas valem), passos de 5 min e formato HH:mm")
    void workingHoursValidation() throws Exception {
        Session ana = register();
        String joao = professional(ana, "João");
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(1, "12:00", "12:00"))), 400, ScheduleService.END_BEFORE_START);
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(1, "12:00", "09:00"))), 400, ScheduleService.END_BEFORE_START);
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(1, "24:00", "24:00"))), 400, ScheduleService.END_BEFORE_START);
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:00", "12:00"), interval(1, "11:55", "14:00"))),
                400, ScheduleService.OVERLAP);
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(3, "09:00", "18:00"), interval(3, "10:00", "11:00"))),
                400, ScheduleService.OVERLAP);
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:03", "12:00"))), 400, ScheduleService.FIVE_MINUTE_STEPS);
        assertError(call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:00", "12:01"))), 400, ScheduleService.FIVE_MINUTE_STEPS);
        assertThat(call(put(hours(ana, joao)), ana.token(),
                week(interval(1, "09:00", "12:00"), interval(1, "12:00", "13:00"), interval(2, "09:00", "12:00"))).getStatus())
                .as("faixas encostadas e o mesmo horário em outro dia").isEqualTo(200);
        assertThat(body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals")).hasSize(3);

        for (Map<String, Object> bad : List.of(interval(0, "09:00", "10:00"), interval(8, "09:00", "10:00"),
                interval(1, "9:00", "10:00"), interval(1, "09:00", "24:05"), interval(1, "09:60", "10:00"),
                interval(1, "09:00", "10:00\n"))) {
            assertError(call(put(hours(ana, joao)), ana.token(), week(bad)), 400, "Validation failed");
        }
        for (String field : new String[]{"weekday", "start", "end"}) {
            Map<String, Object> missing = interval(1, "09:00", "10:00");
            missing.remove(field);
            assertError(call(put(hours(ana, joao)), ana.token(), week(missing)), 400, "Validation failed");
        }
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < ScheduleService.MAX_INTERVALS + 1; i++) tooMany.add(interval(i % 7 + 1, String.format("%02d:00", i / 7), String.format("%02d:30", i / 7)));
        assertError(call(put(hours(ana, joao)), ana.token(), Map.of("intervals", tooMany)), 400, "Validation failed");
        assertThat(call(put(hours(ana, joao)), ana.token(), Map.of("intervals", tooMany.subList(0, ScheduleService.MAX_INTERVALS))).getStatus())
                .as("exatamente o teto").isEqualTo(200);
        assertError(call(put(hours(ana, joao)), ana.token(), "{}"), 400, "Validation failed");
        assertError(call(put(hours(ana, joao)), ana.token(), "{\"intervals\":[null]}"), 400, "Validation failed");
        assertThat(body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals")).as("nada mudou com os pedidos recusados")
                .hasSize(ScheduleService.MAX_INTERVALS);
    }

    @Test
    @DisplayName("Expediente: profissional inexistente ou de outro estabelecimento é 404; equipe lê, só o dono altera")
    void workingHoursAccess() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String joao = professional(ana, "João");
        String eva = professional(eve, "Eva");
        call(put(hours(eve, eva)), eve.token(), week(interval(1, "09:00", "10:00")));

        assertError(call(get(hours(ana, UUID.randomUUID().toString())), ana.token(), null), 404, "Professional not found");
        assertError(call(get(hours(ana, eva)), ana.token(), null), 404, "Professional not found");
        assertError(call(put(hours(ana, eva)), ana.token(), week(interval(1, "09:00", "10:00"))), 404, "Professional not found");
        assertError(call(get(hours(eve, joao)), ana.token(), null), 404, "Tenant not found");
        assertThat(body(call(get(hours(eve, eva)), eve.token(), null)).get("intervals")).as("o da Eve continua lá").hasSize(1);

        Session bia = register("Bia", email(), "Outro");
        addMember(ana.tenantId(), bia.userId(), "PROFESSIONAL");
        call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:00", "10:00")));
        assertThat(body(call(get(hours(ana, joao)), bia.token(), null)).get("intervals")).hasSize(1);
        assertError(call(put(hours(ana, joao)), bia.token(), week()), 403, "Only owners can do this");
        assertThat(call(get(hours(ana, joao)), null, null).getStatus()).isEqualTo(401);
    }

    // ---------- bloqueios ----------

    @Test
    @DisplayName("Bloqueios: o dono cria (de um profissional ou do estabelecimento inteiro), lista por período e apaga")
    void timeOffCrud() throws Exception {
        Session ana = register();
        String joao = professional(ana, "João");
        MockHttpServletResponse holiday = call(post(base(ana) + "/time-off"), ana.token(),
                timeOff(null, "2026-12-25T00:00", "2026-12-26T00:00", "  Natal\r\n "));
        assertThat(holiday.getStatus()).as(holiday.getContentAsString()).isEqualTo(201);
        JsonNode h = body(holiday);
        assertThat(h.get("professionalId").isNull()).isTrue();
        assertThat(h.get("start").asString()).isEqualTo("2026-12-25T00:00:00");
        assertThat(h.get("end").asString()).isEqualTo("2026-12-26T00:00:00");
        assertThat(h.get("reason").asString()).isEqualTo("Natal");
        JsonNode lunch = body(call(post(base(ana) + "/time-off"), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T13:00", "  ")));
        assertThat(lunch.get("professionalId").asString()).isEqualTo(joao);
        assertThat(lunch.get("reason").isNull()).as("motivo em branco vira vazio").isTrue();
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(joao, "2026-11-28T10:00", "2026-11-28T11:00", null));

        JsonNode december = body(call(get(base(ana) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-31"), ana.token(), null));
        assertThat(december.findValuesAsString("start")).containsExactly("2026-12-01T12:00:00", "2026-12-25T00:00:00");
        // o período é de dias inteiros no fuso do estabelecimento: 25/12 pega o feriado; 26/12 já não pega
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-25").param("to", "2026-12-25"), ana.token(), null))).hasSize(1);
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-26").param("to", "2026-12-26"), ana.token(), null))).isEmpty();
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-11-30").param("to", "2026-11-30"), ana.token(), null))).isEmpty();
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-24").param("to", "2026-12-24"), ana.token(), null)))
                .as("o Natal começa exatamente quando o dia 24 termina").isEmpty();
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(joao, "2026-12-03T00:00", "2026-12-10T00:00", "Férias"));
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-05").param("to", "2026-12-05"), ana.token(), null))
                .findValuesAsString("reason")).as("bloqueio que contém o período inteiro").containsExactly("Férias");

        assertThat(call(delete(base(ana) + "/time-off/" + lunch.get("id").asString()), ana.token(), null).getStatus()).isEqualTo(204);
        assertError(call(delete(base(ana) + "/time-off/" + lunch.get("id").asString()), ana.token(), null), 404, ScheduleService.TIME_OFF_NOT_FOUND);
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-31"), ana.token(), null))).hasSize(2);
    }

    @Test
    @DisplayName("Bloqueios: fim depois do início, ainda não terminado, até 92 dias, passos de 5 min, profissional do estabelecimento")
    void timeOffValidation() throws Exception {
        Session ana = register();
        String joao = professional(ana, "João");
        Session eve = register("Eve", email(), "Loja da Eve");
        String eva = professional(eve, "Eva");
        String url = base(ana) + "/time-off";
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T12:00", null)), 400, ScheduleService.END_BEFORE_START);
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T11:00", null)), 400, ScheduleService.END_BEFORE_START);
        // agora: 24/11 06:00 em São Paulo
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-11-23T12:00", "2026-11-24T06:00", null)), 400, ScheduleService.ALREADY_ENDED);
        assertThat(call(post(url), ana.token(), timeOff(joao, "2026-11-23T12:00", "2026-11-24T06:05", null)).getStatus())
                .as("começou no passado, mas ainda não terminou").isEqualTo(201);
        assertError(call(post(url), ana.token(), timeOff(null, "2026-12-01T00:00", "2027-03-03T00:05", null)), 400, ScheduleService.TOO_LONG);
        assertThat(call(post(url), ana.token(), timeOff(null, "2026-12-01T00:00", "2027-03-03T00:00", null)).getStatus())
                .as("exatamente 92 dias").isEqualTo(201);
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:01", "2026-12-01T13:00", null)), 400, ScheduleService.FIVE_MINUTE_STEPS);
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T13:00:30", null)), 400, ScheduleService.FIVE_MINUTE_STEPS);
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00:00.5", "2026-12-01T13:00", null)), 400, ScheduleService.FIVE_MINUTE_STEPS);
        assertError(call(post(url), ana.token(), timeOff(eva, "2026-12-01T12:00", "2026-12-01T13:00", null)), 404, "Professional not found");
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T13:00", "x".repeat(101))), 400, "Validation failed");
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T13:00", "a\u0000b")), 400, "Validation failed");
        assertError(call(post(url), ana.token(), timeOff(joao, null, "2026-12-01T13:00", null)), 400, "Validation failed");
        assertError(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", null, null)), 400, "Validation failed");
        assertError(call(post(url), ana.token(), timeOff(joao, "amanhã", "2026-12-01T13:00", null)), 400, "Malformed JSON request");
        assertThat(call(post(url), ana.token(), timeOff(joao, "2026-12-01T12:00", "2026-12-01T13:00", "x".repeat(100))).getStatus()).isEqualTo(201);

        for (String[] q : new String[][]{{"2026-12-02", "2026-12-01"}, {"2026-12-01", "2027-03-03"}, {"2026-12-01", "ontem"}}) {
            assertError(call(get(url).param("from", q[0]).param("to", q[1]), ana.token(), null), 400,
                    q[1].equals("ontem") ? "Invalid parameter" : ScheduleService.INVALID_PERIOD);
        }
        assertError(call(get(url).param("from", "2026-12-01"), ana.token(), null), 400, "Invalid parameter");
        // datas absurdas viram 400, não 500 (hoje: 24/11/2026; de 23/11/2025 a 25/11/2028)
        for (String[] q : new String[][]{{"2025-11-22", "2025-11-23"}, {"2028-11-26", "2028-11-27"}, {"+999999999-12-31", "+999999999-12-31"}}) {
            assertError(call(get(url).param("from", q[0]).param("to", q[1]), ana.token(), null), 400, ScheduleService.INVALID_PERIOD);
        }
        assertThat(call(get(url).param("from", "2025-11-23").param("to", "2025-11-23"), ana.token(), null).getStatus()).isEqualTo(200);
        assertThat(call(get(url).param("from", "2028-11-25").param("to", "2028-11-25"), ana.token(), null).getStatus()).isEqualTo(200);
        assertError(call(post(url), ana.token(), timeOff(null, "2028-11-25T00:00", "2028-11-26T00:00", null)), 400, ScheduleService.DATE_OUT_OF_RANGE);
        assertError(call(post(url), ana.token(), timeOff(null, "+200000-01-01T00:00", "+200000-01-01T01:00", null)), 400, ScheduleService.DATE_OUT_OF_RANGE);
        assertThat(call(post(url), ana.token(), timeOff(null, "2028-11-24T00:00", "2028-11-25T00:00", null)).getStatus())
                .as("fim no último dia aceito").isEqualTo(201);
        assertThat(call(get(url).param("from", "2026-12-01").param("to", "2027-03-02"), ana.token(), null).getStatus())
                .as("92 dias de período").isEqualTo(200);
    }

    @Test
    @DisplayName("Bloqueios: equipe lê, só o dono cria e apaga; bloqueio de outro estabelecimento é 404 e não aparece")
    void timeOffAccess() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String eveOff = body(call(post(base(eve) + "/time-off"), eve.token(), timeOff(null, "2026-12-01T00:00", "2026-12-02T00:00", "Feriado")))
                .get("id").asString();
        assertError(call(delete(base(ana) + "/time-off/" + eveOff), ana.token(), null), 404, ScheduleService.TIME_OFF_NOT_FOUND);
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-01"), ana.token(), null))).isEmpty();
        assertThat(body(call(get(base(eve) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-01"), eve.token(), null)))
                .as("continua lá").hasSize(1);

        Session bia = register("Bia", email(), "Outro");
        addMember(ana.tenantId(), bia.userId(), "PROFESSIONAL");
        String own = body(call(post(base(ana) + "/time-off"), ana.token(), timeOff(null, "2026-12-01T00:00", "2026-12-02T00:00", null)))
                .get("id").asString();
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(null, "2026-12-01T10:00", "2026-12-01T11:00", "Exame médico"));
        JsonNode seenByTeam = body(call(get(base(ana) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-01"), bia.token(), null));
        assertThat(seenByTeam).hasSize(2);
        assertThat(seenByTeam.get(1).get("reason").isNull()).as("o motivo pode ser pessoal: só o dono vê").isTrue();
        assertThat(body(call(get(base(ana) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-01"), ana.token(), null))
                .get(1).get("reason").asString()).isEqualTo("Exame médico");
        assertError(call(post(base(ana) + "/time-off"), bia.token(), timeOff(null, "2026-12-03T00:00", "2026-12-04T00:00", null)), 403, "Only owners can do this");
        assertError(call(delete(base(ana) + "/time-off/" + own), bia.token(), null), 403, "Only owners can do this");
        assertError(call(get(base(eve) + "/time-off").param("from", "2026-12-01").param("to", "2026-12-01"), ana.token(), null), 404, "Tenant not found");
    }

    @Test
    @DisplayName("Teto de bloqueios futuros por estabelecimento; os que já terminaram não contam")
    void timeOffLimit() throws Exception {
        Session ana = register();
        String url = base(ana) + "/time-off";
        for (int i = 0; i < ScheduleService.MAX_UPCOMING_TIME_OFF; i++) {
            LocalDateTime start = LocalDateTime.parse("2026-11-24T07:00").plusMinutes(5L * i);
            assertThat(call(post(url), ana.token(), timeOff(null, start.toString(), start.plusMinutes(5).toString(), null)).getStatus())
                    .isEqualTo(201);
        }
        assertError(call(post(url), ana.token(), timeOff(null, "2026-12-01T00:00", "2026-12-02T00:00", null)), 409, ScheduleService.TIME_OFF_LIMIT);
        clock.advance(Duration.ofMinutes(65));   // 07:00–07:05 local terminou (agora: 07:05)
        ana = renewed(ana);
        assertThat(call(post(url), ana.token(), timeOff(null, "2026-12-01T00:00", "2026-12-02T00:00", null)).getStatus()).isEqualTo(201);
        assertError(call(post(url), ana.token(), timeOff(null, "2026-12-02T00:00", "2026-12-03T00:00", null)), 409, ScheduleService.TIME_OFF_LIMIT);
    }

    // ---------- horários livres ----------

    @Test
    @DisplayName("Horários livres: de 15 em 15 min dentro do expediente, cabendo duração + preparo, fora dos bloqueios")
    void availability() throws Exception {
        Session ana = register();
        String corte = service(ana, "Corte", 30, 10);
        String joao = professional(ana, "João", corte);
        String beto = professional(ana, "Beto", corte);
        call(put(hours(ana, joao)), ana.token(), week(interval(1, "13:00", "15:00"), interval(1, "09:00", "12:00")));

        assertThat(slots(ana, joao, corte, MONDAY)).containsExactlyElementsOf(at(MONDAY,
                "09:00", "09:15", "09:30", "09:45", "10:00", "10:15", "10:30", "10:45", "11:00", "11:15",
                "13:00", "13:15", "13:30", "13:45", "14:00", "14:15"));
        assertThat(slots(ana, joao, corte, "2026-12-01")).as("terça: sem expediente").isEmpty();
        assertThat(slots(ana, beto, corte, MONDAY)).as("sem expediente cadastrado").isEmpty();

        // bloqueio do João das 10:00 às 11:00: some todo horário cujo atendimento + preparo encosta nele
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(joao, MONDAY + "T10:00", MONDAY + "T11:00", "Médico"));
        // bloqueio do Beto não afeta o João
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(beto, MONDAY + "T13:00", MONDAY + "T15:00", null));
        assertThat(slots(ana, joao, corte, MONDAY)).containsExactlyElementsOf(at(MONDAY,
                "09:00", "09:15", "11:00", "11:15", "13:00", "13:15", "13:30", "13:45", "14:00", "14:15"));

        // feriado do estabelecimento vale para todos
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(null, MONDAY + "T14:00", MONDAY + "T23:00", "Feriado"));
        assertThat(slots(ana, joao, corte, MONDAY)).containsExactlyElementsOf(at(MONDAY, "09:00", "09:15", "11:00", "11:15", "13:00", "13:15"));
    }

    @Test
    @DisplayName("Horários livres: hoje só a partir de agora; passado e além de 180 dias recusados; inativo não tem horário")
    void availabilityRules() throws Exception {
        Session ana = register();
        String corte = service(ana, "Corte", 60, 0);
        String barba = service(ana, "Barba", 30, 0);
        String joao = professional(ana, "João", corte);
        call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:00", "12:00"), interval(2, "09:00", "11:00")));

        // terça 24/11, agora 06:00 local: o dia todo; às 09:20 local, só a partir de 09:30
        assertThat(slots(ana, joao, corte, "2026-11-24")).containsExactlyElementsOf(at("2026-11-24",
                "09:00", "09:15", "09:30", "09:45", "10:00"));
        clock.set(Instant.parse("2026-11-24T12:20:00Z"));
        ana = renewed(ana);
        assertThat(slots(ana, joao, corte, "2026-11-24")).containsExactlyElementsOf(at("2026-11-24", "09:30", "09:45", "10:00"));
        clock.set(Instant.parse("2026-11-24T12:30:00Z"));
        assertThat(slots(ana, joao, corte, "2026-11-24")).as("começando agora ainda vale")
                .containsExactlyElementsOf(at("2026-11-24", "09:30", "09:45", "10:00"));

        String availability = base(ana) + "/availability";
        assertError(call(get(availability).param("professionalId", joao).param("serviceId", corte).param("date", "2026-11-23"),
                ana.token(), null), 400, ScheduleService.DATE_OUT_OF_RANGE);
        assertThat(slots(ana, joao, corte, "2027-05-23")).as("hoje + 180 dias (domingo)").isEmpty();
        assertError(call(get(availability).param("professionalId", joao).param("serviceId", corte).param("date", "2027-05-24"),
                ana.token(), null), 400, ScheduleService.DATE_OUT_OF_RANGE);
        assertError(call(get(availability).param("professionalId", joao).param("serviceId", barba).param("date", MONDAY),
                ana.token(), null), 400, "Professional does not perform this service");
        assertError(call(get(availability).param("professionalId", joao).param("serviceId", UUID.randomUUID().toString()).param("date", MONDAY),
                ana.token(), null), 404, "Service not found");
        assertError(call(get(availability).param("professionalId", UUID.randomUUID().toString()).param("serviceId", corte).param("date", MONDAY),
                ana.token(), null), 404, "Professional not found");
        assertError(call(get(availability).param("professionalId", joao).param("serviceId", corte).param("date", "30/11/2026"),
                ana.token(), null), 400, "Invalid parameter");
        assertError(call(get(availability).param("professionalId", joao).param("serviceId", corte), ana.token(), null), 400, "Invalid parameter");

        Map<String, Object> off = new HashMap<>(Map.of("name", "Corte", "durationMinutes", 60, "bufferMinutes", 0, "priceCents", 0, "active", false));
        call(put(base(ana) + "/services/" + corte), ana.token(), off);
        assertThat(slots(ana, joao, corte, MONDAY)).as("serviço inativo").isEmpty();
        off.put("active", true);
        call(put(base(ana) + "/services/" + corte), ana.token(), off);
        call(put(base(ana) + "/professionals/" + joao), ana.token(), Map.of("name", "João", "active", false, "serviceIds", List.of(corte)));
        assertThat(slots(ana, joao, corte, MONDAY)).as("profissional inativo").isEmpty();
        call(put(base(ana) + "/professionals/" + joao), ana.token(), Map.of("name", "João", "active", true, "serviceIds", List.of(corte)));
        assertThat(slots(ana, joao, corte, MONDAY)).hasSize(9);
    }

    @Test
    @DisplayName("Virada do dia: expediente até a meia-noite local (já é o dia seguinte em UTC) e o 'hoje' no fuso do estabelecimento")
    void availabilityAcrossUtcMidnight() throws Exception {
        Session ana = register();
        String corte = service(ana, "Corte", 30, 0);
        String joao = professional(ana, "João", corte);
        call(put(hours(ana, joao)), ana.token(), week(interval(1, "21:00", "24:00")));
        // feriado na terça local: começa terça 00:00 em São Paulo = terça 03:00 UTC, exatamente quando a segunda acaba
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(null, "2026-12-01T00:00", "2026-12-02T00:00", "Feriado"));

        assertThat(slots(ana, joao, corte, MONDAY)).as("o feriado de terça não toca a noite de segunda").containsExactlyElementsOf(at(MONDAY,
                "21:00", "21:15", "21:30", "21:45", "22:00", "22:15", "22:30", "22:45", "23:00", "23:15", "23:30"));

        // terça 01:10 UTC = segunda 22:10 local: "hoje" ainda é segunda, e só sobra o que começa depois de agora
        clock.set(Instant.parse("2026-12-01T01:10:00Z"));
        ana = renewed(ana);
        assertThat(slots(ana, joao, corte, MONDAY)).containsExactlyElementsOf(at(MONDAY,
                "22:15", "22:30", "22:45", "23:00", "23:15", "23:30"));
        assertError(call(get(base(ana) + "/availability").param("professionalId", joao).param("serviceId", corte).param("date", "2026-11-29"),
                ana.token(), null), 400, ScheduleService.DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("Horários livres: equipe consulta; outro estabelecimento é 404 (nem o serviço nem o profissional alheio servem)")
    void availabilityAccess() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        String corte = service(ana, "Corte", 30, 0);
        String joao = professional(ana, "João", corte);
        String unha = service(eve, "Unha", 30, 0);
        String eva = professional(eve, "Eva", unha);
        call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:00", "10:00")));

        Session bia = register("Bia", email(), "Outro");
        addMember(ana.tenantId(), bia.userId(), "PROFESSIONAL");
        MockHttpServletResponse r = call(get(base(ana) + "/availability").param("professionalId", joao).param("serviceId", corte)
                .param("date", MONDAY), bia.token(), null);
        assertThat(r.getStatus()).isEqualTo(200);
        assertThat(body(r).get("slots")).hasSize(3);

        assertError(call(get(base(ana) + "/availability").param("professionalId", eva).param("serviceId", corte).param("date", MONDAY),
                ana.token(), null), 404, "Professional not found");
        assertError(call(get(base(ana) + "/availability").param("professionalId", joao).param("serviceId", unha).param("date", MONDAY),
                ana.token(), null), 404, "Service not found");
        assertError(call(get(base(eve) + "/availability").param("professionalId", eva).param("serviceId", unha).param("date", MONDAY),
                ana.token(), null), 404, "Tenant not found");
    }

    // ---------- complementos da revisão ----------

    @Test
    @DisplayName("Horários livres: bloqueio que começa antes do expediente corta o começo; férias de vários dias esvaziam o dia")
    void availabilityWithBlocksStartingBefore() throws Exception {
        Session ana = register();
        String corte = service(ana, "Corte", 30, 0);
        String joao = professional(ana, "João", corte);
        call(put(hours(ana, joao)), ana.token(), week(interval(1, "09:00", "11:00"), interval(2, "09:00", "10:00")));
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(joao, MONDAY + "T08:00", MONDAY + "T09:30", null));
        assertThat(slots(ana, joao, corte, MONDAY)).containsExactlyElementsOf(at(MONDAY, "09:30", "09:45", "10:00", "10:15", "10:30"));
        call(post(base(ana) + "/time-off"), ana.token(), timeOff(joao, "2026-12-01T00:00", "2026-12-03T00:00", "Férias"));
        assertThat(slots(ana, joao, corte, "2026-12-01")).isEmpty();
        assertThat(slots(ana, joao, corte, "2026-12-08")).as("semana seguinte, livre").hasSize(3);
    }

    @Test
    @DisplayName("Horizonte de 180 dias contado no fuso do estabelecimento, também na virada do dia")
    void horizonInLocalZone() throws Exception {
        clock.set(Instant.parse("2026-12-01T01:10:00Z"));      // segunda 30/11 22:10 em São Paulo; em UTC já é 01/12
        Session ana = register();
        String corte = service(ana, "Corte", 30, 0);
        String joao = professional(ana, "João", corte);
        assertThat(slots(ana, joao, corte, "2027-05-29")).isEmpty();   // 30/11 + 180 dias (sábado, sem expediente)
        assertError(call(get(base(ana) + "/availability").param("professionalId", joao).param("serviceId", corte).param("date", "2027-05-30"),
                ana.token(), null), 400, ScheduleService.DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("Listagem de bloqueios: no máximo 500, os primeiros por início (os que já terminaram não contam no teto)")
    void timeOffListLimit() throws Exception {
        Session ana = register();
        for (int i = 0; i < ScheduleService.MAX_LISTED_TIME_OFF + 1; i++) {
            Instant start = Instant.parse("2026-11-01T03:00:00Z").plus(Duration.ofMinutes(5L * i));
            ownerSql("INSERT INTO time_off (id, tenant_id, start_at, end_at, created_at) VALUES (gen_random_uuid(), ?, ?, ?, now())",
                    UUID.fromString(ana.tenantId()), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(Duration.ofMinutes(5))));
        }
        JsonNode list = body(call(get(base(ana) + "/time-off").param("from", "2026-11-01").param("to", "2026-11-30"), ana.token(), null));
        assertThat(list).hasSize(ScheduleService.MAX_LISTED_TIME_OFF);
        assertThat(list.get(0).get("start").asString()).isEqualTo("2026-11-01T00:00:00");
        assertThat(list.get(499).get("start").asString()).isEqualTo("2026-11-02T17:35:00");
    }

    @Test
    @DisplayName("Teto de bloqueios sob concorrência: com 195 futuros, 10 criações simultâneas → exatamente 5 entram, 5 recebem 409")
    void timeOffLimitUnderConcurrency() throws Exception {
        Session ana = register();
        for (int i = 0; i < ScheduleService.MAX_UPCOMING_TIME_OFF - 5; i++) {
            Instant start = Instant.parse("2026-12-01T03:00:00Z").plus(Duration.ofMinutes(5L * i));
            ownerSql("INSERT INTO time_off (id, tenant_id, start_at, end_at, created_at) VALUES (gen_random_uuid(), ?, ?, ?, now())",
                    UUID.fromString(ana.tenantId()), java.sql.Timestamp.from(start), java.sql.Timestamp.from(start.plus(Duration.ofMinutes(5))));
        }
        List<Integer> statuses = concurrently(10, i -> call(post(base(ana) + "/time-off"), ana.token(),
                timeOff(null, "2027-01-0" + (i % 9 + 1) + "T00:00", "2027-01-0" + (i % 9 + 1) + "T01:00", null)).getStatus());
        assertThat(statuses).filteredOn(st -> st == 201).hasSize(5);
        assertThat(statuses).filteredOn(st -> st == 409).hasSize(5);
    }

    @Test
    @DisplayName("Troca da semana sob concorrência: 10 trocas simultâneas → fica exatamente uma das semanas, sem faixas misturadas")
    void replaceWeekUnderConcurrency() throws Exception {
        Session ana = register();
        String joao = professional(ana, "João");
        List<Integer> statuses = concurrently(10, i -> call(put(hours(ana, joao)), ana.token(),
                week(interval(1, "09:00", "12:00"), interval(i % 7 + 1, String.format("%02d:00", 13 + i), String.format("%02d:30", 13 + i)))).getStatus());
        assertThat(statuses).containsOnly(200);
        JsonNode week = body(call(get(hours(ana, joao)), ana.token(), null)).get("intervals");
        assertThat(week).as(week.toString()).hasSize(2);
    }

    interface Request {
        int status(int i) throws Exception;
    }

    private static List<Integer> concurrently(int n, Request request) throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return request.status(k);
                }));
            }
            start.countDown();
            List<Integer> out = new ArrayList<>();
            for (var r : results) out.add(r.get(30, java.util.concurrent.TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
