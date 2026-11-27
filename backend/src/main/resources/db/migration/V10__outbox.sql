-- Fase 3a: outbox (ADR-003). O aviso ao cliente é gravado na MESMA transação do que o causou (confirmou, cancelou,
-- remarcou): ou os dois ficam, ou nenhum. Um publicador o leva ao RabbitMQ com confirmação; o worker manda o e-mail.
CREATE TABLE outbox_events (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    type         VARCHAR(40) NOT NULL,
    -- o conteúdo (e-mail de destino, texto com o link pessoal) só fica aqui até ser publicado
    payload      JSONB,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CHECK ((published_at IS NULL) = (payload IS NOT NULL))
);
-- o publicador só olha os pendentes, do mais antigo para o mais novo
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_tenant ON outbox_events (tenant_id);

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY outbox_events_tenant ON outbox_events FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());

-- Idempotência do worker: o mesmo evento entregue duas vezes tem efeito uma vez só (por consumidor).
CREATE TABLE processed_events (
    consumer     VARCHAR(40) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- O publicador não tem estabelecimento na transação (o RLS esconderia tudo): estas duas funções fazem só isto, com os
-- direitos do dono das tabelas. Pegar trava os eventos até o fim da transação de quem chamou (SKIP LOCKED: várias
-- instâncias dividem o trabalho sem publicar o mesmo evento duas vezes); marcar apaga o conteúdo.
CREATE FUNCTION outbox_claim(max_rows integer)
    RETURNS TABLE (id uuid, type varchar, payload jsonb)
    LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS
$$
    SELECT o.id, o.type, o.payload FROM outbox_events o
     WHERE o.published_at IS NULL
     ORDER BY o.created_at
     LIMIT GREATEST(1, LEAST(COALESCE(max_rows, 1), 500))
       FOR UPDATE SKIP LOCKED
$$;

CREATE FUNCTION outbox_published(ids uuid[], at_ts timestamptz) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS
$$
DECLARE n integer;
BEGIN
    UPDATE outbox_events SET published_at = LEAST(at_ts, now()), payload = NULL
     WHERE id = ANY (ids) AND published_at IS NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END
$$;
