# Arquitetura

## Visão geral

```
                 ┌──────────────┐
   navegador ──► │ nginx (TLS)  │──► SPA React (estático)
                 └──────┬───────┘
                        │ /api
              ┌─────────▼──────────┐        ┌──────────────────────┐
              │  API (Spring Boot) │──SQL──►│ PostgreSQL           │
              │  N instâncias,     │        │ (fonte da verdade;   │
              │  sem estado        │──────► │  outbox na mesma tx) │
              └───┬────────────┬───┘        └──────────▲───────────┘
                  │            │                       │
          Redis ◄─┘            │ publicador do outbox  │
   (reservas temporárias,      ▼                       │
    limite por IP, cache) ┌──────────┐   eventos   ┌───┴──────────────────┐
                          │ RabbitMQ │────────────►│ Worker de            │
                          └──────────┘             │ notificações         │──► e-mail / WhatsApp
                                                   │ (N instâncias)       │
                                                   └──────────────────────┘
```

- **API**: monolito modular. Não guarda estado em memória que importe entre requisições (nada de sessão, cache
  local ou contador local): pode rodar com quantas instâncias for preciso atrás do nginx.
- **Worker de notificações**: processo separado, consome eventos do RabbitMQ. Falha de e-mail não derruba a API.
- **PostgreSQL**: fonte da verdade. **Redis**: dados temporários e compartilhados entre instâncias. **RabbitMQ**:
  entrega de eventos com confirmação.

## Módulos da API

| Módulo | Responsabilidade |
|---|---|
| `user` | Identidade da equipe (conta, senha, versão dos tokens); `/api/me` |
| `tenant` | Estabelecimento, equipe, convites, papéis (`OWNER` dono, `PROFESSIONAL` profissional) |
| `catalog` | Serviços (duração, intervalo, preço em centavos) e profissionais com os serviços que cada um faz; até 100 serviços e 50 profissionais por estabelecimento (listas sempre limitadas) |
| `schedule` | Expediente, bloqueios, cálculo de horários livres |
| `booking` | Agendamentos (criar, cancelar, remarcar, atendido, falta), reservas temporárias |
| `publicbooking` | Rotas públicas do cliente (sem login), verificação por código, links pessoais |
| `waitlist` | Lista de espera e oferta de vaga |
| `notification` | Outbox e publicação de eventos (o envio fica no worker) |
| `auth`, `security` | Login da equipe, tokens, limites, cabeçalhos |
| `db`, `config`, `common` | Infraestrutura: variáveis do RLS na transação, configuração e validação de produção, erros padronizados |
| `reporting` | Indicadores do painel |

As fronteiras são verificadas por teste (ArchUnit): um módulo só usa a API pública de outro, nunca o repositório dele.
Exceção registrada: `user` é a identidade, e `auth`/`security`/`config` usam o `UserRepository` (login, filtro do
token). A partir da fase 1 a regra fica mais estrita (pendências da revisão da fase 0, abaixo).

## Modelo de dados (núcleo)

- `users` (conta da equipe, global: a mesma pessoa pode estar em vários estabelecimentos) e `refresh_tokens`
  (só o hash, família, uso e revogação — ADR-005). Sem `tenant_id` nem RLS por natureza; nunca saem pela API a não
  ser para o próprio usuário.
- `tenants`, `memberships` (usuário × estabelecimento × papel), `professionals`, `services`, `professional_services`.
- `working_hours` (por profissional e dia da semana), `time_off` (bloqueios).
- `bookings`: `tenant_id`, `professional_id`, `service_id`, `customer_id`, `period tstzrange`, `status`.
- `customers` (por estabelecimento: nome, e-mail, telefone — o mínimo, ver LGPD em seguranca.md).
- `waitlist_entries`, `outbox_events`, `processed_events` (idempotência do worker).

**Isolamento entre estabelecimentos** em três camadas: o guard de acesso na API, chaves estrangeiras compostas
`(id, tenant_id)` e **Row-Level Security** do PostgreSQL com o `tenant_id` da requisição (ADR-006).

## Concorrência: nunca dois agendamentos no mesmo horário

1. **No banco (garantia definitiva):**
   ```sql
   EXCLUDE USING gist (professional_id WITH =, period WITH &&) WHERE (status IN ('PENDING', 'CONFIRMED'))
   ```
   Dois agendamentos ativos sobrepostos para o mesmo profissional são impossíveis, com qualquer número de instâncias
   e de requisições simultâneas. A violação vira `409 Conflict` com mensagem clara (ADR-002).
2. **Reserva temporária no Redis (experiência do cliente):** ao escolher o horário, `SET reserva:{prof}:{inicio} {id}
   NX PX 300000` segura a vaga por 5 min enquanto o cliente confirma. Não substitui a restrição do banco: só evita que
   dois clientes preencham o formulário para o mesmo horário.

Teste obrigatório: 100 requisições simultâneas para o mesmo horário → exatamente 1 agendamento criado, 99 recebem 409.

## Eventos: padrão outbox

1. Na **mesma transação** do agendamento, grava-se um registro em `outbox_events` (ex.: `BookingConfirmed`).
2. Um publicador lê o outbox (`FOR UPDATE SKIP LOCKED`, seguro com várias instâncias) e publica no RabbitMQ com
   *publisher confirms*; só marca como publicado depois da confirmação.
3. O worker consome com *ack* manual, registra o id do evento em `processed_events` (idempotência: evento repetido
   não manda e-mail duas vezes) e, em falha, tenta de novo com espera crescente; esgotadas as tentativas, a mensagem
   vai para a fila de falhas (*dead-letter*) e gera alerta.

Garantia: um agendamento confirmado **sempre** gera seus avisos, mesmo que o RabbitMQ esteja fora no momento (ADR-003).

Lembretes (24 h e 2 h antes): agendados no banco; um job com trava distribuída (`SKIP LOCKED`) cria os eventos na hora
certa — sem `@Scheduled` que rodaria em todas as instâncias ao mesmo tempo.

## Datas e fusos

- Tudo guardado em UTC (`timestamptz`); cada estabelecimento tem seu fuso (`America/Sao_Paulo` por padrão).
- Cálculo de horários livres sempre no fuso do estabelecimento; o "hoje" vem de um `Clock` injetável.
- Testes rodam com a JVM em UTC e cobrem a virada do dia entre UTC e o fuso local.

## Escalabilidade

- API e worker sem estado: escalam horizontalmente; nenhum dado de uma requisição fica só na memória de uma instância.
- Limite por IP e reservas no **Redis** (compartilhados entre instâncias), não em mapas locais.
- Jobs com trava no banco (`SKIP LOCKED`) ou no Redis: rodam uma vez, não uma por instância.
- Nenhuma rota devolve lista sem limite: ou é paginada, ou tem teto fixo por estabelecimento (catálogo: 100 serviços e
  50 profissionais), garantido com a trava de escrita por estabelecimento (`DbSession.lockTenant`).
- Pool de conexões dimensionado e medido; índices definidos junto com cada consulta (e testados com `EXPLAIN` nos
  casos críticos).
- Leitura mais frequente (horários livres da página pública) com cache curto no Redis, invalidado pelos eventos de
  agendamento e de expediente.
- Redis com timeout curto (200 ms): lento ou fora, o limite por IP é pulado (com aviso no log), nunca trava a API.

### Pendências registradas na revisão da fase 0 (para a fase 1)

- Login: o BCrypt roda dentro da transação com a linha do usuário travada; mover a verificação para fora e gravar as
  falhas num `UPDATE` atômico, e dimensionar o pool do Hikari com teste de carga.
- `refresh_tokens`: job de limpeza dos vencidos (com trava distribuída) e índice parcial dos ativos.
- Fronteiras: principal próprio (DTO) em vez da entidade `User` no contexto de segurança; regra ArchUnit sem ciclos
  entre pacotes (`user` ↔ `tenant`, `user` ↔ `security`).
- Métrica (Micrometer) para "Redis indisponível", além do log.

## Desempenho: metas (SLOs)

| Operação | p95 | Observação |
|---|---|---|
| Horários livres de um dia (página pública) | < 150 ms | com cache quente; < 300 ms sem cache |
| Criar agendamento | < 250 ms | inclui a restrição de exclusão e o outbox |
| Agenda da semana (painel) | < 200 ms | paginada por profissional |
| Evento → e-mail enfileirado no provedor | < 30 s | do commit ao envio |

As metas viram limites do teste de carga (k6): o CI falha se forem ultrapassadas (ver testes.md).

## Observabilidade

OpenTelemetry (traces da requisição ao SQL e do evento ao envio), métricas de negócio (agendamentos, cancelamentos,
faltas, eventos na fila de falhas) e logs em JSON sem dados pessoais. Alertas para fila de falhas com mensagens,
outbox atrasado e latência acima do SLO.

## Contrato da API

Especificação OpenAPI gerada do código e versionada em `docs/openapi.json`; o front gera os tipos TypeScript dela. O CI
falha se a API mudar sem o contrato acompanhar (mesmo modelo do Bolso).
