-- Fase 2a: página pública do estabelecimento, sem login. A requisição anônima não tem usuário nem estabelecimento,
-- então o RLS esconde tudo — inclusive o próprio estabelecimento. Esta função responde só "qual é o id deste link"
-- (nenhum outro dado), com os direitos do dono das tabelas; a aplicação então entra nesse estabelecimento
-- (app.tenant_id) e as políticas de sempre valem: a página pública só enxerga o que é dele.
CREATE FUNCTION tenant_by_slug(candidate text) RETURNS uuid
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS
$$ SELECT id FROM tenants WHERE slug = candidate $$;
