package com.sskings.agendai.booking;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 3ª camada do ADR-006 e a restrição de exclusão (ADR-002) direto no banco, sem a API por cima. */
class BookingRowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;


    record Setup(BookingIntegrationTest.Shop ana, BookingIntegrationTest.Shop eve, JsonNode anaBooking, JsonNode eveBooking) {}

    private Setup setup() throws Exception {
        BookingIntegrationTest.Shop ana = shop();
        BookingIntegrationTest.Shop eve = shop();
        JsonNode a = body(call(post(ana.base() + "/bookings"), ana.owner().token(),
                BookingIntegrationTest.booking(ana.joao(), ana.corte(), "2026-11-30T09:00", "11987654321")));
        JsonNode e = body(call(post(eve.base() + "/bookings"), eve.owner().token(),
                BookingIntegrationTest.booking(eve.joao(), eve.corte(), "2026-11-30T09:00", "11912345678")));
        return new Setup(ana, eve, a, e);
    }

    private BookingIntegrationTest.Shop shop() throws Exception {
        Session s = register();
        String base = "/api/tenants/" + s.tenantId();
        String corte = body(call(post(base + "/services"), s.token(), java.util.Map.of("name", "Corte", "durationMinutes", 30,
                "bufferMinutes", 10, "priceCents", 0, "active", true))).get("id").asString();
        String joao = body(call(post(base + "/professionals"), s.token(), java.util.Map.of("name", "João", "active", true,
                "serviceIds", List.of(corte)))).get("id").asString();
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "/professionals/" + joao + "/working-hours"),
                s.token(), java.util.Map.of("intervals", List.of(java.util.Map.of("weekday", 1, "start", "09:00", "end", "12:00"))));
        return new BookingIntegrationTest.Shop(s, corte, joao, joao);
    }

    private <T> T asEve(Setup s, TransactionCallback<T> work) {
        return tx.execute(status -> {
            db.actAs(UUID.fromString(s.eve().owner().userId()));
            db.enterTenant(UUID.fromString(s.eve().owner().tenantId()));
            return work.doInTransaction(status);
        });
    }

    private static final String INSERT = """
            INSERT INTO bookings (id, tenant_id, professional_id, service_id, customer_id, starts_at, ends_at, blocked_until, status,
                                  price_cents, created_at, updated_at)
            VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), cast(:s as uuid), cast(:c as uuid),
                    timestamptz '2026-11-30 12:20Z', timestamptz '2026-11-30 12:50Z', timestamptz '2026-11-30 13:00Z', :status, 0, now(), now())""";

    private int insert(Setup s, String tenant, String professional, String service, String customer, String status) {
        return asEve(s, st -> em.createNativeQuery(INSERT).setParameter("t", tenant).setParameter("p", professional)
                .setParameter("s", service).setParameter("c", customer).setParameter("status", status).executeUpdate());
    }

    @Test
    @DisplayName("Consulta sem filtro no estabelecimento da Eve não enxerga agendamentos nem clientes da Ana")
    void foreignRowsInvisible() throws Exception {
        Setup s = setup();
        List<?> counts = asEve(s, st -> List.of(
                em.createNativeQuery("SELECT count(*) FROM bookings").getSingleResult(),
                em.createNativeQuery("SELECT count(*) FROM customers").getSingleResult(),
                em.createNativeQuery("UPDATE bookings SET status = 'CANCELLED' WHERE tenant_id <> cast(:t as uuid)")
                        .setParameter("t", s.eve().owner().tenantId()).executeUpdate(),
                em.createNativeQuery("DELETE FROM customers WHERE tenant_id <> cast(:t as uuid)")
                        .setParameter("t", s.eve().owner().tenantId()).executeUpdate()));
        assertThat(counts).extracting(Object::toString).containsExactly("1", "1", "0", "0");
    }

    @Test
    @DisplayName("Inserir agendamento ou cliente com o tenant_id da Ana é barrado; FKs compostas impedem misturar estabelecimentos")
    void foreignTenantAndCompositeKeys() throws Exception {
        Setup s = setup();
        String anaCustomer = s.anaBooking().get("customer").get("id").asString();
        String eveCustomer = s.eveBooking().get("customer").get("id").asString();
        assertThatThrownBy(() -> insert(s, s.ana().owner().tenantId(), s.ana().joao(), s.ana().corte(), anaCustomer, "CONFIRMED"))
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery(
                "INSERT INTO customers (id, tenant_id, name, phone, created_at) VALUES (gen_random_uuid(), cast(:t as uuid), 'x', '11900000000', now())")
                .setParameter("t", s.ana().owner().tenantId()).executeUpdate())).hasMessageContaining("row-level security");
        assertThatThrownBy(() -> insert(s, s.eve().owner().tenantId(), s.ana().joao(), s.eve().corte(), eveCustomer, "CANCELLED"))
                .hasMessageContaining("bookings_professional_id_tenant_id_fkey");
        assertThatThrownBy(() -> insert(s, s.eve().owner().tenantId(), s.eve().joao(), s.ana().corte(), eveCustomer, "CANCELLED"))
                .hasMessageContaining("bookings_service_id_tenant_id_fkey");
        assertThatThrownBy(() -> insert(s, s.eve().owner().tenantId(), s.eve().joao(), s.eve().corte(), anaCustomer, "CANCELLED"))
                .hasMessageContaining("bookings_customer_id_tenant_id_fkey");
    }

    @Test
    @DisplayName("WITH CHECK no UPDATE: mover cliente da Eve para o estabelecimento da Ana é barrado")
    void cannotMoveRowToForeignTenant() throws Exception {
        Setup s = setup();
        ownerSql("INSERT INTO customers (id, tenant_id, name, phone, created_at) VALUES (gen_random_uuid(), ?, 'Solto', '11933334444', now())",
                UUID.fromString(s.eve().owner().tenantId()));
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("UPDATE customers SET tenant_id = cast(:t as uuid) WHERE phone = '11933334444'")
                .setParameter("t", s.ana().owner().tenantId()).executeUpdate())).hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("Restrição de exclusão no banco: sobreposição não cancelada é impossível mesmo sem a API; cancelado pode sobrepor")
    void exclusionConstraint() throws Exception {
        Setup s = setup();
        String eve = s.eve().owner().tenantId();
        String customer = s.eveBooking().get("customer").get("id").asString();
        // o da API ocupa 09:00–09:40 local (12:00–12:40Z); este começa 12:20Z
        assertThatThrownBy(() -> insert(s, eve, s.eve().joao(), s.eve().corte(), customer, "CONFIRMED")).hasMessageContaining("bookings_no_overlap");
        assertThatThrownBy(() -> insert(s, eve, s.eve().joao(), s.eve().corte(), customer, "COMPLETED")).hasMessageContaining("bookings_no_overlap");
        assertThat(insert(s, eve, s.eve().joao(), s.eve().corte(), customer, "CANCELLED")).isEqualTo(1);
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery("""
                INSERT INTO bookings (id, tenant_id, professional_id, service_id, customer_id, starts_at, ends_at, blocked_until, status,
                                      price_cents, created_at, updated_at)
                VALUES (gen_random_uuid(), cast(:t as uuid), cast(:p as uuid), cast(:s as uuid), cast(:c as uuid),
                        timestamptz '2026-12-01 12:00Z', timestamptz '2026-12-01 22:00Z', timestamptz '2026-12-01 22:05Z', 'CANCELLED', 0, now(), now())""")
                .setParameter("t", eve).setParameter("p", s.eve().joao()).setParameter("s", s.eve().corte()).setParameter("c", customer)
                .executeUpdate())).as("mais de 10 h ocupadas").hasMessageContaining("bookings_check");
        assertThatThrownBy(() -> insert(s, eve, s.eve().joao(), s.eve().corte(), customer, "PENDING")).hasMessageContaining("bookings_status_check");
        assertThatThrownBy(() -> asEve(s, st -> em.createNativeQuery(
                "INSERT INTO customers (id, tenant_id, name, phone, created_at) VALUES (gen_random_uuid(), cast(:t as uuid), 'x', '119', now())")
                .setParameter("t", eve).executeUpdate())).hasMessageContaining("customers_phone_check");
    }
}
