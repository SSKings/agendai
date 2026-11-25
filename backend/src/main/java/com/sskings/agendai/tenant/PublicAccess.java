package com.sskings.agendai.tenant;

import com.sskings.agendai.common.ApiException;
import com.sskings.agendai.db.DbSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Guard das rotas públicas (sem login): o link do estabelecimento vira o estabelecimento da transação no RLS. Link
 * malformado ou que não existe → 404, sem distinguir os dois. Daí em diante, só os dados desse estabelecimento.
 */
@Component
@RequiredArgsConstructor
public class PublicAccess {

    static final String NOT_FOUND = "Business not found";
    /** Links gerados por {@link Slugs}: letras minúsculas, dígitos e hífen (com o sufixo "-2", "-3"... no máximo 60). */
    private static final String SLUG = "[a-z0-9-]{1,60}";

    public record PublicBusiness(UUID id, String name, String timeZone) {}

    private final TenantRepository tenants;
    private final DbSession db;

    @Transactional(propagation = Propagation.MANDATORY)
    public PublicBusiness enter(String slug) {
        if (!slug.matches(SLUG)) throw ApiException.notFound(NOT_FOUND);   // o link vem do caminho: nunca nulo
        UUID id = tenants.idBySlug(slug).orElseThrow(() -> ApiException.notFound(NOT_FOUND));
        db.enterTenant(id);
        Tenant t = tenants.findById(id).orElseThrow();
        return new PublicBusiness(t.getId(), t.getName(), t.getTimeZone());
    }
}
