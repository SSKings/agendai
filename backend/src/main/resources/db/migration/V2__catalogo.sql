-- Fase 1: catálogo e equipe. Toda tabela tem tenant_id, chave estrangeira COMPOSTA (id, tenant_id) — uma ligação
-- entre estabelecimentos diferentes é impossível no banco — e RLS (ADR-006).

CREATE TABLE services (
    id               UUID         PRIMARY KEY,
    tenant_id        UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name             VARCHAR(100) NOT NULL,
    duration_minutes INT          NOT NULL CHECK (duration_minutes BETWEEN 5 AND 480 AND duration_minutes % 5 = 0),
    buffer_minutes   INT          NOT NULL CHECK (buffer_minutes BETWEEN 0 AND 120 AND buffer_minutes % 5 = 0),  -- preparo/limpeza
    price_cents      INT          NOT NULL CHECK (price_cents BETWEEN 0 AND 10000000),
    active           BOOLEAN      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, tenant_id)
);
-- nome único no estabelecimento sem diferenciar maiúsculas; também atende a listagem por estabelecimento
CREATE UNIQUE INDEX ux_services_name ON services (tenant_id, lower(name));

CREATE TABLE professionals (
    id         UUID         PRIMARY KEY,
    tenant_id  UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    active     BOOLEAN      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, tenant_id)
);
CREATE UNIQUE INDEX ux_professionals_name ON professionals (tenant_id, lower(name));

-- Quem faz o quê
CREATE TABLE professional_services (
    tenant_id       UUID NOT NULL,
    professional_id UUID NOT NULL,
    service_id      UUID NOT NULL,
    PRIMARY KEY (professional_id, service_id),
    FOREIGN KEY (professional_id, tenant_id) REFERENCES professionals (id, tenant_id) ON DELETE CASCADE,
    FOREIGN KEY (service_id, tenant_id) REFERENCES services (id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX ix_professional_services_service ON professional_services (service_id);
CREATE INDEX ix_professional_services_tenant ON professional_services (tenant_id);

ALTER TABLE services ENABLE ROW LEVEL SECURITY;
ALTER TABLE professionals ENABLE ROW LEVEL SECURITY;
ALTER TABLE professional_services ENABLE ROW LEVEL SECURITY;

-- Só o estabelecimento da requisição (definido pelo guard de acesso), para ler e para escrever
CREATE POLICY services_tenant ON services FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
CREATE POLICY professionals_tenant ON professionals FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
CREATE POLICY professional_services_tenant ON professional_services FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
