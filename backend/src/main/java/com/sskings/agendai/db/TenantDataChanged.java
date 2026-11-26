package com.sskings.agendai.db;

import java.util.UUID;

/**
 * Uma transação de escrita que entrou neste estabelecimento terminou com commit: o que se calcula dos dados dele (os
 * horários livres em cache) pode ter mudado. Publicado por {@link DbSession} — nenhuma escrita precisa lembrar de avisar.
 */
public record TenantDataChanged(UUID tenantId) {}
