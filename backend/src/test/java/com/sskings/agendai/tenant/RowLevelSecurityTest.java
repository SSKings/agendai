package com.sskings.agendai.tenant;

import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3ª camada do ADR-006: Row-Level Security, testada SEM o guard da API — como se uma consulta tivesse um bug e
 * esquecesse de filtrar. A conexão é a do usuário da aplicação (sem privilégio de ignorar o RLS).
 */
class RowLevelSecurityTest extends IntegrationTest {

    @Autowired TransactionTemplate tx;
    @Autowired TenantRepository tenants;
    @Autowired MembershipRepository memberships;
    @Autowired DbSession db;
    @PersistenceContext EntityManager em;

    @Test
    @DisplayName("A API conecta com um usuário que não é superusuário nem ignora o RLS (senão este teste não provaria nada)")
    void appUserIsUnprivileged() {
        List<?> row = tx.execute(s -> em.createNativeQuery(
                "SELECT rolsuper, rolbypassrls, current_user FROM pg_roles WHERE rolname = current_user").getResultList());
        Object[] r = (Object[]) row.get(0);
        assertThat(r[0]).isEqualTo(false);
        assertThat(r[1]).isEqualTo(false);
        assertThat(r[2]).isEqualTo("agendai_app");
    }

    @Test
    @DisplayName("Consulta sem filtro (bug) dentro do estabelecimento A só enxerga A e as próprias participações")
    void unfilteredQueryOnlySeesOwnTenant() throws Exception {
        Session ana = register();
        Session bia = register("Bia", email(), "Clínica da Bia");
        UUID anaUser = UUID.fromString(ana.userId());
        UUID anaTenant = UUID.fromString(ana.tenantId());
        tx.executeWithoutResult(s -> {
            db.actAs(anaUser);
            db.enterTenant(anaTenant);
            assertThat(tenants.findAll()).extracting(Tenant::getId).containsExactly(anaTenant);
            assertThat(tenants.findById(UUID.fromString(bia.tenantId()))).isEmpty();
            assertThat(memberships.findAll()).extracting(Membership::getTenantId).containsExactly(anaTenant);
        });
    }

    @Test
    @DisplayName("Sem ninguém na transação (rota anônima), nenhum estabelecimento nem participação aparece")
    void anonymousSeesNothing() throws Exception {
        register();
        tx.executeWithoutResult(s -> {
            assertThat(tenants.findAll()).isEmpty();
            assertThat(memberships.findAll()).isEmpty();
        });
    }

    @Test
    @DisplayName("Usuário vê os estabelecimentos em que participa mesmo fora de um deles (lista de 'meus estabelecimentos')")
    void userSeesOwnMemberships() throws Exception {
        Session ana = register();
        tx.executeWithoutResult(s -> {
            db.actAs(UUID.fromString(ana.userId()));
            assertThat(tenants.findAll()).extracting(t -> t.getId().toString()).containsExactly(ana.tenantId());
        });
    }

    @Test
    @DisplayName("Não dá para se colocar em estabelecimento alheio: inserir participação de outro usuário é barrado pelo banco")
    void cannotInsertMembershipForSomeoneElse() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            memberships.saveAndFlush(new Membership(UUID.fromString(ana.tenantId()), UUID.fromString(ana.userId()),
                    Role.PROFESSIONAL, Instant.now()));
        })).hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("Não dá para entrar sozinho em estabelecimento que já tem dono, nem com o próprio usuário (convite: fase 1)")
    void cannotJoinForeignTenant() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            memberships.saveAndFlush(new Membership(UUID.fromString(ana.tenantId()), UUID.fromString(eve.userId()),
                    Role.OWNER, Instant.now()));
        })).hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("Dentro do próprio estabelecimento, UPDATE e DELETE em linhas de outro estabelecimento não afetam nada")
    void cannotChangeForeignRows() throws Exception {
        Session ana = register();
        Session eve = register("Eve", email(), "Loja da Eve");
        int[] affected = tx.execute(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            db.enterTenant(UUID.fromString(eve.tenantId()));
            return new int[]{
                    em.createNativeQuery("UPDATE tenants SET name = 'invadido' WHERE id = cast(:id as uuid)")
                            .setParameter("id", ana.tenantId()).executeUpdate(),
                    em.createNativeQuery("UPDATE memberships SET role = 'PROFESSIONAL' WHERE tenant_id = cast(:id as uuid)")
                            .setParameter("id", ana.tenantId()).executeUpdate(),
                    em.createNativeQuery("DELETE FROM memberships WHERE tenant_id = cast(:id as uuid)")
                            .setParameter("id", ana.tenantId()).executeUpdate()};
        });
        assertThat(affected).containsExactly(0, 0, 0);
        // controle: no próprio estabelecimento as mesmas operações funcionam (o teste não passa por erro de SQL)
        Integer own = tx.execute(s -> {
            db.actAs(UUID.fromString(eve.userId()));
            db.enterTenant(UUID.fromString(eve.tenantId()));
            return em.createNativeQuery("UPDATE tenants SET name = 'Loja da Eve 2' WHERE id = cast(:id as uuid)")
                    .setParameter("id", eve.tenantId()).executeUpdate();
        });
        assertThat(own).isEqualTo(1);
    }

    @Test
    @DisplayName("O link público é único entre todos os estabelecimentos, mesmo invisíveis pelo RLS (função slug_taken)")
    void slugTakenSeesAll() throws Exception {
        register("Dono", email(), "Studio Pilates Zen 9z");
        Boolean taken = tx.execute(s -> tenants.slugTaken("studio-pilates-zen-9z"));
        Boolean free = tx.execute(s -> tenants.slugTaken("nao-existe-" + UUID.randomUUID()));
        assertThat(taken).isTrue();
        assertThat(free).isFalse();
    }
}
