-- Fase 1c: agendamentos sem conflito (ADR-002). Mesmas regras das anteriores: tenant_id, FKs compostas e RLS.

-- Criada pelo superusuário (deploy/db-init/02-extensions.sh; nos testes, test-roles.sql): o dono das tabelas não tem
-- CREATE no banco. Aqui só confere; se faltar, a migração falha com "permission denied" em vez de seguir sem a regra.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Cliente do estabelecimento: o mínimo (docs/seguranca.md, LGPD). Telefone só com dígitos, único no estabelecimento:
-- o mesmo cliente que liga de novo não vira outro cadastro.
CREATE TABLE customers (
    id         UUID         PRIMARY KEY,
    tenant_id  UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    phone      VARCHAR(15)  NOT NULL CHECK (phone ~ '^[0-9]{10,15}$'),
    email      VARCHAR(254),
    created_at TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, tenant_id),
    UNIQUE (tenant_id, phone)
);

-- starts_at → ends_at: o atendimento; até blocked_until: + o preparo depois (a agenda do profissional fica ocupada).
-- Duração, preparo e preço são copiados do serviço na hora de agendar: mudar o serviço depois não mexe no que já
-- foi marcado.
CREATE TABLE bookings (
    id              UUID         PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    professional_id UUID         NOT NULL,
    service_id      UUID         NOT NULL,
    customer_id     UUID         NOT NULL,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    blocked_until   TIMESTAMPTZ  NOT NULL,
    period          TSTZRANGE    GENERATED ALWAYS AS (tstzrange(starts_at, blocked_until, '[)')) STORED,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    price_cents     INT          NOT NULL CHECK (price_cents BETWEEN 0 AND 10000000),
    note            VARCHAR(200),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    -- até 480 min de serviço + 120 de preparo (limites do catálogo): as consultas "o que ocupa" contam com isso
    CHECK (starts_at < ends_at AND ends_at <= blocked_until AND blocked_until - starts_at <= interval '600 minutes'),
    FOREIGN KEY (professional_id, tenant_id) REFERENCES professionals (id, tenant_id),
    FOREIGN KEY (service_id, tenant_id) REFERENCES services (id, tenant_id),
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customers (id, tenant_id),
    -- A garantia definitiva: dois agendamentos que ocupam o horário (BookingStatus.OCCUPYING) do mesmo profissional
    -- nunca se sobrepõem, com qualquer número de instâncias e de requisições simultâneas. O predicado já inclui o
    -- PENDING da fase 2 (ADR-009): novos status finais entram só no CHECK, sem recriar esta restrição.
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (professional_id WITH =, period WITH &&)
        WHERE (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW'))
);
-- agenda do dia/semana do estabelecimento
CREATE INDEX ix_bookings_tenant_start ON bookings (tenant_id, starts_at);
-- o que ocupa um profissional (horários livres): um agendamento dura no máximo 10 h (480 + 120 min), então "começa
-- entre X - 10 h e Y" é uma faixa curta deste índice; cancelados ficam fora (não ocupam e podem se acumular)
CREATE INDEX ix_bookings_professional_start ON bookings (professional_id, starts_at)
    WHERE status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW');
-- teto de agendamentos criados nas últimas 24 h por estabelecimento
CREATE INDEX ix_bookings_tenant_created ON bookings (tenant_id, created_at);
-- histórico do cliente e anonimização da LGPD (job de retenção, docs/seguranca.md)
CREATE INDEX ix_bookings_customer ON bookings (customer_id);

ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE bookings ENABLE ROW LEVEL SECURITY;

CREATE POLICY customers_tenant ON customers FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
CREATE POLICY bookings_tenant ON bookings FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
