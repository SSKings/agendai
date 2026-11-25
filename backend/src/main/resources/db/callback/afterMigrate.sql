-- Só o dono das tabelas (usuário das migrações) mexe no histórico do Flyway: os privilégios padrão dão DML ao usuário
-- da API em toda tabela nova, inclusive nesta, que o Flyway cria depois (lição do ShopEasy, revisão 5).
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT DISTINCT grantee FROM information_schema.role_table_grants
             WHERE table_schema = 'public' AND table_name = 'flyway_schema_history' AND grantee <> current_user
    LOOP
        EXECUTE format('REVOKE ALL ON public.flyway_schema_history FROM %I', r.grantee);
    END LOOP;
END $$;
