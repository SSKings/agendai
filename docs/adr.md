# Decisões de arquitetura (ADRs)

## ADR-001 — Monolito modular + worker de notificações

**Contexto:** projeto de uma pessoa, com foco em qualidade. Microsserviços trariam rede, versionamento e operação
sem benefício real neste tamanho.
**Decisão:** a API é um monolito com módulos de fronteiras verificadas por teste (ArchUnit). O envio de notificações
fica num **worker separado**, ligado por RabbitMQ — a parte que de fato se beneficia de isolamento (falha de e-mail não
derruba a API) e de escala independente.
**Consequência:** deploy simples; se um módulo precisar virar serviço, as fronteiras já existem.

## ADR-002 — Conflito de horário resolvido pelo banco (restrição de exclusão)

**Contexto:** "nunca dois clientes no mesmo horário" é a regra mais importante do produto e tem de valer com várias
instâncias e requisições simultâneas. Checar no código ("existe agendamento?" e depois inserir) tem janela de corrida.
**Decisão:** `EXCLUDE USING gist (professional_id WITH =, period WITH &&)` nos agendamentos ativos. O Redis só segura
o horário durante o preenchimento (experiência), nunca é a garantia.
**Consequência:** a regra vale mesmo com bug no código; exige a extensão `btree_gist` e testes de concorrência reais.

## ADR-003 — Eventos pelo padrão outbox

**Contexto:** publicar no RabbitMQ dentro da transação do agendamento ou depois dela perde eventos quando um dos dois
falha.
**Decisão:** o evento é gravado em `outbox_events` na mesma transação; um publicador o envia com confirmação; o worker
é idempotente (`processed_events`) e usa novas tentativas com espera crescente e fila de falhas.
**Consequência:** entrega "pelo menos uma vez" com efeito "exatamente uma vez" no worker; atraso de alguns segundos.

## ADR-004 — TDD e 100% de cobertura exigidos pelo CI

**Contexto:** o objetivo do projeto é mostrar engenharia confiável; os projetos anteriores mostraram que cobertura
abaixo de 100% esconde exatamente os ramos de segurança (limpeza de memória, regras do validador).
**Decisão:** testes antes do código; o CI exige 100% de linhas e ramos, com exceções listadas e justificadas; PIT
≥ 95% no domínio e na segurança.
**Consequência:** desenvolvimento mais lento no começo; cada funcionalidade nasce com a prova de que funciona.

## ADR-005 — Sessão: access token em memória e refresh token em cookie HttpOnly com rotação

**Contexto:** no Bolso, o JWT no `localStorage` ficou como risco aceito (um XSS leria o token).
**Decisão:** access token de 15 min só na memória do front; refresh token em cookie `HttpOnly; Secure;
SameSite=Strict`, de uso único com rotação; reuso de um refresh já trocado revoga a família inteira (sinal de roubo).
**Consequência:** um XSS não consegue levar uma sessão de longa duração; o front renova o token em segundo plano.

## ADR-006 — Isolamento entre estabelecimentos em três camadas, com Row-Level Security

**Contexto:** vazamento entre estabelecimentos é o pior incidente possível para o produto.
**Decisão:** (1) guard de acesso na API; (2) consultas e FKs compostas com `tenant_id`; (3) **RLS no PostgreSQL**, com o
`tenant_id` da requisição definido na transação (`set_config('app.tenant_id', ..., true)`, equivalente a `SET LOCAL`;
o usuário vai em `app.user_id`, definido pelo `RlsTransactionManager` no início de cada transação), e o usuário da API
sem permissão de ignorar o RLS. `users` e `refresh_tokens` são globais (sem `tenant_id`): ficam fora do RLS.
**Consequência:** um bug numa consulta não vaza dados; testes precisam cobrir as três camadas, inclusive cada uma
sozinha.

## ADR-007 — Redis para dados temporários e compartilhados

**Contexto:** limite por IP e reservas temporárias em memória local quebram com mais de uma instância.
**Decisão:** reservas (com expiração), limites por IP/estabelecimento e o cache de horários livres ficam no Redis.
Se o Redis cair, a API continua agendando (a garantia é o banco), sem cache e com limite degradado registrado em alerta.
**Consequência:** mais um serviço na pilha; comportamento com o Redis fora é testado.

## ADR-008 — Tempo na agenda: expediente em hora local, bloqueios em UTC, horários livres com deslocamento

**Contexto:** o dono pensa em "segunda, das 09:00 às 18:00" no fuso do estabelecimento; o banco guarda instantes; o
front pode estar em outro fuso; zonas com horário de verão têm dias de 23 e 25 horas e horas repetidas.
**Decisão:**
- Expediente é **hora de parede**: dia da semana ISO + minutos desde 00:00 no fuso do estabelecimento (`24:00` = fim
  do dia), convertido para instantes no dia consultado. Na troca de horário de verão o expediente continua começando às
  09:00 locais.
- Bloqueios são **instantes** (`timestamptz`), mas a API recebe e devolve a data-hora **local, sem deslocamento**
  (`2026-12-25T00:00`, formato `local-date-time` no contrato): quem cadastra pensa no relógio da loja, não no do
  navegador. Valor com deslocamento ou `Z` é recusado (400), nunca reinterpretado.
- Horários livres saem como `OffsetDateTime` (`2026-11-30T09:15:00-03:00`): sem ambiguidade na hora repetida.
- Horários começam de 15 em 15 min a partir do início de cada faixa (grade da página pública); expediente, bloqueios e
  durações andam de 5 em 5. O agendamento manual do painel (1c) poderá usar qualquer início de 5 em 5 que caiba.
- A duração máxima de um bloqueio (92 dias) é em tempo real: atravessando o fim do horário de verão, 92 dias de
  calendário têm 1 hora a mais e são recusados.
- 180 dias é o teto técnico da consulta; a política do estabelecimento ("quantos dias à frente a agenda abre") ficará
  sempre abaixo dele.
- `working_hours` não tem restrição de exclusão: a semana é trocada inteira, validada na aplicação sob a trava de
  escrita do estabelecimento (`DbSession.lockTenant`), e o banco garante o resto com CHECKs. A restrição de exclusão
  dos agendamentos (ADR-002) precisa da extensão `btree_gist`, que o dono das tabelas não pode criar (só tem
  `CONNECT, TEMPORARY`): na 1c ela entra pelo superusuário, em `deploy/db-init` **e** em `test-roles.sql` (CI e
  produção iguais), com o passo documentado para banco já existente.
- Dependência entre módulos: `booking` (1c) usa `schedule` (expediente e bloqueios) — nunca o contrário. Para os
  horários livres considerarem agendamentos, `schedule` define uma porta ("períodos ocupados") que `booking`
  implementa.
**Consequência:** a conversão local ↔ UTC fica num lugar só (`ScheduleService`), coberta por testes de virada do dia
UTC × local e de horário de verão; a 1c herda o formato sem decidir de novo.
