package com.sskings.agendai.catalog;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.db.DbSession;
import com.sskings.agendai.support.IntegrationTest;
import com.sskings.agendai.tenant.TenantService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Métodos que um módulo oferece a outro só valem dentro de uma transação que já passou pelo guard. */
class CrossModuleApiTest extends IntegrationTest {

    @Autowired CatalogService catalog;
    @Autowired TenantService tenants;
    @Autowired TransactionTemplate tx;
    @Autowired DbSession db;

    @Test
    @DisplayName("Fora de transação: recusados (Propagation.MANDATORY)")
    void requireTransaction() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> catalog.offer(id, id, id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> catalog.requireProfessional(id, id)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tenants.zoneOf(id)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("Em transação sem o estabelecimento definido pelo guard, o RLS esconde tudo: 404")
    void withoutGuardNothingIsVisible() throws Exception {
        Session ana = register();
        String joao = body(call(post("/api/tenants/" + ana.tenantId() + "/professionals"), ana.token(),
                Map.of("name", "João", "active", true, "serviceIds", List.of()))).get("id").asString();
        UUID tenant = UUID.fromString(ana.tenantId());
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> catalog.requireProfessional(tenant, UUID.fromString(joao))))
                .isInstanceOf(ApiException.class).hasMessage("Professional not found");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> tenants.zoneOf(tenant)))
                .isInstanceOf(ApiException.class).hasMessage("Tenant not found");
    }
}
