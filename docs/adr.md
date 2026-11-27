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

**Emenda (fase 2b, temporária até a fase 3):** o código de confirmação do agendamento pela página sai por e-mail
síncrono, na requisição, porque o outbox e o worker ainda não existem. Para não violar o espírito desta decisão: o
pendente é gravado numa transação própria e o e-mail sai fora dela (provedor lento não segura conexão nem trava); se
o envio falha, o pendente expira na hora e a resposta é 503 — nada fica valendo sem o código. Na fase 3 o envio passa
para o outbox.
**Emenda (fase 2c, temporária até a fase 3):** os avisos ao cliente (confirmado, com o link pessoal; cancelado;
remarcado) saem depois do commit, fora da transação, e são "melhor esforço": se o provedor falhar, o resultado já
gravado vale e o aviso se perde (fica no log). O link pessoal também aparece na tela da confirmação. A garantia "pelo
menos uma vez" deste ADR chega com o outbox da fase 3.
**Emenda (fase 3a):** os avisos ao cliente (confirmado, cancelado, remarcado) vão para o `outbox_events` na mesma
transação; o publicador (`OutboxPublisher`, a cada 1 s, `SKIP LOCKED` pela função `outbox_claim`) publica com
confirmação do broker e só então marca como publicado — e apaga o conteúdo (e-mail, texto com o link pessoal) do
banco. O worker (`EmailWorker`) é idempotente pelo id do evento (`processed_events`, na transação do envio). Falha de
envio (provedor fora) não dorme no thread nem se perde: a mensagem vai para uma fila de espera, que a devolve depois de
1 min, até 180 tentativas (3 h de provedor fora sem perder aviso); esgotadas, ou mensagem sem conserto (malformada),
vai para a fila de falhas (7 dias). O que o broker não consegue rotear (tipo sem fila) fica numa fila própria pelo
exchange alternativo, não some depois da confirmação. Os tipos de evento são versionados (`notification.email.v1`).
Ainda pode haver e-mail repetido num caso raro: o provedor aceitou e a transação do worker caiu antes do commit
("pelo menos uma vez"). O **código de confirmação continua síncrono**, por decisão: é parte da interação (o
cliente espera por ele na tela, e a falha vira 503 com o pendente expirado — emenda da 2b), e gravá-lo em claro no
outbox poria um segredo de 10 minutos no banco e no broker.

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
Exceção (fase 2b): a página pública não reserva sem o Redis (503 "tente de novo") — a reserva de 5 min é parte do
fluxo do cliente; o painel continua agendando normalmente.
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

## ADR-009 — Ciclo de vida do agendamento e identidade do cliente

**Contexto:** a restrição de exclusão (ADR-002) depende de quais status "ocupam" o horário, e mudar o predicado
depois exige recriar o índice GiST com trava na tabela. A fase 2 traz a reserva pela página pública (PENDING) e a
confirmação por código; o cliente precisa de uma identidade estável por estabelecimento.
**Decisão:**
- Status: `CONFIRMED` → `COMPLETED` (atendido) | `NO_SHOW` (falta) | `CANCELLED`, todos finais. Atendido e falta só
  depois do início; cancelar a qualquer momento. A transição é um `UPDATE ... WHERE status = 'CONFIRMED'`: com dois
  pedidos simultâneos, um vale e o outro recebe 409.
- Ocupam o horário: `PENDING`, `CONFIRMED`, `COMPLETED`, `NO_SHOW` (atendido e falta mantêm o histórico do horário).
  O predicado da restrição já inclui o `PENDING` da fase 2; um status final novo que libere o horário (ex.: `EXPIRED`
  para reserva não confirmada) entra só no CHECK, sem recriar a restrição. No código, `BookingStatus.OCCUPYING` é a
  mesma lista e é usada em "o que ocupa".
- Fase 2b: a reserva de 5 min fica só no Redis; o `PENDING` nasce no banco quando o cliente manda os dados e pede o
  código, e segura o horário por 10 min. Vencido, um job passa para `EXPIRED` (um UPDATE idempotente pela função
  `expire_pending_bookings`, seguro com várias instâncias sem trava); antes disso, horários livres e novos agendamentos
  já ignoram o pendente vencido. Pela página, o nome e o e-mail de quem confirmou ficam no agendamento e são o que a
  agenda mostra (o cadastro por telefone pode ser de outra pessoa).
- Cliente: um por telefone (só dígitos) no estabelecimento. No painel, telefone já cadastrado com outro nome é 409 sem
  revelar o nome guardado. Na página pública (fase 2) esse 409 seria enumeração de clientes: lá a identidade vem da
  verificação por código, e o fluxo nunca responde diferente para telefone conhecido ou não.
- Horário local que não existe ou se repete no dia (horário de verão) é recusado ao agendar (400), nunca ajustado em
  silêncio.
- A duração máxima de um agendamento (serviço + preparo, 600 min) vem dos limites do catálogo e é CHECK no banco: as
  consultas "o que ocupa" contam com ela.
**Consequência:** a fase 2 acrescenta status sem mexer na restrição; a regra de agendar (oferta, grade, horizonte,
expediente, cliente) hoje mora em `BookingService.create`, atrás do guard de membro — na fase 2 ela vira um caso de
uso sem guard próprio (`Propagation.MANDATORY`), chamado pelo painel e pela página pública, cada um com o seu guard.

## ADR-010 — Entrada na equipe por convite: função SECURITY DEFINER e autorização por profissional

**Contexto:** quem aceita um convite ainda não participa do estabelecimento: o RLS esconde o convite e a política de
`memberships` (V1) só aceita o dono de um estabelecimento novo. Depois de entrar, o profissional precisa de uma regra
única de "em que agenda posso mexer", usada por vários módulos.
**Decisão:**
- Aceitar é a função `redeem_invite` (V5), `SECURITY DEFINER`, a **única** que grava com o RLS desligado (as da V1 só
  respondem sim/não) — exceção registrada ao ADR-006. Ela só cria participação `PROFESSIONAL` para o usuário da
  transação (`app_user()`), confere código/uso/revogação/validade/profissional ativo sem vínculo, trava o convite e o
  profissional, trata a corrida da mesma conta com `ON CONFLICT`, e não devolve nenhum dado do convite. `search_path`
  fixo com `pg_temp` por último (também aplicado às funções da V1). O instante vem da aplicação (o mesmo relógio que
  gravou a validade); só a aplicação chama a função (único papel com USAGE no schema).
- O vínculo `professionals.user_id` tem FK composta para a participação, `ON DELETE SET NULL (user_id)`: não existe
  vínculo sem participação, e tirar alguém da equipe (apagar a participação) desfaz o vínculo no banco.
- O convite mora em `catalog` (dono dos profissionais), não em `tenant`: `catalog` já depende de `tenant` (guard), e o
  contrário criaria um ciclo — garantido por ArchUnit.
- Autorização por profissional (`CatalogService.Actor`): o dono age em tudo; o profissional da equipe só no
  profissional **ativo** vinculado à conta dele; membro sem vínculo (ou com o profissional desativado), em nenhum. O que
  pode ser do estabelecimento inteiro (feriado) é só do dono (`actsOn(null)`). Na resposta de criação de agendamento,
  o profissional não recebe o e-mail guardado de um cliente que já existia.
**Consequência:** uma porta de escrita fora do RLS, pequena e testada sozinha (`InviteRowLevelSecurityTest`); a regra
"de quem é a agenda" num lugar só. Para a fase 2, o agendamento público precisa de uma origem "cliente" além do
`Actor` da equipe (ADR-009): a checagem de acesso fica na entrada, não no caso de uso.

## ADR-011 — Página pública: transação anônima dentro do estabelecimento do link

**Contexto:** o cliente agenda sem login (fase 2). A requisição anônima não tem `app.user_id` nem `app.tenant_id`, e
o RLS esconde tudo — inclusive o estabelecimento dono do link.
**Decisão:**
- O link (slug) vira o estabelecimento da transação pelo guard `tenant.PublicAccess`: formato validado, depois a
  função `tenant_by_slug` (`SECURITY DEFINER`, V6) devolve **só o id** — nenhum outro dado; link malformado ou
  inexistente dão o mesmo 404. A partir daí valem as políticas de sempre (`tenant_id = app_tenant()`), sem usuário.
- Rotas públicas só em `/api/public/{link}/…`; na 2a, só `GET`. O que sai é o mínimo para o cliente: nome e fuso,
  serviços ativos (sem preparo), profissionais ativos (só nome) e horários livres. Inativo, sem vínculo ou
  inexistente: o mesmo 404 (nada a deduzir).
- As regras são as do painel, chamadas sem guard próprio (`ScheduleService.slots`, `CatalogService.activeServices` /
  `activeProfessionalsFor`, com `Propagation.MANDATORY`) e só pela página pública — ArchUnit garante quem chama.
- Limite por IP próprio (120/min) e, somando todas as origens, por estabelecimento (1200/min), no Redis; separado do
  limite da autenticação. A página do cliente no front fica fora do contexto de sessão da equipe (não tenta renovar
  sessão, não gasta o limite de login do IP compartilhado do salão).
- Escrita anônima (2b) só por esse mesmo guard: reserva no Redis (5 min, `SET NX`, até 3 por origem), cliente e
  agendamento PENDING (segura o horário por 10 min), confirmado com o código de 6 dígitos enviado ao e-mail — o RLS
  continua valendo sem usuário. Expirar os pendentes vencidos de todos os estabelecimentos é a função
  `expire_pending_bookings` (V7, `SECURITY DEFINER`, um UPDATE idempotente), chamada por um job; os horários livres e a
  criação de agendamento já ignoram o pendente vencido antes disso.
- Link pessoal (2c): o token de acesso do agendamento (256 bits) consulta, cancela e remarca, pelas rotas
  `/bookings/lookup|cancel|reschedule`, dentro da política do estabelecimento (`TenantService.Policy`): a página e o
  link seguem a antecedência, a janela e o prazo; o painel não. Remarcar cancela o antigo e cria o novo na mesma
  transação, passando o token. Avisos por e-mail saem depois do commit e não desfazem nada se falharem.
**Consequência:** a página pública não tem caminho para dados de outro estabelecimento nem de clientes; os horários
seguem as mesmas regras do painel, mais a política na página e no link pessoal.

## ADR-012 — Cache dos horários livres: versão por estabelecimento, renovada a cada escrita gravada

**Contexto:** os horários livres da página pública são a leitura mais frequente e a mais cara (catálogo, expediente,
bloqueios e agendamentos do dia: ~10 idas ao banco). Meta: < 150 ms p95 com cache. Quase tudo que um estabelecimento
grava mexe neles (agendamento, cancelamento, expediente, bloqueio, serviço, vínculo), e esquecer uma invalidação
mostraria horário ocupado como livre.
**Decisão:**
- Chave no Redis com a **versão do estabelecimento**: `slots:{estabelecimento}:{versão}:{profissional}:{serviço}:{dia}`,
  60 s de TTL. A versão (`slots:v:{estabelecimento}`, `INCR`, sem expirar: se voltasse a 1, entradas velhas da
  versão 1 seriam servidas) sobe depois do commit de **toda transação que entrou no estabelecimento e escreveu** —
  quem avisa é `DbSession.enterTenant` (por onde toda escrita passa para o RLS deixar ver as linhas), com o evento
  `TenantDataChanged`. "Escreveu" é o PostgreSQL quem diz (`txid_current_if_assigned()` antes do commit): leitura,
  transação desfeita e tentativa anônima que não grava nada (um `confirm` com token inventado, em laço) não renovam.
  Nenhuma escrita precisa lembrar de invalidar.
- O aviso é um evento em memória, entregue no máximo uma vez e fora do outbox (ADR-003): perdê-lo (Redis fora, queda
  entre o commit e o `INCR`) só atrasa a renovação até o TTL. Erro no aviso nunca vira 500 depois do commit.
- O cache guarda só as regras do painel (o pesado). A política (janela, antecedência) e o "a partir de agora" são
  aplicados por cima em toda leitura; o TTL cobre o que muda sem escrita (pendente que venceu pelo relógio, ou pelo
  job sem estabelecimento na transação).
- Só a página pública usa o cache. A reserva (`POST /holds`) e o painel leem o banco; a restrição de exclusão
  continua sendo a garantia final.
- Redis fora do ar: calcula direto (leitura) e segue (aviso do commit) — o cache nunca derruba a página.
**Consequência:** horário mostrado pode estar até 60 s velho só no que muda sem escrita; tudo o que é gravado aparece
na leitura seguinte. Uma versão por estabelecimento renova também o que não mudou (escrita em um profissional renova
os outros): simples e correto, ao custo de mais recálculos em estabelecimentos muito ativos — medir com o k6.

