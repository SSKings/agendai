#!/bin/sh
# Usuário do RabbitMQ (ADR-003): só o worker fala com o broker (publica o outbox, consome e encaminha); a API não tem
# credencial nenhuma. Roda a cada `up` (serviço rabbitmq-init do docker-compose.prod.yml), idempotente: cria ou troca a
# senha. Fala com o nó pelo cookie do Erlang (volume do broker, só leitura) — não existe usuário administrador.
#
#   agendai_worker  declara, lê e publica só no que é agendai.*, mais o exchange padrão (fila de espera e de falhas).
#                   Atenção: pelo exchange padrão dá para publicar em QUALQUER fila do vhost — hoje só existem as
#                   agendai.*; uma fila de outro sistema neste vhost ficaria ao alcance do worker.
#
# Qualquer outro usuário é apagado: o guest da imagem e o administrador da 3a (RABBITMQ_DEFAULT_USER). Para depurar,
# use o rabbitmqctl de dentro do container do broker (pelo cookie), não um usuário criado à mão.
set -eu

NODE="${RABBITMQ_NODE:-rabbit@rabbitmq}"
ctl() { rabbitmqctl -q -n "$NODE" "$@"; }

# senhas de openssl rand -hex 24: só caracteres que dispensam escape no JSON abaixo
check() {
  case "$2" in
    *[!A-Za-z0-9._-]* | "") echo "$1: use só letras, números, ponto, hífen e sublinhado (openssl rand -hex 24)" >&2; exit 1 ;;
  esac
  [ "${#2}" -ge 16 ] || { echo "$1: mínimo de 16 caracteres (openssl rand -hex 24)" >&2; exit 1; }
}
check RABBITMQ_WORKER_PASSWORD "${RABBITMQ_WORKER_PASSWORD:-}"

# A senha vai num arquivo do tmpfs do container (some com ele), não na linha de comando: o rabbitmqctl não a lê da
# entrada padrão sem terminal. Nas expressões, \\. no JSON é \. na regex (ponto literal).
umask 077
DEFS=/tmp/agendai-users.json
trap 'rm -f "$DEFS"' EXIT
cat > "$DEFS" <<EOF
{
  "users": [
    {"name": "agendai_worker", "password": "$RABBITMQ_WORKER_PASSWORD", "tags": []}
  ],
  "vhosts": [{"name": "/"}],
  "permissions": [
    {"user": "agendai_worker", "vhost": "/", "configure": "^agendai\\\\.", "write": "^(agendai\\\\..*|amq\\\\.default)$", "read": "^agendai\\\\."}
  ]
}
EOF
ctl import_definitions "$DEFS" >/dev/null
rm -f "$DEFS"

# a importação é assíncrona: espera as permissões aparecerem (e confere que são exatamente estas)
expected='agendai_worker	^agendai\.	^(agendai\..*|amq\.default)$	^agendai\.'
for _ in $(seq 1 30); do
  got=$(ctl list_permissions -p / --no-table-headers | awk -F '\t' '$1 == "agendai_worker"')
  [ "$got" = "$expected" ] && break
  sleep 1
done
[ "$got" = "$expected" ] || { echo "permissões não conferem:" >&2; echo "$got" >&2; exit 1; }

for user in $(ctl list_users --no-table-headers | cut -f1); do
  case "$user" in
    agendai_worker) ;;
    *) ctl delete_user "$user" >/dev/null && echo "usuário removido: $user" ;;
  esac
done
echo "usuário do RabbitMQ pronto: agendai_worker"
