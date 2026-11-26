-- Fase 2d (LGPD, docs/seguranca.md): o cliente anonimizado perde nome, telefone e e-mail; os agendamentos dele ficam
-- (horário, serviço, profissional, preço: a agenda e o caixa do estabelecimento), sem o contato. O telefone liberado
-- pode voltar num cadastro novo (o UNIQUE (tenant_id, phone) ignora os nulos).
ALTER TABLE customers
    ALTER COLUMN phone DROP NOT NULL,
    ADD COLUMN anonymized_at TIMESTAMPTZ,
    ADD CONSTRAINT customers_anonymized_check CHECK ((anonymized_at IS NULL) = (phone IS NOT NULL));

-- o job de retenção só olha quem ainda não foi anonimizado, do mais antigo para o mais novo
CREATE INDEX ix_customers_retention ON customers (created_at) WHERE anonymized_at IS NULL;

-- Retenção, em todos os estabelecimentos (o job não tem estabelecimento na transação: o RLS esconderia tudo). Anonimiza
-- quem foi cadastrado há mais de 30 dias e não tem:
--   - agendamento de verdade (não EXPIRED) com início nos últimos 24 meses ou no futuro; nem
--   - tentativa pela página (EXPIRED: o código nunca foi confirmado) dos últimos 30 dias.
-- Ou seja: tentativa nunca confirmada some em 30 dias; cliente sem agendamento há 24 meses, também. No máximo max_rows
-- por vez (entre 1 e 5000; SKIP LOCKED: várias instâncias ao mesmo tempo não se esperam nem repetem, e quem está
-- agendando com o cadastro — FOR SHARE — o segura até a próxima passada). Devolve quantos anonimizou.
CREATE FUNCTION anonymize_stale_customers(at_ts timestamptz, max_rows integer) RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS
$$
DECLARE
    t timestamptz := LEAST(at_ts, now());                            -- quem chama não adianta o relógio
    batch integer := GREATEST(1, LEAST(COALESCE(max_rows, 1), 5000));  -- nulo não vira "sem limite"
    n integer;
BEGIN
    WITH stale AS (
        SELECT c.id, c.tenant_id
          FROM customers c
         WHERE c.anonymized_at IS NULL
           AND c.created_at < t - interval '30 days'
           AND NOT EXISTS (
                SELECT 1 FROM bookings b
                 WHERE b.customer_id = c.id AND b.tenant_id = c.tenant_id
                   AND ((b.status <> 'EXPIRED' AND b.starts_at >= t - interval '24 months')
                     OR (b.status = 'EXPIRED' AND b.created_at >= t - interval '30 days')))
         ORDER BY c.created_at
         LIMIT batch
           FOR UPDATE SKIP LOCKED
    ), done AS (
        UPDATE customers c
           SET name = 'Cliente removido', phone = NULL, email = NULL, anonymized_at = t
          FROM stale
         WHERE c.id = stale.id
     RETURNING c.id, c.tenant_id
    ), contacts AS (
        UPDATE bookings b
           SET contact_name = NULL, contact_email = NULL, access_hash = NULL, note = NULL, updated_at = t
          FROM done
         WHERE b.customer_id = done.id AND b.tenant_id = done.tenant_id
    )
    SELECT count(*) INTO n FROM done;
    RETURN n;
END
$$;
