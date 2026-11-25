package com.sskings.agendai.catalog;

import com.sskings.agendai.common.Tokens;
import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Convite para a equipe e a regra "profissional só mexe na própria agenda". Segunda seguinte ao relógio: 30/11. */
class InviteIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    record Shop(Session owner, String corte, String joao, String beto) {
        String base() {
            return "/api/tenants/" + owner.tenantId();
        }
    }

    Shop shop() throws Exception {
        Session ana = register();
        String base = "/api/tenants/" + ana.tenantId();
        String corte = body(call(post(base + "/services"), ana.token(), Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 0, "priceCents", 0, "active", true))).get("id").asString();
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

    String invite(Shop s, String professional) throws Exception {
        MockHttpServletResponse r = call(post(s.base() + "/professionals/" + professional + "/invite"), s.owner().token(), null);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r).get("code").asString();
    }

    MockHttpServletResponse accept(Session who, String code) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        return call(post("/api/invites/accept"), who == null ? null : who.token(), body);
    }

    void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    /** Dono convida, outra conta aceita: entra como profissional, vinculado ao João. */
    Session joined(Shop s) throws Exception {
        Session carlos = register("Carlos Lima", email(), "Barbearia do Carlos");
        assertThat(accept(carlos, invite(s, s.joao())).getStatus()).isEqualTo(200);
        return carlos;
    }

    Session renewed(Session s) throws Exception {
        MockHttpServletResponse r = refresh(s.refresh());
        assertThat(r.getStatus()).isEqualTo(200);
        return new Session(body(r).get("accessToken").asString(), refreshCookie(r), s.userId(), s.email(), s.tenantId());
    }

    static Map<String, Object> booking(String professional, String service, String start) {
        return Map.of("professionalId", professional, "serviceId", service, "start", start,
                "customer", Map.of("name", "Cliente", "phone", "11987654321"));
    }

    @Test
    @DisplayName("Convite: código de 256 bits que vale 72 h; quem aceita entra como profissional, vinculado, e o código não serve de novo")
    void acceptFlow() throws Exception {
        Shop s = shop();
        MockHttpServletResponse created = call(post(s.base() + "/professionals/" + s.joao() + "/invite"), s.owner().token(), null);
        JsonNode inv = body(created);
        String code = inv.get("code").asString();
        assertThat(Tokens.wellFormed(code)).isTrue();
        assertThat(inv.get("expiresAt").asString()).isEqualTo("2026-11-27T09:00:00Z");
        assertThat(ownerCount("SELECT count(*) FROM invites WHERE token_hash = ?", Tokens.sha256(code))).as("no banco, só o hash").isEqualTo(1);
        assertThat(ownerCount("SELECT count(*) FROM invites WHERE token_hash = ?", code)).isZero();

        Session carlos = register("Carlos Lima", email(), "Barbearia do Carlos");
        MockHttpServletResponse ok = accept(carlos, code);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(body(ok).get("tenantId").asString()).isEqualTo(s.owner().tenantId());
        JsonNode tenants = body(call(get("/api/me"), carlos.token(), null)).get("tenants");
        assertThat(tenants.findValuesAsString("id")).contains(s.owner().tenantId());
        for (JsonNode t : tenants) {
            if (t.get("id").asString().equals(s.owner().tenantId())) assertThat(t.get("role").asString()).isEqualTo("PROFESSIONAL");
        }
        JsonNode seenByCarlos = body(call(get(s.base() + "/professionals"), carlos.token(), null));
        JsonNode joao = seenByCarlos.get(1);
        assertThat(joao.get("name").asString()).isEqualTo("João");
        assertThat(joao.get("hasAccount").asBoolean()).isTrue();
        assertThat(joao.get("self").asBoolean()).isTrue();
        assertThat(seenByCarlos.get(0).get("hasAccount").asBoolean()).as("Beto").isFalse();
        assertThat(seenByCarlos.get(0).get("self").asBoolean()).isFalse();
        assertThat(body(call(get(s.base() + "/professionals"), s.owner().token(), null)).get(1).get("self").asBoolean()).isFalse();

        Session dani = register("Dani", email(), "Outro");
        assertError(accept(dani, code), 400, InviteService.INVALID);
        assertError(call(post(s.base() + "/professionals/" + s.joao() + "/invite"), s.owner().token(), null), 409, InviteService.HAS_ACCOUNT);
    }

    @Test
    @DisplayName("Código inválido, desconhecido, vencido, cancelado ou substituído: sempre a mesma resposta genérica")
    void invalidCodes() throws Exception {
        Shop s = shop();
        Session carlos = register("Carlos Lima", email(), "Outro");
        for (String bad : new String[]{"curto", "x".repeat(43) + "=", Tokens.random()}) assertError(accept(carlos, bad), 400, InviteService.INVALID);
        assertError(call(post("/api/invites/accept"), carlos.token(), "{}"), 400, InviteService.INVALID);

        String revoked = invite(s, s.joao());
        assertThat(call(delete(s.base() + "/professionals/" + s.joao() + "/invite"), s.owner().token(), null).getStatus()).isEqualTo(204);
        assertError(accept(carlos, revoked), 400, InviteService.INVALID);

        String old = invite(s, s.joao());
        String current = invite(s, s.joao());
        assertError(accept(carlos, old), 400, InviteService.INVALID);

        clock.advance(Duration.ofHours(72));
        Session late = renewed(carlos);
        assertError(accept(late, current), 400, InviteService.INVALID);
        clock.advance(Duration.ofMinutes(-1));
        assertThat(accept(renewed(late), current).getStatus()).as("um minuto antes de vencer").isEqualTo(200);
    }

    @Test
    @DisplayName("Quem já participa (o próprio dono, ou alguém da equipe) recebe 409 e o convite continua valendo")
    void alreadyMember() throws Exception {
        Shop s = shop();
        String code = invite(s, s.joao());
        assertError(accept(s.owner(), code), 409, InviteService.ALREADY_MEMBER);
        Session bia = register("Bia", email(), "Outro");
        addMember(s.owner().tenantId(), bia.userId(), "PROFESSIONAL");
        assertError(accept(bia, code), 409, InviteService.ALREADY_MEMBER);
        assertThat(accept(register("Carlos", email(), "Outro"), code).getStatus()).isEqualTo(200);
        assertThat(accept(null, code).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Só o dono convida e cancela; profissional de outro estabelecimento é 404")
    void onlyOwnerInvites() throws Exception {
        Shop s = shop();
        Shop eve = shop();
        Session carlos = joined(s);
        assertError(call(post(s.base() + "/professionals/" + s.beto() + "/invite"), carlos.token(), null), 403, "Only owners can do this");
        assertError(call(delete(s.base() + "/professionals/" + s.beto() + "/invite"), carlos.token(), null), 403, "Only owners can do this");
        assertError(call(post(s.base() + "/professionals/" + eve.joao() + "/invite"), s.owner().token(), null), 404, "Professional not found");
        assertError(call(delete(s.base() + "/professionals/" + eve.joao() + "/invite"), s.owner().token(), null), 404, "Professional not found");
        assertError(call(post(eve.base() + "/professionals/" + eve.joao() + "/invite"), s.owner().token(), null), 404, "Tenant not found");
    }

    @Test
    @DisplayName("Duas contas aceitando o mesmo código ao mesmo tempo: só uma entra")
    void concurrentAccept() throws Exception {
        Shop s = shop();
        String code = invite(s, s.joao());
        Session a = register("Conta A", email(), "A");
        Session b = register("Conta B", email(), "B");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Session who : List.of(a, b)) results.add(pool.submit(() -> { start.await(); return accept(who, code).getStatus(); }));
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ownerCount("SELECT count(*) FROM memberships WHERE tenant_id = ? AND role = 'PROFESSIONAL'", UUID.fromString(s.owner().tenantId())))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Profissional vinculado: agenda, agendamentos e status só do próprio profissional (403 nos outros)")
    void ownAgendaBookings() throws Exception {
        Shop s = shop();
        Session carlos = joined(s);
        String betoBooking = body(call(post(s.base() + "/bookings"), s.owner().token(), booking(s.beto(), s.corte(), MONDAY + "T09:00"))).get("id").asString();
        MockHttpServletResponse mine = call(post(s.base() + "/bookings"), carlos.token(), booking(s.joao(), s.corte(), MONDAY + "T10:00"));
        assertThat(mine.getStatus()).as(mine.getContentAsString()).isEqualTo(201);
        assertError(call(post(s.base() + "/bookings"), carlos.token(), booking(s.beto(), s.corte(), MONDAY + "T10:00")), 403, CatalogService.OWN_AGENDA);

        JsonNode agenda = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), carlos.token(), null)).get("bookings");
        assertThat(agenda.findValuesAsString("professionalId")).as("sem filtro: só a própria").containsExactly(s.joao());
        assertError(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY).param("professionalId", s.beto()), carlos.token(), null),
                403, CatalogService.OWN_AGENDA);
        assertThat(body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY).param("professionalId", s.joao()),
                carlos.token(), null)).get("bookings")).hasSize(1);
        assertThat(body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null)).get("bookings"))
                .as("o dono vê todos").hasSize(2);
        assertError(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(s.base() + "/bookings/" + betoBooking),
                carlos.token(), Map.of("status", "CANCELLED")), 403, CatalogService.OWN_AGENDA);
        assertThat(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(s.base() + "/bookings/" + body(mine).get("id").asString()),
                carlos.token(), Map.of("status", "CANCELLED")).getStatus()).isEqualTo(200);

        Session bia = register("Bia", email(), "Outro");
        addMember(s.owner().tenantId(), bia.userId(), "PROFESSIONAL");   // na equipe, sem vínculo com nenhum profissional
        assertError(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), bia.token(), null), 403, CatalogService.OWN_AGENDA);
        assertError(call(post(s.base() + "/bookings"), bia.token(), booking(s.joao(), s.corte(), MONDAY + "T11:00")), 403, CatalogService.OWN_AGENDA);
    }

    @Test
    @DisplayName("Profissional vinculado bloqueia e desbloqueia só a própria agenda e vê só os próprios motivos")
    void ownTimeOff() throws Exception {
        Shop s = shop();
        Session carlos = joined(s);
        String url = s.base() + "/time-off";
        String betoOff = body(call(post(url), s.owner().token(), Map.of("professionalId", s.beto(), "start", "2026-12-01T12:00",
                "end", "2026-12-01T13:00", "reason", "Médico"))).get("id").asString();
        String holiday = body(call(post(url), s.owner().token(), Map.of("start", "2026-12-01T00:00", "end", "2026-12-02T00:00",
                "reason", "Feriado"))).get("id").asString();
        MockHttpServletResponse own = call(post(url), carlos.token(), Map.of("professionalId", s.joao(), "start", "2026-12-01T12:00",
                "end", "2026-12-01T13:00", "reason", "Almoço"));
        assertThat(own.getStatus()).as(own.getContentAsString()).isEqualTo(201);
        assertError(call(post(url), carlos.token(), Map.of("professionalId", s.beto(), "start", "2026-12-01T14:00", "end", "2026-12-01T15:00")),
                403, CatalogService.OWN_AGENDA);
        assertError(call(post(url), carlos.token(), Map.of("start", "2026-12-02T00:00", "end", "2026-12-03T00:00")), 403, CatalogService.OWN_AGENDA);
        assertError(call(delete(url + "/" + betoOff), carlos.token(), null), 403, CatalogService.OWN_AGENDA);
        assertError(call(delete(url + "/" + holiday), carlos.token(), null), 403, CatalogService.OWN_AGENDA);

        JsonNode list = body(call(get(url).param("from", "2026-12-01").param("to", "2026-12-01"), carlos.token(), null));
        Map<String, String> reasons = new HashMap<>();
        for (JsonNode t : list) reasons.put(t.get("id").asString(), t.get("reason").isNull() ? null : t.get("reason").asString());
        assertThat(reasons).containsEntry(body(own).get("id").asString(), "Almoço").containsEntry(betoOff, null).containsEntry(holiday, null);
        assertThat(call(delete(url + "/" + body(own).get("id").asString()), carlos.token(), null).getStatus()).isEqualTo(204);
    }

    // ---------- complementos da revisão ----------

    @Test
    @DisplayName("Convite que sobrou para um profissional já vinculado não serve: nada de membro sem vínculo, e o convite não é gasto")
    void inviteForLinkedProfessional() throws Exception {
        Shop s = shop();
        String code = invite(s, s.joao());
        Session other = register("Outra Conta", email(), "Outro");
        addMember(s.owner().tenantId(), other.userId(), "PROFESSIONAL");   // o vínculo exige participação (FK composta)
        ownerSql("UPDATE professionals SET user_id = ? WHERE id = ?", UUID.fromString(other.userId()), UUID.fromString(s.joao()));
        Session third = register("Terceira", email(), "Outro");
        assertError(accept(third, code), 400, InviteService.INVALID);
        assertThat(ownerCount("SELECT count(*) FROM memberships WHERE tenant_id = ? AND user_id = ?",
                UUID.fromString(s.owner().tenantId()), UUID.fromString(third.userId()))).isZero();
        assertThat(ownerCount("SELECT count(*) FROM invites WHERE professional_id = ? AND used_at IS NULL", UUID.fromString(s.joao()))).isEqualTo(1);
    }

    @Test
    @DisplayName("Convidar ou cancelar o Beto não mexe no convite já enviado ao João")
    void invitesArePerProfessional() throws Exception {
        Shop s = shop();
        String forJoao = invite(s, s.joao());
        invite(s, s.beto());
        assertThat(call(delete(s.base() + "/professionals/" + s.beto() + "/invite"), s.owner().token(), null).getStatus()).isEqualTo(204);
        assertThat(accept(register("Carlos", email(), "Outro"), forJoao).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Conta vinculada em dois estabelecimentos age em cada um pelo próprio profissional; sem vínculo num deles, nada lá")
    void accountInTwoBusinesses() throws Exception {
        Shop a = shop();
        Shop b = shop();
        Session carlos = register("Carlos Lima", email(), "Outro");
        assertThat(accept(carlos, invite(a, a.joao())).getStatus()).isEqualTo(200);
        assertThat(accept(carlos, invite(b, b.beto())).getStatus()).isEqualTo(200);
        call(post(a.base() + "/bookings"), a.owner().token(), booking(a.beto(), a.corte(), MONDAY + "T09:00"));
        call(post(a.base() + "/bookings"), a.owner().token(), booking(a.joao(), a.corte(), MONDAY + "T09:00"));
        call(post(b.base() + "/bookings"), b.owner().token(), booking(b.joao(), b.corte(), MONDAY + "T09:00"));
        call(post(b.base() + "/bookings"), b.owner().token(), booking(b.beto(), b.corte(), MONDAY + "T09:00"));
        assertThat(body(call(get(a.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), carlos.token(), null)).get("bookings")
                .findValuesAsString("professionalId")).containsExactly(a.joao());
        assertThat(body(call(get(b.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), carlos.token(), null)).get("bookings")
                .findValuesAsString("professionalId")).containsExactly(b.beto());
        assertError(call(post(a.base() + "/bookings"), carlos.token(), booking(a.beto(), a.corte(), MONDAY + "T10:00")), 403, CatalogService.OWN_AGENDA);
        assertError(call(post(b.base() + "/bookings"), carlos.token(), booking(b.joao(), b.corte(), MONDAY + "T10:00")), 403, CatalogService.OWN_AGENDA);

        Shop c = shop();
        addMember(c.owner().tenantId(), carlos.userId(), "PROFESSIONAL");
        assertError(call(get(c.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), carlos.token(), null), 403, CatalogService.OWN_AGENDA);
    }

    @Test
    @DisplayName("No banco: vínculo de conta exige participação no estabelecimento (FK composta para memberships)")
    void linkRequiresMembership() throws Exception {
        Shop s = shop();
        Session stranger = register("Estranho", email(), "Outro");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ownerSql("UPDATE professionals SET user_id = ? WHERE id = ?",
                UUID.fromString(stranger.userId()), UUID.fromString(s.joao()))).hasMessageContaining("professionals_tenant_id_user_id_fkey");
    }

    @Test
    @DisplayName("Profissional desativado perde o acesso à agenda; convite para inativo é recusado (na criação e no aceite)")
    void inactiveProfessional() throws Exception {
        Shop s = shop();
        Session carlos = joined(s);
        String booked = body(call(post(s.base() + "/bookings"), carlos.token(), booking(s.joao(), s.corte(), MONDAY + "T09:00"))).get("id").asString();
        call(put(s.base() + "/professionals/" + s.joao()), s.owner().token(), Map.of("name", "João", "active", false, "serviceIds", List.of(s.corte())));
        assertError(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), carlos.token(), null), 403, CatalogService.OWN_AGENDA);
        assertError(call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(s.base() + "/bookings/" + booked),
                carlos.token(), Map.of("status", "CANCELLED")), 403, CatalogService.OWN_AGENDA);

        String code = invite(s, s.beto());
        call(put(s.base() + "/professionals/" + s.beto()), s.owner().token(), Map.of("name", "Beto", "active", false, "serviceIds", List.of()));
        assertError(accept(register("Dani", email(), "Outro"), code), 400, InviteService.INVALID);
        assertError(call(post(s.base() + "/professionals/" + s.beto() + "/invite"), s.owner().token(), null), 409, InviteService.INACTIVE);
    }

    @Test
    @DisplayName("Dono tira a conta da equipe: perde o estabelecimento, o profissional fica sem conta (pode convidar de novo); agendamentos ficam")
    void removeAccount() throws Exception {
        Shop s = shop();
        Session carlos = joined(s);
        call(post(s.base() + "/bookings"), carlos.token(), booking(s.joao(), s.corte(), MONDAY + "T09:00"));
        assertError(call(delete(s.base() + "/professionals/" + s.joao() + "/account"), carlos.token(), null), 403, "Only owners can do this");
        assertThat(call(delete(s.base() + "/professionals/" + s.joao() + "/account"), s.owner().token(), null).getStatus()).isEqualTo(204);
        assertError(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), carlos.token(), null), 404, "Tenant not found");
        assertThat(body(call(get("/api/me"), carlos.token(), null)).get("tenants").findValuesAsString("id")).doesNotContain(s.owner().tenantId());
        JsonNode joao = body(call(get(s.base() + "/professionals"), s.owner().token(), null)).get(1);
        assertThat(joao.get("hasAccount").asBoolean()).isFalse();
        assertThat(body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null)).get("bookings")).hasSize(1);
        assertError(call(delete(s.base() + "/professionals/" + s.joao() + "/account"), s.owner().token(), null), 404, InviteService.NO_ACCOUNT);
        assertError(call(delete(s.base() + "/professionals/" + UUID.randomUUID() + "/account"), s.owner().token(), null), 404, "Professional not found");
        assertThat(accept(carlos, invite(s, s.joao())).getStatus()).as("pode voltar com um convite novo").isEqualTo(200);
    }

    @Test
    @DisplayName("Profissional que agenda com o telefone de um cliente existente não recebe o e-mail guardado dele")
    void customerEmailNotLeaked() throws Exception {
        Shop s = shop();
        Session carlos = joined(s);
        Map<String, Object> byOwner = new HashMap<>(booking(s.beto(), s.corte(), MONDAY + "T09:00"));
        byOwner.put("customer", Map.of("name", "Cliente", "phone", "11987654321", "email", "segredo@exemplo.com"));
        call(post(s.base() + "/bookings"), s.owner().token(), byOwner);
        JsonNode mine = body(call(post(s.base() + "/bookings"), carlos.token(), booking(s.joao(), s.corte(), MONDAY + "T09:00")));
        assertThat(mine.get("customer").get("email").isNull()).isTrue();
        assertThat(mine.toString()).doesNotContain("segredo@");
        JsonNode ownersView = body(call(get(s.base() + "/bookings").param("from", MONDAY).param("to", MONDAY), s.owner().token(), null));
        assertThat(ownersView.toString()).as("o dono continua vendo").contains("segredo@exemplo.com");
    }

    @Test
    @DisplayName("A mesma conta aceitando ao mesmo tempo convites de dois profissionais do estabelecimento: um entra, o outro é 409")
    void sameAccountTwoInvites() throws Exception {
        Shop s = shop();
        String forJoao = invite(s, s.joao());
        String forBeto = invite(s, s.beto());
        Session carlos = register("Carlos", email(), "Outro");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> { start.await(); return accept(carlos, forJoao).getStatus(); });
            Future<Integer> b = pool.submit(() -> { start.await(); return accept(carlos, forBeto).getStatus(); });
            start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ownerCount("SELECT count(*) FROM professionals WHERE tenant_id = ? AND user_id IS NOT NULL", UUID.fromString(s.owner().tenantId())))
                .isEqualTo(1);
    }
}
