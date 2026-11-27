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
| `tenant` | Estabelecimento, equipe (participações), papéis (`OWNER` dono, `PROFESSIONAL` profissional); guard `TenantAccess` |
| `catalog` | Serviços (duração, intervalo, preço em centavos) e profissionais com os serviços que cada um faz; até 100 serviços e 50 profissionais por estabelecimento (listas sempre limitadas); convites para a equipe (vínculo profissional ↔ conta) e `CatalogService.actor` (quem age por qual profissional), usado pela agenda e pelos agendamentos |
| `schedule` | Expediente semanal por profissional (até 28 faixas), bloqueios e feriados (até 200 futuros por estabelecimento), cálculo de horários livres; usa o catálogo e o estabelecimento só pela API pública deles (`CatalogService.requireProfessional`/`offer`, `TenantService.zoneOf`) |
| `booking` | Agendamentos (criar, cancelar, atendido, falta; pela página: pendente → confirmado por código; pelo link pessoal: consultar, cancelar e remarcar até 3 vezes) e clientes (ADR-009); usa `catalog` (`offer`), `schedule` (`calendar`; implementa a porta `BusyPeriods`) e `tenant` (`TenantAccess`, `zoneOf`) — nenhum deles depende de `booking` (ArchUnit); os métodos da página e do link (`createPending`, `confirm`, `abandon`, `lookup`, `cancelByClient`, `rescheduleByClient`) só são chamados por `publicbooking`; LGPD (2d): exportar e anonimizar um cliente (só o dono, `CustomerPrivacy`) e a retenção (`CustomerRetention`, função `anonymize_stale_customers`, V9) |
| `publicbooking` | Rotas públicas do cliente (sem login) em `/api/public/{link}`: leitura (2a), agendar (2b: reserva de 5 min no Redis — `Holds` —, pendente com código por e-mail, confirmação) e link pessoal (2c: consultar, cancelar, remarcar). Entra pelo guard `tenant.PublicAccess` (ADR-011); aplica a política do estabelecimento (`TenantService.policyOf`) por cima das regras do painel. Horários livres da página em cache no Redis (`SlotCache`, ADR-012); a reserva lê o banco |
| `waitlist` | Lista de espera e oferta de vaga |
| `notification` | E-mail (`Mailer`; SMTP do provedor, Mailpit em dev/teste/CI). O código de confirmação sai síncrono, fora da transação (falhou → o pendente expira e 503). Os avisos ao cliente vão pelo outbox (3a): `Outbox` grava na transação de quem os causou, `OutboxPublisher` publica no RabbitMQ com confirmação, `EmailWorker` envia de forma idempotente, com novas tentativas e fila de falhas (ADR-003 e emendas) |
| `auth`, `security` | Login da equipe, tokens, limites, cabeçalhos |
| `db`, `config`, `common` | Infraestrutura: variáveis do RLS na transação, configuração e validação de produção, erros padronizados. `DbSession.enterTenant` avisa (`TenantDataChanged`) depois do commit de toda escrita num estabelecimento |
| `reporting` | Indicadores do painel |

Métodos que um módulo oferece a outro (ex.: `CatalogService.offer`) usam `Propagation.MANDATORY` e não repetem o
guard: só valem dentro de uma transação que já passou por `TenantAccess` (sem ela, o RLS esconde tudo e a resposta é
404). As fronteiras são verificadas por teste (ArchUnit): um módulo só usa a API pública de outro, nunca o repositório dele.
Exceção registrada: `user` é a identidade, e `auth`/`security`/`config` usam o `UserRepository` (login, filtro do
token). A partir da fase 1 a regra fica mais estrita (pendências da revisão da fase 0, abaixo).

## Modelo de dados (núcleo)

- `users` (conta da equipe, global: a mesma pessoa pode estar em vários estabelecimentos) e `refresh_tokens`
  (só o hash, família, uso e revogação — ADR-005). Sem `tenant_id` nem RLS por natureza; nunca saem pela API a não
  ser para o próprio usuário.
- `tenants`, `memberships` (usuário × estabelecimento × papel), `professionals`, `services`, `professional_services`.
- `working_hours` (por profissional e dia da semana, em minutos do dia no fuso local; a API troca a semana inteira
  de uma vez) e `time_off` (bloqueios em UTC; sem profissional = o estabelecimento inteiro).
- `bookings`: `tenant_id`, `professional_id`, `service_id`, `customer_id`, `starts_at`/`ends_at` (o atendimento),
  `blocked_until` (+ o preparo), `period tstzrange` gerado pelo banco de `[starts_at, blocked_until)`, `status`, preço,
  duração e preparo copiados do serviço na hora de agendar.
- `customers` (por estabelecimento: nome, telefone só com dígitos e único no estabelecimento, e-mail opcional — o
  mínimo, ver LGPD em seguranca.md). Criado com `INSERT ... ON CONFLICT DO NOTHING`: seguro com pedidos simultâneos.
- `waitlist_entries`, `outbox_events`, `processed_events` (idempotência do worker).

**Isolamento entre estabelecimentos** em três camadas: o guard de acesso na API, chaves estrangeiras compostas
`(id, tenant_id)` e **Row-Level Security** do PostgreSQL com o `tenant_id` da requisição (ADR-006).

## Concorrência: nunca dois agendamentos no mesmo horário

1. **No banco (garantia definitiva):**
   ```sql
   EXCLUDE USING gist (professional_id WITH =, period WITH &&)
       WHERE (status IN ('PENDING', 'CONFIRMED', 'COMPLETED', 'NO_SHOW'))
   ```
   Dois agendamentos que ocupam o horário (ADR-009) sobrepostos para o mesmo profissional são impossíveis — o período inclui o
   preparo depois do atendimento —, com qualquer número de instâncias e de requisições simultâneas. Criar agendamento
   não usa a trava de escrita do estabelecimento: quem decide é o banco. A violação vira `409 Time slot not available`
   (ADR-002). Depende da extensão `btree_gist`, criada pelo superusuário (`deploy/db-init/02-extensions.sh`; em banco
   existente, rode o db-init de novo antes de subir a API — README e teste de upgrade do CI).
2. **Reserva temporária no Redis (experiência do cliente):** ao escolher o horário, `SET reserva:{prof}:{inicio} {id}
   NX PX 300000` segura a vaga por 5 min enquanto o cliente confirma. Não substitui a restrição do banco: só evita que
   dois clientes preencham o formulário para o mesmo horário.

Teste obrigatório: 100 requisições simultâneas para o mesmo horário → exatamente 1 agendamento criado, 99 recebem 409
(`BookingIntegrationTest.hundredConcurrentRequests`; sem a restrição, o teste falha).

## Eventos: padrão outbox

Implementado na fase 3a (`notification`: `Outbox`, `OutboxPublisher`, `EmailWorker`, `Messaging`; V10):

1. Na **mesma transação** do agendamento, grava-se um registro em `outbox_events` (hoje o tipo `notification.email`,
   com destino, assunto e texto prontos; o conteúdo sai do banco quando é publicado).
2. Um publicador lê o outbox (`FOR UPDATE SKIP LOCKED`, seguro com várias instâncias) e publica no RabbitMQ com
   *publisher confirms*; só marca como publicado depois da confirmação.
3. O worker consome (o *ack* só sai quando o processamento termina sem erro), registra o id do evento em
   `processed_events` na mesma transação do envio (idempotência: evento repetido não manda e-mail duas vezes). Em
   falha, encaminha a mensagem (com confirmação do broker) para `agendai.notifications.email.wait`, que a devolve à
   fila depois de 1 min; depois de 180 tentativas, ou se a mensagem não tem conserto, vai para a fila de falhas
   (`agendai.notifications.email.dlq`, 7 dias). Tipos sem fila caem em `agendai.unrouted` (exchange alternativo).
   Filas *quorum*. O alerta da fila de falhas e o worker como processo separado vêm na 3b; na 3a o consumidor roda na
   própria API (`agendai.worker.enabled`).
4. O RabbitMQ fora do ar não derruba a API: o health dele fica fora do health da API, e os avisos esperam no outbox.
   O publicador usa uma conexão própria (broker bloqueando publicações não para os consumidores), e os jobs agendados
   têm 3 threads (outbox, expiração de pendentes e retenção não esperam uns pelos outros).

Garantia: um agendamento confirmado **sempre** gera seus avisos, mesmo que o RabbitMQ esteja fora no momento (ADR-003).

Lembretes (24 h e 2 h antes): agendados no banco; um job com trava distribuída (`SKIP LOCKED`) cria os eventos na hora
certa — sem `@Scheduled` que rodaria em todas as instâncias ao mesmo tempo.

## Datas e fusos

- Tudo guardado em UTC (`timestamptz`); cada estabelecimento tem seu fuso (`America/Sao_Paulo` por padrão).
- Cálculo de horários livres sempre no fuso do estabelecimento; o "hoje" vem de um `Clock` injetável.
- Testes rodam com a JVM em UTC e cobrem a virada do dia entre UTC e o fuso local.
- Expediente é hora de parede ("09:00 às 18:00" no fuso do estabelecimento), convertida para instantes no dia
  consultado: na troca de horário de verão o expediente continua começando às 09:00 locais. Bloqueios são instantes
  (a API recebe e devolve a data-hora local). Horários livres saem com o deslocamento (`2026-11-30T09:15:00-03:00`),
  sem ambiguidade na hora repetida.

## Escalabilidade

- API e worker sem estado: escalam horizontalmente; nenhum dado de uma requisição fica só na memória de uma instância.
- Limite por IP e reservas no **Redis** (compartilhados entre instâncias), não em mapas locais.
- Jobs com trava no banco (`SKIP LOCKED`) ou no Redis: rodam uma vez, não uma por instância.
- Nenhuma rota devolve lista sem limite: ou é paginada, ou tem teto fixo por estabelecimento (catálogo: 100 serviços e
  50 profissionais; agenda: 28 faixas por profissional, 200 bloqueios futuros, listagem de bloqueios por período de
  até 92 dias), garantido com a trava de escrita por estabelecimento (`DbSession.lockTenant`).
- Pool de conexões dimensionado e medido; índices definidos junto com cada consulta (e testados com `EXPLAIN` nos
  casos críticos).
- Leitura mais frequente (horários livres da página pública) com cache de 60 s no Redis desde a 2d, com uma versão por
  estabelecimento que sobe depois de cada escrita gravada nele (ADR-012). Pendente: os horários livres e a criação de
  agendamento fazem idas ao banco evitáveis (catálogo numa consulta só, cliente num comando só); medir no k6.
- Espera por trava é curta: `lock_timeout` de 3 s em toda conexão (trava de escrita do estabelecimento, horário em
  disputa na restrição de exclusão, cliente sendo criado) → 503 "Busy, try again", sem segurar a conexão; pool do
  Hikari com 20 conexões e 5 s de espera (medir com o teste de carga).
- Agendamentos: agenda de até 7 dias com teto de 500 itens (`truncated`; ativos antes dos cancelados) e até 1000
  criados por estabelecimento em 24 h. Clientes ainda não têm listagem; crescem no ritmo dos agendamentos e a retenção
  (LGPD) anonimiza a cada 6 h em lotes de 500, um atrás do outro (transações curtas) até a fila esvaziar ou 100 lotes.
  Pendente: a passada relê os clientes antigos ainda ativos (anti-join); com volume, uma coluna de última atividade
  indexada — medir com `EXPLAIN`. A exportação de um cliente tem teto de 1000 agendamentos (`truncated`).
- Redis com timeout curto (200 ms): lento ou fora, o limite por IP é pulado (com aviso no log), nunca trava a API.

### Pendências registradas na revisão da fase 0 (para a fase 1)

- Login: o BCrypt roda dentro da transação com a linha do usuário travada; mover a verificação para fora e gravar as
  falhas num `UPDATE` atômico, e dimensionar o pool do Hikari com teste de carga.
- `refresh_tokens`: job de limpeza dos vencidos (com trava distribuída) e índice parcial dos ativos.
- Página pública (2a): falta o cenário k6 dela (o cache entrou na 2d, ADR-012; medir com e sem); cerca de 10 idas ao banco por consulta de horários
  (o fuso é lido duas vezes); o limite de 120/min por IP pode pegar clientes atrás de CGNAT da operadora — medir e
  ajustar (ADR-011).
- Convite (1d): cadastro só de profissional (hoje o cadastro sempre cria um estabelecimento); `ProfessionalView.self`
  depende do usuário logado — para a página pública da fase 2, o vínculo deve vir em `/api/me`; o `Actor` faz uma
  consulta a mais por requisição (pode vir junto com a participação); limpeza de convites antigos.
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
