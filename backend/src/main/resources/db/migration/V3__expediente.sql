-- Fase 1b: expediente semanal por profissional e bloqueios (folga, férias, feriado). Mesmas regras da V2: tenant_id,
-- chave estrangeira composta (id, tenant_id) e RLS (ADR-006).

-- Faixas de trabalho por dia da semana, em minutos desde 00:00 no fuso do estabelecimento (09:00 = 540). Mais de uma
-- faixa no dia = pausa entre elas (almoço). A API troca a semana inteira de uma vez, com a trava de escrita do
-- estabelecimento, e recusa faixas sobrepostas.
CREATE TABLE working_hours (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    professional_id UUID NOT NULL,
    weekday         INT  NOT NULL CHECK (weekday BETWEEN 1 AND 7),      -- ISO-8601: 1 = segunda … 7 = domingo
    start_minute    INT  NOT NULL,
    end_minute      INT  NOT NULL,
    CHECK (start_minute >= 0 AND end_minute <= 1440 AND start_minute < end_minute
           AND start_minute % 5 = 0 AND end_minute % 5 = 0),
    FOREIGN KEY (professional_id, tenant_id) REFERENCES professionals (id, tenant_id) ON DELETE CASCADE
);
-- toda consulta é por profissional (semana, dia, troca da semana); também atende o ON DELETE CASCADE
CREATE INDEX ix_working_hours_professional ON working_hours (professional_id, weekday);

-- Bloqueio pontual em UTC. Sem profissional = o estabelecimento inteiro (feriado, reforma).
CREATE TABLE time_off (
    id              UUID         PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    professional_id UUID,
    start_at        TIMESTAMPTZ  NOT NULL,
    end_at          TIMESTAMPTZ  NOT NULL,
    reason          VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL,
    CHECK (start_at < end_at),
    FOREIGN KEY (professional_id, tenant_id) REFERENCES professionals (id, tenant_id) ON DELETE CASCADE
);
-- teto dos bloqueios futuros ("termina depois de agora")
CREATE INDEX ix_time_off_tenant_end ON time_off (tenant_id, end_at);
-- período (listagem e horários livres): um bloqueio dura no máximo 92 dias, então quem encosta em [de, até) começou
-- em [de - 92 dias, até) — faixa limitada do índice, já na ordem da listagem, por mais histórico que exista
CREATE INDEX ix_time_off_tenant_start ON time_off (tenant_id, start_at);
CREATE INDEX ix_time_off_professional ON time_off (professional_id);

ALTER TABLE working_hours ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_off ENABLE ROW LEVEL SECURITY;

CREATE POLICY working_hours_tenant ON working_hours FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
CREATE POLICY time_off_tenant ON time_off FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
