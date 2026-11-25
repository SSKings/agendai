-- Fase 1d: convite para a equipe. O dono convida um profissional já cadastrado; quem aceita (com a própria conta)
-- passa a participar do estabelecimento como PROFESSIONAL e fica vinculado àquele profissional.

-- Conta vinculada ao profissional (uma conta por profissional, um profissional por conta no estabelecimento). A FK
-- composta aponta para a PARTICIPAÇÃO: tirar a pessoa da equipe (apagar a participação) desfaz o vínculo sozinho, e
-- nunca existe vínculo sem participação (ADR-010).
ALTER TABLE professionals ADD COLUMN user_id UUID;
ALTER TABLE professionals ADD FOREIGN KEY (tenant_id, user_id) REFERENCES memberships (tenant_id, user_id) ON DELETE SET NULL (user_id);
CREATE UNIQUE INDEX ux_professionals_user ON professionals (tenant_id, user_id) WHERE user_id IS NOT NULL;

-- Convite: código de uso único (no banco, só o SHA-256), expira em 72 h, revogável; um novo revoga o anterior.
CREATE TABLE invites (
    id              UUID        PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    professional_id UUID        NOT NULL,
    token_hash      VARCHAR(64) NOT NULL UNIQUE,
    created_by      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    used_by         UUID        REFERENCES users (id) ON DELETE SET NULL,
    revoked_at      TIMESTAMPTZ,
    CHECK (created_at < expires_at),
    FOREIGN KEY (professional_id, tenant_id) REFERENCES professionals (id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX ix_invites_professional ON invites (professional_id);
-- apagar uma conta (LGPD) não varre a tabela inteira
CREATE INDEX ix_invites_created_by ON invites (created_by);
CREATE INDEX ix_invites_used_by ON invites (used_by) WHERE used_by IS NOT NULL;

-- agenda de um profissional (a que o profissional da equipe abre), com ou sem cancelados
CREATE INDEX ix_bookings_tenant_professional_start ON bookings (tenant_id, professional_id, starts_at);

ALTER TABLE invites ENABLE ROW LEVEL SECURITY;
CREATE POLICY invites_tenant ON invites FOR ALL
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());

-- Aceitar: quem foi convidado ainda não participa — o RLS esconde o convite e a política de memberships só aceita o
-- dono de um estabelecimento novo (V1). Esta função faz só isto, com os direitos do dono das tabelas, para o usuário
-- da transação (app_user()) e no instante "at_ts" (o relógio da aplicação, o mesmo que gravou expires_at): confere o
-- código, marca o uso, cria a participação e vincula o profissional. Resultado: (estabelecimento, 'OK' | 'INVALID' | 'MEMBER'); nenhum dado do convite sai daqui.
CREATE FUNCTION redeem_invite(hash text, at_ts timestamptz)
    RETURNS TABLE (tenant uuid, outcome text)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS
$$
DECLARE
    inv invites%ROWTYPE;
    uid uuid := app_user();
BEGIN
    IF uid IS NULL THEN
        RETURN QUERY SELECT NULL::uuid, 'INVALID'::text;
        RETURN;
    END IF;
    SELECT * INTO inv FROM invites WHERE token_hash = hash FOR UPDATE;
    IF NOT FOUND OR inv.used_at IS NOT NULL OR inv.revoked_at IS NOT NULL OR inv.expires_at <= at_ts THEN
        RETURN QUERY SELECT NULL::uuid, 'INVALID'::text;
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM memberships m WHERE m.tenant_id = inv.tenant_id AND m.user_id = uid) THEN
        RETURN QUERY SELECT inv.tenant_id, 'MEMBER'::text;
        RETURN;
    END IF;
    -- profissional ainda sem conta e ativo (convite que sobrou para quem já tem vínculo, ou foi desativado, não vale)
    PERFORM 1 FROM professionals WHERE id = inv.professional_id AND user_id IS NULL AND active FOR UPDATE;
    IF NOT FOUND THEN
        RETURN QUERY SELECT NULL::uuid, 'INVALID'::text;
        RETURN;
    END IF;
    -- a mesma conta aceitando dois convites do estabelecimento ao mesmo tempo: o segundo vira 'MEMBER', não erro
    INSERT INTO memberships (tenant_id, user_id, role, joined_at) VALUES (inv.tenant_id, uid, 'PROFESSIONAL', at_ts)
        ON CONFLICT (tenant_id, user_id) DO NOTHING;
    IF NOT FOUND THEN
        RETURN QUERY SELECT inv.tenant_id, 'MEMBER'::text;
        RETURN;
    END IF;
    UPDATE professionals SET user_id = uid WHERE id = inv.professional_id;
    UPDATE invites SET used_at = at_ts, used_by = uid WHERE id = inv.id;
    RETURN QUERY SELECT inv.tenant_id, 'OK'::text;
END
$$;

-- A função só é chamada pela aplicação (o único papel com USAGE no schema). pg_temp por último no search_path: uma
-- tabela temporária com o mesmo nome nunca é usada no lugar da real. Vale também para as funções da V1.
ALTER FUNCTION tenant_unclaimed(uuid) SET search_path = public, pg_temp;
ALTER FUNCTION slug_taken(text) SET search_path = public, pg_temp;
