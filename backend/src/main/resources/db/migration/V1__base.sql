-- Agendaí — fase 0: equipe, estabelecimentos e sessão.
--
-- Isolamento entre estabelecimentos em três camadas (ADR-006). Esta migração faz a terceira: Row-Level Security.
-- A API define, a cada transação, app.user_id (quem está logado) e app.tenant_id (o estabelecimento da rota, depois
-- que o guard confere a participação). O usuário da API não é dono das tabelas: o RLS vale para ele.

CREATE FUNCTION app_user() RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION app_tenant() RETURNS uuid LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

-- Conta da equipe (global: o login acontece antes de existir um estabelecimento na requisição)
CREATE TABLE users (
    id             UUID PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    email          VARCHAR(150) NOT NULL CHECK (email = lower(email)),
    password_hash  VARCHAR(100) NOT NULL,
    token_version  INT          NOT NULL DEFAULT 0,
    failed_logins  INT          NOT NULL DEFAULT 0,
    lock_count     INT          NOT NULL DEFAULT 0,
    locked_until   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_users_email ON users (email);

CREATE TABLE tenants (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    slug        VARCHAR(60)  NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    time_zone   VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE TABLE memberships (
    tenant_id  UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'PROFESSIONAL')),
    joined_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX ix_memberships_user ON memberships (user_id);

-- Refresh token: só o SHA-256; uso único com rotação dentro da família; reuso revoga a família (ADR-005)
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

-- ---------- Row-Level Security ----------
-- "Ninguém participa ainda deste estabelecimento": precisa enxergar as participações de todos (o RLS esconderia),
-- então roda com os direitos do dono das tabelas e responde só sim/não. search_path fixo: sem sequestro de função.
CREATE FUNCTION tenant_unclaimed(tid uuid) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT NOT EXISTS (SELECT 1 FROM memberships WHERE tenant_id = tid) $$;

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;

-- Participação: vê as do estabelecimento da requisição e as próprias (para listar "meus estabelecimentos").
-- Cria só para si mesmo E só em estabelecimento ainda sem ninguém (o dono, no cadastro). Sem a 2ª condição, um bug
-- que deixasse o tenant_id vir do cliente punha qualquer um dentro de qualquer estabelecimento. A fase 1 acrescenta
-- "OU convite pendente para este usuário" (também numa função SECURITY DEFINER).
CREATE POLICY memberships_visible ON memberships FOR SELECT
    USING (tenant_id = app_tenant() OR user_id = app_user());
CREATE POLICY memberships_insert ON memberships FOR INSERT
    WITH CHECK (user_id = app_user() AND tenant_unclaimed(tenant_id));
CREATE POLICY memberships_change ON memberships FOR UPDATE
    USING (tenant_id = app_tenant()) WITH CHECK (tenant_id = app_tenant());
CREATE POLICY memberships_delete ON memberships FOR DELETE
    USING (tenant_id = app_tenant());

-- Estabelecimento: vê e altera só aquele em que participa; criar é livre (o dono cria o seu no cadastro).
CREATE POLICY tenants_visible ON tenants FOR SELECT
    USING (id = app_tenant() OR EXISTS (SELECT 1 FROM memberships m WHERE m.tenant_id = tenants.id AND m.user_id = app_user()));
CREATE POLICY tenants_insert ON tenants FOR INSERT
    WITH CHECK (true);
CREATE POLICY tenants_change ON tenants FOR UPDATE
    USING (id = app_tenant()) WITH CHECK (id = app_tenant());

-- Link público único entre TODOS os estabelecimentos, mas o RLS esconde os dos outros. Esta função responde só
-- "existe ou não" (nenhum dado), com os direitos do dono das tabelas. search_path fixo: sem sequestro de função.
CREATE FUNCTION slug_taken(candidate text) RETURNS boolean
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public AS
$$ SELECT EXISTS (SELECT 1 FROM tenants WHERE slug = candidate) $$;
