package com.sskings.agendai.tenant;

import com.sskings.agendai.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 1ª camada do ADR-006: o guard da API. Estabelecimento de outro dono é "inexistente" (404, sem revelar). */
class TenantIsolationTest extends IntegrationTest {

    @Test
    @DisplayName("Dono vê o próprio estabelecimento com o papel; o de outro e um inexistente dão o mesmo 404")
    void isolation() throws Exception {
        Session ana = register();
        Session eve = register("Eve Intrusa", email(), "Barbearia da Eve");
        JsonNode own = body(call(get("/api/tenants/" + ana.tenantId()), ana.token(), null));
        assertThat(own.get("role").asString()).isEqualTo("OWNER");
        assertThat(own.get("name").asString()).isEqualTo("Barbearia da Ana");

        MockHttpServletResponse foreign = call(get("/api/tenants/" + ana.tenantId()), eve.token(), null);
        MockHttpServletResponse ghost = call(get("/api/tenants/" + UUID.randomUUID()), eve.token(), null);
        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(foreign.getContentAsString()).isEqualTo(ghost.getContentAsString())
                .isEqualTo("{\"status\":404,\"message\":\"Tenant not found\",\"errors\":null}");
        assertThat(body(call(get("/api/me"), eve.token(), null)).get("tenants").findValuesAsString("id"))
                .containsExactly(eve.tenantId());
        assertThat(call(get("/api/tenants/" + ana.tenantId()), null, null).getStatus()).isEqualTo(401);
        assertThat(call(get("/api/tenants/nao-e-uuid"), ana.token(), null).getStatus()).isEqualTo(400);
    }
}
