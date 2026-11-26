package com.sskings.agendai.booking;

import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.support.MailBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** LGPD: exportar e apagar (anonimizar) os dados de um cliente, e a retenção. Segunda: 30/11. */
class CustomerPrivacyIntegrationTest extends IntegrationTest {

    static final String MONDAY = "2026-11-30";

    @Autowired MailBox mail;
    @Autowired CustomerRetention retention;

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
        call(put(base + "/professionals/" + joao + "/working-hours"), ana.token(),
                Map.of("intervals", List.of(Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        return new Shop(ana, slug, corte, joao);
    }

    /** Pelo painel; devolve o agendamento. */
    JsonNode panel(Shop s, String time, String phone, String note) throws Exception {
        var customer = new java.util.HashMap<String, Object>(Map.of("name", "Bruno Lima", "phone", phone, "email", "bruno@exemplo.com"));
        var req = new java.util.HashMap<String, Object>(Map.of("professionalId", s.joao(), "serviceId", s.corte(),
                "start", MONDAY + "T" + time, "customer", customer));
        if (note != null) req.put("note", note);
        MockHttpServletResponse r = call(post(s.base() + "/bookings"), s.owner().token(), req);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(201);
        return body(r);
    }

    /** Pela página, confirmado; devolve o token do link pessoal. */
    String online(Shop s, String time, String phone, String email) throws Exception {
        return online(s, time, phone, email, "Carla Dias");
    }

    String online(Shop s, String time, String phone, String email, String name) throws Exception {
        MockHttpServletResponse h = call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T" + time));
        assertThat(h.getStatus()).as(h.getContentAsString()).isEqualTo(201);
        MockHttpServletResponse p = call(post(s.pub() + "/bookings"), null, Map.of("holdToken", body(h).get("token").asString(),
                "customer", Map.of("name", name, "phone", phone, "email", email)));
        String token = body(p).get("token").asString();
        assertThat(call(post(s.pub() + "/bookings/confirm"), null, Map.of("token", token, "code", mail.lastCodeTo(email))).getStatus())
                .isEqualTo(200);
        return token;
    }

    JsonNode agenda(Shop s) throws Exception {
        return body(call(get(s.base() + "/bookings?from=" + MONDAY + "&to=" + MONDAY), s.owner().token(), null)).get("bookings");
    }

    void assertError(MockHttpServletResponse r, int status, String message) throws Exception {
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(status);
        assertThat(body(r).get("message").asString()).isEqualTo(message);
    }

    @Test
    @DisplayName("Exportar: o cadastro e os agendamentos; o contato de outra pessoa que agendou com o mesmo telefone não vai")
    void export() throws Exception {
        Shop s = shop();
        String customer = panel(s, "09:00", "11987654321", "Prefere máquina 2").get("customer").get("id").asString();
        String other = email();
        online(s, "10:00", "11987654321", other, "Carla Dias");     // outra pessoa, mesmo telefone: mesmo cadastro
        online(s, "11:00", "11987654321", "bruno.novo@exemplo.com", "bruno lima");   // o próprio, com outro e-mail
        MockHttpServletResponse r = call(get(s.base() + "/customers/" + customer + "/export"), s.owner().token(), null);
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(r.getContentAsString()).doesNotContain(other).doesNotContain("Carla");
        JsonNode e = body(r);
        assertThat(e.get("business").asString()).startsWith("Barbearia ");
        assertThat(e.get("customer").get("name").asString()).isEqualTo("Bruno Lima");
        assertThat(e.get("customer").get("phone").asString()).isEqualTo("11987654321");
        assertThat(e.get("customer").get("email").asString()).isEqualTo("bruno@exemplo.com");
        assertThat(e.get("customer").get("anonymizedAt").isNull()).isTrue();
        assertThat(e.get("truncated").asBoolean()).isFalse();
        JsonNode b = e.get("bookings");
        assertThat(b).hasSize(3);
        assertThat(b.get(0).get("start").asString()).isEqualTo(MONDAY + "T11:00:00-03:00");    // mais recente primeiro
        assertThat(b.get(0).get("contactName").asString()).isEqualTo("bruno lima");
        assertThat(b.get(0).get("contactEmail").asString()).isEqualTo("bruno.novo@exemplo.com");
        assertThat(b.get(0).get("otherContact").asBoolean()).isFalse();
        assertThat(b.get(1).get("source").asString()).isEqualTo("PUBLIC");
        assertThat(b.get(1).get("contactName").isNull()).isTrue();
        assertThat(b.get(1).get("contactEmail").isNull()).isTrue();
        assertThat(b.get(1).get("otherContact").asBoolean()).isTrue();
        assertThat(b.get(1).get("serviceName").asString()).isEqualTo("Corte");
        assertThat(b.get(1).get("professionalName").asString()).isEqualTo("João");
        assertThat(b.get(2).get("note").asString()).isEqualTo("Prefere máquina 2");
        assertThat(b.get(2).get("otherContact").asBoolean()).isFalse();
        assertThat(b.get(2).get("priceCents").asInt()).isEqualTo(4500);

        // na agenda: quem agendou aparece, e o cadastro sobre o qual agem exportar/apagar também
        JsonNode carla = agenda(s).get(1).get("customer");
        assertThat(carla.get("name").asString()).isEqualTo("Carla Dias");
        assertThat(carla.get("registeredName").asString()).isEqualTo("Bruno Lima");
    }

    @Test
    @DisplayName("Exportar mais de 1000 agendamentos: os 1000 mais recentes e a marca de corte")
    void exportTruncated() throws Exception {
        Shop s = shop();
        JsonNode first = panel(s, "09:00", "11987654321", null);
        String customer = first.get("customer").get("id").asString();
        ownerSql("""
                INSERT INTO bookings (id, tenant_id, professional_id, service_id, customer_id, starts_at, ends_at, blocked_until,
                                      status, price_cents, created_at, updated_at)
                SELECT gen_random_uuid(), ?::uuid, ?::uuid, ?::uuid, ?::uuid, t, t + interval '30 minutes', t + interval '30 minutes',
                       'CANCELLED', 0, t, t
                  FROM generate_series(timestamptz '2025-01-01', timestamptz '2025-01-01' + interval '1000 hours', interval '1 hour') t""",
                s.owner().tenantId(), s.joao(), s.corte(), customer);
        JsonNode e = body(call(get(s.base() + "/customers/" + customer + "/export"), s.owner().token(), null));
        assertThat(e.get("truncated").asBoolean()).isTrue();
        assertThat(e.get("bookings")).hasSize(1000);
        assertThat(e.get("bookings").get(0).get("start").asString()).isEqualTo(MONDAY + "T09:00:00-03:00");
    }

    @Test
    @DisplayName("Só o dono: profissional da equipe 403; outro estabelecimento e cliente inexistente 404 (exportar e apagar)")
    void access() throws Exception {
        Shop s = shop();
        String customer = panel(s, "09:00", "11987654321", null).get("customer").get("id").asString();
        Session pro = register("Paulo Reis", email(), "Loja do Paulo " + UUID.randomUUID().toString().substring(0, 6));
        addMember(s.owner().tenantId(), pro.userId(), "PROFESSIONAL");
        assertError(call(get(s.base() + "/customers/" + customer + "/export"), pro.token(), null), 403, "Only owners can do this");
        assertError(call(delete(s.base() + "/customers/" + customer), pro.token(), null), 403, "Only owners can do this");
        Shop eve = shop();
        assertError(call(get(s.base() + "/customers/" + customer + "/export"), eve.owner().token(), null), 404, "Tenant not found");
        // o cliente da Ana pelo estabelecimento da Eve: não existe lá
        assertError(call(get(eve.base() + "/customers/" + customer + "/export"), eve.owner().token(), null), 404, "Customer not found");
        assertError(call(delete(eve.base() + "/customers/" + customer), eve.owner().token(), null), 404, "Customer not found");
        assertThat(call(get(s.base() + "/customers/" + customer + "/export"), null, null).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Apagar com agendamento por acontecer: 409; depois de cancelar, anonimiza cadastro e agendamentos (o link para de valer)")
    void forget() throws Exception {
        Shop s = shop();
        JsonNode b = panel(s, "09:00", "11987654321", "Alergia a talco");
        String customer = b.get("customer").get("id").asString();
        String token = online(s, "10:00", "11987654321", email());
        assertError(call(delete(s.base() + "/customers/" + customer), s.owner().token(), null), 409, "Customer has upcoming bookings");

        assertThat(call(patch(s.base() + "/bookings/" + b.get("id").asString()), s.owner().token(), Map.of("status", "CANCELLED")).getStatus())
                .isEqualTo(200);
        assertThat(call(post(s.pub() + "/bookings/cancel"), null, Map.of("token", token)).getStatus()).isEqualTo(200);
        assertThat(call(delete(s.base() + "/customers/" + customer), s.owner().token(), null).getStatus()).isEqualTo(204);

        for (JsonNode a : agenda(s)) {
            assertThat(a.get("customer").get("name").asString()).isEqualTo("Cliente removido");
            assertThat(a.get("customer").get("phone").isNull()).isTrue();
            assertThat(a.get("customer").get("email").isNull()).isTrue();
            assertThat(a.get("note").isNull()).isTrue();
            assertThat(a.get("priceCents").asInt()).isEqualTo(4500);      // o caixa continua
        }
        assertThat(call(post(s.pub() + "/bookings/lookup"), null, Map.of("token", token)).getStatus()).isEqualTo(404);
        JsonNode e = body(call(get(s.base() + "/customers/" + customer + "/export"), s.owner().token(), null));
        assertThat(e.get("customer").get("anonymizedAt").isNull()).isFalse();
        assertThat(e.get("bookings").get(0).get("contactEmail").isNull()).isTrue();

        // de novo: inofensivo; o telefone liberado volta num cadastro novo
        assertThat(call(delete(s.base() + "/customers/" + customer), s.owner().token(), null).getStatus()).isEqualTo(204);
        String again = panel(s, "11:00", "11987654321", null).get("customer").get("id").asString();
        assertThat(again).isNotEqualTo(customer);
    }

    @Test
    @DisplayName("Apagar: pendente por acontecer também é 409; agendamento que já começou não impede")
    void forgetRules() throws Exception {
        Shop s = shop();
        String customer = panel(s, "09:00", "11987654321", null).get("customer").get("id").asString();
        MockHttpServletResponse h = call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T10:00"));
        call(post(s.pub() + "/bookings"), null, Map.of("holdToken", body(h).get("token").asString(),
                "customer", Map.of("name", "Bruno Lima", "phone", "11987654321", "email", email())));
        ownerSql("UPDATE bookings SET status = 'CANCELLED' WHERE customer_id = ?::uuid AND status = 'CONFIRMED'", customer);
        assertError(call(delete(s.base() + "/customers/" + customer), s.owner().token(), null), 409, "Customer has upcoming bookings");
        // o pendente vira um confirmado que começou 5 min antes de agora (relógio dos testes: 24/11 09:00 UTC)
        ownerSql("""
                UPDATE bookings SET status = 'CONFIRMED', code_hash = NULL, starts_at = timestamptz '2026-11-24 08:55Z',
                       ends_at = timestamptz '2026-11-24 09:25Z', blocked_until = timestamptz '2026-11-24 09:25Z'
                WHERE customer_id = ?::uuid AND status = 'PENDING'""", customer);
        assertThat(call(delete(s.base() + "/customers/" + customer), s.owner().token(), null).getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("Agendar enquanto o cadastro está sendo apagado: espera e cria um cadastro novo (nunca liga ao anonimizado)")
    void bookWhileForgetting() throws Exception {
        Shop s = shop();
        String customer = panel(s, "09:00", "11987654321", null).get("customer").get("id").asString();
        ownerSql("UPDATE bookings SET status = 'CANCELLED' WHERE customer_id = ?::uuid", customer);
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "agendai_owner", "owner-test")) {
            c.setAutoCommit(false);
            try (var st = c.prepareStatement("""
                    UPDATE customers SET name = 'Cliente removido', phone = NULL, email = NULL, anonymized_at = now()
                    WHERE id = ?::uuid""")) {
                st.setString(1, customer);
                st.executeUpdate();     // o "apagar" segura a linha, como o FOR UPDATE de CustomerPrivacy.forget
            }
            var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return panel(s, "10:00", "11987654321", null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(500);
            assertThat(pending).as("esperando o apagar").isNotDone();
            c.commit();
            JsonNode b = pending.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(b.get("customer").get("id").asString()).isNotEqualTo(customer);
            assertThat(b.get("customer").get("phone").asString()).isEqualTo("11987654321");
        }
    }

    @Test
    @DisplayName("Retenção: tentativa nunca confirmada some em 30 dias; sem agendamento há 24 meses, também; o resto fica")
    void retention() throws Exception {
        Shop s = shop();
        String tenant = s.owner().tenantId();
        String recent = panel(s, "09:00", "11900000001", null).get("customer").get("id").asString();
        String old = panel(s, "09:30", "11900000002", null).get("customer").get("id").asString();
        String attempt = pending(s, "10:00", "11900000003");
        String attemptNew = pending(s, "10:30", "11900000004");
        String none = UUID.randomUUID().toString();
        ownerSql("INSERT INTO customers (id, tenant_id, name, phone, created_at) VALUES (?::uuid, ?::uuid, 'Sem Agenda', '11900000005', now())",
                none, tenant);
        ownerSql("UPDATE bookings SET status = 'EXPIRED', code_hash = NULL WHERE tenant_id = ?::uuid AND source = 'PUBLIC'", tenant);

        // o tempo passa, no banco e relativo ao now() real (a função não deixa o relógio da API andar além dele). Os
        // cadastros deste teste ficam mais antigos que os das outras classes: entram primeiro na fila da retenção.
        ownerSql("UPDATE customers SET created_at = now() - interval '30 months' WHERE tenant_id = ?::uuid", tenant);
        ownerSql("""
                UPDATE bookings SET starts_at = now() - interval '1 month', ends_at = now() - interval '1 month' + interval '30 minutes',
                       blocked_until = now() - interval '1 month' + interval '30 minutes' WHERE customer_id = ?::uuid""", recent);
        ownerSql("""
                UPDATE bookings SET status = 'CANCELLED', starts_at = now() - interval '25 months',
                       ends_at = now() - interval '25 months' + interval '30 minutes',
                       blocked_until = now() - interval '25 months' + interval '30 minutes' WHERE customer_id = ?::uuid""", old);
        ownerSql("UPDATE bookings SET created_at = now() - interval '40 days' WHERE customer_id = ?::uuid", attempt);
        ownerSql("UPDATE bookings SET created_at = now() - interval '10 days' WHERE customer_id = ?::uuid", attemptNew);

        // relógio da API 3 anos à frente: a função usa o now() do banco — o recente não vence por isso
        clock.set(Instant.now().plus(java.time.Duration.ofDays(3 * 365)));
        // lotes de 1: a passada repete até esvaziar
        new CustomerRetention(customers, clock, tx, 1, 1000).run();
        assertThat(isAnonymized(recent)).as("agendamento há 1 mês").isFalse();
        assertThat(isAnonymized(attemptNew)).as("tentativa de 10 dias").isFalse();
        assertThat(isAnonymized(old)).as("só agendamento cancelado há 25 meses").isTrue();
        assertThat(isAnonymized(attempt)).as("tentativa de 40 dias").isTrue();
        assertThat(isAnonymized(none)).as("sem nenhum agendamento").isTrue();
        assertThat(ownerCount("""
                SELECT count(*) FROM bookings WHERE customer_id = ?::uuid
                AND contact_name IS NULL AND contact_email IS NULL AND access_hash IS NULL""", attempt)).isEqualTo(1);
    }

    @Autowired CustomerRepository customers;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    /** Pendente pela página, sem confirmar; devolve o id do cadastro. */
    String pending(Shop s, String time, String phone) throws Exception {
        MockHttpServletResponse h = call(post(s.pub() + "/holds"), null, Map.of("serviceId", s.corte(), "professionalId", s.joao(),
                "start", MONDAY + "T" + time));
        call(post(s.pub() + "/bookings"), null, Map.of("holdToken", body(h).get("token").asString(),
                "customer", Map.of("name", "Tentativa", "phone", phone, "email", email())));
        return customerByPhone(s.owner().tenantId(), phone);
    }

    String customerByPhone(String tenant, String phone) throws Exception {
        try (var c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "agendai_owner", "owner-test");
             var st = c.prepareStatement("SELECT id FROM customers WHERE tenant_id = ?::uuid AND phone = ?")) {
            st.setString(1, tenant);
            st.setString(2, phone);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    boolean isAnonymized(String id) throws Exception {
        return ownerCount("SELECT count(*) FROM customers WHERE id = ?::uuid AND anonymized_at IS NOT NULL AND phone IS NULL", id) == 1;
    }
}
