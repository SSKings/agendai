-- Testes com os mesmos três usuários da produção (deploy/db-init/01-roles.sh): o superusuário só cria; o dono migra;
-- a API só tem DML e NÃO ignora o RLS. Sem isso, um teste de RLS rodando como superusuário provaria nada.
CREATE ROLE agendai_owner LOGIN PASSWORD 'owner-test' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE agendai_app   LOGIN PASSWORD 'app-test'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO agendai_owner;
GRANT USAGE ON SCHEMA public TO agendai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agendai_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO agendai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE agendai_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO agendai_app;
