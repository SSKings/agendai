-- Fase 2b: agendamento pela página pública, confirmado por código no e-mail (ADR-009/011).
-- PENDING ocupa o horário (o predicado da restrição de exclusão já o incluía desde a V4) até confirmar ou vencer;
-- EXPIRED é final e libera o horário (fora do predicado: só o CHECK muda, a restrição não é recriada).
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED', 'EXPIRED'));

ALTER TABLE bookings
    -- até quando o código vale (e o pendente segura o horário)
    ADD COLUMN pending_until TIMESTAMPTZ,
    -- SHA-256 de "id do agendamento:código de 6 dígitos"; apagado ao confirmar ou expirar
    ADD COLUMN code_hash     VARCHAR(64),
    ADD COLUMN code_attempts INT         NOT NULL DEFAULT 0 CHECK (code_attempts BETWEEN 0 AND 5),
    -- SHA-256 do token (256 bits) que só o cliente tem: confirmar agora, cancelar/remarcar na 2c
    ADD COLUMN access_hash   VARCHAR(64),
    ADD COLUMN source        VARCHAR(8)  NOT NULL DEFAULT 'PANEL' CHECK (source IN ('PANEL', 'PUBLIC')),
    -- pela página: o nome e o e-mail de QUEM confirmou o código (o cadastro por telefone pode ser de outra pessoa) —
    -- é o que a agenda mostra
    ADD COLUMN contact_name  VARCHAR(100),
    ADD COLUMN contact_email VARCHAR(254),
    -- pendente sempre com prazo, código e token
    ADD CONSTRAINT bookings_pending_check
        CHECK (status <> 'PENDING' OR (pending_until IS NOT NULL AND code_hash IS NOT NULL AND access_hash IS NOT NULL));

CREATE UNIQUE INDEX ux_bookings_access ON bookings (access_hash) WHERE access_hash IS NOT NULL;
-- o job de expiração só olha os pendentes
CREATE INDEX ix_bookings_pending ON bookings (pending_until) WHERE status = 'PENDING';
-- tetos por estabelecimento e origem (painel × página) nas últimas 24 h
CREATE INDEX ix_bookings_tenant_source_created ON bookings (tenant_id, source, created_at);

-- Expira os pendentes vencidos de todos os estabelecimentos. O job não tem estabelecimento na transação (o RLS
-- esconderia tudo): esta função faz só isto, com os direitos do dono das tabelas. Um UPDATE só: várias instâncias
-- rodando ao mesmo tempo não fazem mal (a segunda não acha nada). Devolve quantos expirou.
CREATE FUNCTION expire_pending_bookings(at_ts timestamptz) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS
$$
DECLARE n integer;
BEGIN
    UPDATE bookings SET status = 'EXPIRED', code_hash = NULL, updated_at = at_ts
     WHERE status = 'PENDING' AND pending_until <= LEAST(at_ts, now());   -- quem chama não adianta o relógio
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;
