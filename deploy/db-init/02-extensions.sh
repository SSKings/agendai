#!/usr/bin/env bash
# Extensões do PostgreSQL que as migrações usam, criadas pelo SUPERUSUÁRIO: o dono das tabelas (que roda o Flyway) não
# tem CREATE no banco (01-roles.sh). A migração só confere que a extensão existe (CREATE EXTENSION IF NOT EXISTS).
#
#   btree_gist → restrição de exclusão dos agendamentos: professional_id WITH = (uuid) junto com period WITH && (ADR-002)
#
# Idempotente. Banco já existente (atualização): rode os scripts deste diretório de novo antes de subir a API nova, ex.:
#   docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T db \
#     sh -c 'for f in /docker-entrypoint-initdb.d/*.sh; do bash "$f" || exit 1; done'
set -euo pipefail
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS btree_gist;
SQL
echo "extensões do banco conferidas: btree_gist"
